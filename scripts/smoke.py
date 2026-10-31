"""启动真实 Java/FastAPI，以明确的模型/存储替身验证协议；不生成效果评测分数。"""
import json
import os
import secrets
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
import httpx

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_http(url, process):
    until = time.monotonic() + 60
    while time.monotonic() < until:
        if process.poll() is not None:
            raise RuntimeError("测试服务提前退出，请检查 runtime/smoke-* 日志")
        try:
            if httpx.get(url, timeout=2).status_code < 500:
                return
        except httpx.HTTPError:
            pass
        time.sleep(.2)
    raise RuntimeError("测试服务启动超时")


def main():
    jar = ROOT / "java-service/target/knowledge-business-service-1.0.0.jar"
    if not jar.is_file():
        print("请先在 java-service 执行 mvnw.cmd package，再运行协议冒烟。")
        return 2
    folder = ROOT / "runtime" / ("smoke-" + uuid.uuid4().hex[:10])
    folder.mkdir(parents=True)
    engine_port, java_port = free_port(), free_port()
    env = {**os.environ, "RAG_SERVICE_KEY": secrets.token_urlsafe(32),
        "RAG_ENGINE_URL": f"http://127.0.0.1:{engine_port}", "JAVA_PORT": str(java_port),
        "DATABASE_URL": "jdbc:h2:mem:smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "JAVA_UPLOAD_DIR": str(folder / "uploads")}
    processes, logs, checks = [], [], []
    try:
        commands = (
            ("python", [sys.executable, "-m", "uvicorn", "scripts.contract_fixture:app", "--host", "127.0.0.1", "--port", str(engine_port)]),
            ("java", ["java", "-jar", str(jar)]),
        )
        for name, command in commands:
            log = (folder / (name + ".log")).open("w", encoding="utf-8")
            logs.append(log)
            processes.append(subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0))
        wait_http(f"http://127.0.0.1:{engine_port}/health/live", processes[0])
        wait_http(f"http://127.0.0.1:{java_port}/api/v1/health", processes[1])
        with httpx.Client(base_url=f"http://127.0.0.1:{java_port}/api/v1", timeout=20) as client:
            def call(method, path, token=None, expected=200, **kwargs):
                response = client.request(method, path, headers={"Authorization": "Bearer " + token} if token else {}, **kwargs)
                if response.status_code != expected:
                    raise AssertionError(f"{method} {path}: expected={expected}, actual={response.status_code}, code={response.json().get('code')}")
                return response
            credentials = {"username": "smoke" + uuid.uuid4().hex[:8], "password": "SmokePass123"}
            token = call("POST", "/auth/register", json=credentials).json()["token"]
            other = call("POST", "/auth/register", json={**credentials, "username": credentials["username"] + "b"}).json()["token"]
            call("POST", "/auth/login", json=credentials)
            kb = call("POST", "/knowledge-bases", token, json={"name": "协议测试知识库", "description": "验证协议"}).json()["id"]
            call("GET", "/knowledge-bases/" + kb, other, expected=404)
            checks.append("注册/登录/知识库归属隔离")
            path = f"/knowledge-bases/{kb}/documents"
            body = b"# KE-100\n\nHold Reset for 5 seconds to reset network settings.\n"
            uploaded = call("POST", path, token, files={"file": ("manual.md", body, "text/markdown")}).json()
            duplicate = call("POST", path, token, files={"file": ("duplicate.md", body, "text/markdown")}).json()
            assert duplicate["duplicate"] and duplicate["document_id"] == uploaded["document_id"]
            until = time.monotonic() + 15
            while True:
                job = call("GET", "/jobs/" + uploaded["job_id"], token).json()
                if job["status"] == "SUCCEEDED":
                    break
                if job["status"] == "FAILED" or time.monotonic() >= until:
                    raise AssertionError("导入未成功：" + str(job.get("error_message")))
                time.sleep(.1)
            checks.append("multipart/真实解析切片/导入任务/上传幂等")
            result = call("POST", f"/knowledge-bases/{kb}/search", token, json={"query": "Reset"}).json()
            assert result["citations"] and result["citations"][0]["document_id"] == uploaded["document_id"]
            session = call("POST", "/sessions", token, json={"knowledge_base_id": kb, "title": "测试会话"}).json()["id"]
            response = call("POST", f"/sessions/{session}/messages/stream", token, json={"query": "Reset"})
            events = []
            for frame in response.text.replace("\r\n", "\n").split("\n\n"):
                event = next((line.split(":", 1)[1].strip() for line in frame.splitlines() if line.startswith("event:")), None)
                if event:
                    events.append(event)
            assert events.count("done") == 1 and "error" not in events
            assert all(name in events for name in ("meta", "retrieval", "delta"))
            history = call("GET", "/sessions/" + session, token).json()["messages"]
            assert history[-1]["status"] == "COMPLETE" and history[-1]["citations"]
            checks.append("FastAPI/LangGraph/Java SSE终态/引用持久化")
            # 使用新会话避免 query rewrite 改写协议故障触发词。
            session2 = call("POST", "/sessions", token, json={"knowledge_base_id": kb}).json()["id"]
            failed_stream = call("POST", f"/sessions/{session2}/messages/stream", token, json={"query": "触发协议失败"}).text.replace(" ", "")
            assert failed_stream.count("event:error") == 1 and "event:done" not in failed_stream
            assert call("GET", "/sessions/" + session2, token).json()["messages"][-1]["status"] == "FAILED"
            checks.append("流式上游失败/唯一错误终态/失败持久化")
            call("DELETE", "/documents/" + uploaded["document_id"], token)
            result = call("POST", f"/knowledge-bases/{kb}/search", token, json={"query": "Reset"}).json()
            assert result["citations"] == []
            checks.append("删除后不再召回")
        report = {"mode": "contract_fixture", "real_model_evaluation": False, "checks": checks, "passed": True}
        (folder / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in logs:
            log.close()


if __name__ == "__main__":
    raise SystemExit(main())
