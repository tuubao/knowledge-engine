"""通过真实引擎接口执行检索消融，保存配置、逐题结果和失败原因。"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import platform
import statistics
import time
from pathlib import Path

import httpx
from dotenv import load_dotenv

from evaluation.metrics import percentile, retrieval_metrics

ROOT = Path(__file__).resolve().parents[1]
STRATEGIES = {
    "dense": {"mode": "dense", "use_rerank": False, "use_hyde": False},
    "hybrid": {"mode": "hybrid", "use_rerank": False, "use_hyde": False},
    "rerank": {"mode": "hybrid", "use_rerank": True, "use_hyde": False},
    "hyde": {"mode": "hybrid", "use_rerank": True, "use_hyde": True},
}


def run(args) -> tuple[dict, int]:
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    questions = [q for q in dataset["questions"] if args.split == "all" or q["split"] == args.split]
    if not questions:
        raise ValueError("没有匹配的问题")
    if args.limit:
        questions = questions[:args.limit]
    service_key = os.getenv("RAG_SERVICE_KEY", "")
    if not service_key:
        raise ValueError("先配置 RAG_SERVICE_KEY；不要把密钥作为命令行参数")
    headers = {"X-Service-Key": service_key}
    report = {
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "dataset_version": dataset["version"], "source": dataset["source"],
        "split": args.split, "k": args.k, "candidate_k": args.candidate_k,
        "granularity": "document relevance within the first K chunks; chunk ranks retained",
        "python": platform.python_version(), "strategies": {}, "indexing": [],
    }
    failed = False
    with httpx.Client(base_url=args.engine_url.rstrip("/"), headers=headers, timeout=args.timeout) as client:
        if args.index_samples:
            for document in dataset["documents"]:
                path = ROOT / document["path"]
                metadata = {"knowledge_base_id": dataset["knowledge_base_id"], "document_id": document["document_id"], "index_version": dataset["index_version"]}
                with path.open("rb") as stream:
                    response = client.post("/internal/v1/documents/index", data={"metadata": json.dumps(metadata)}, files={"file": (path.name, stream, "text/markdown")})
                response.raise_for_status()
                report["indexing"].append(response.json())
        allowed = [{"document_id": d["document_id"], "index_version": dataset["index_version"]} for d in dataset["documents"]]
        for strategy in args.strategies:
            options = {**STRATEGIES[strategy], "top_k": args.k, "candidate_k": args.candidate_k, "use_web": False}
            rows = []
            for question in questions:
                start = time.perf_counter()
                row = {"id": question["id"], "query": question["query"], "relevant_document_ids": question["relevant_document_ids"]}
                try:
                    response = client.post("/internal/v1/retrieve", json={"knowledge_base_id": dataset["knowledge_base_id"], "query": question["query"], "history": [], "allowed_documents": allowed, "retrieval": options})
                    response.raise_for_status()
                    result = response.json()
                    # 降级属于有效工程行为，但不能当作目标策略已经成功运行。
                    degraded = result.get("trace", {}).get("degraded", [])
                    if degraded:
                        row.update(status="degraded", degraded=degraded)
                        failed = True
                    else:
                        row["status"] = "ok"
                    docs = [x["document_id"] for x in result.get("citations", [])]
                    row.update(retrieval_metrics(docs, question["relevant_document_ids"], args.k))
                    row.update(retrieved_document_ids=docs, request_id=result.get("request_id"), trace=result.get("trace", {}))
                except (httpx.HTTPError, ValueError, KeyError) as exc:
                    failed = True
                    row.update(status="error", error=type(exc).__name__)
                    if isinstance(exc, httpx.HTTPStatusError):
                        try:
                            error = exc.response.json()
                            row["error"] = {key: error.get(key) for key in ("code", "message", "request_id")}
                        except ValueError:
                            row["http_status"] = exc.response.status_code
                row["elapsed_ms"] = round((time.perf_counter() - start) * 1000, 3)
                rows.append(row)
            scored = [r for r in rows if r["status"] == "ok" and r.get("answerable")]
            successful = [r["elapsed_ms"] for r in rows if r["status"] == "ok"]
            report["strategies"][strategy] = {
                "config": options, "question_count": len(rows), "scored_answerable_count": len(scored),
                "error_count": sum(r["status"] == "error" for r in rows),
                "degraded_count": sum(r["status"] == "degraded" for r in rows),
                "document_recall_at_k": statistics.mean(r["document_recall_at_k"] for r in scored) if scored else None,
                "document_mrr_at_k": statistics.mean(r["document_mrr_at_k"] for r in scored) if scored else None,
                "mean_ms": statistics.mean(successful) if successful else None,
                "p50_ms": percentile(successful, .5), "p95_ms": percentile(successful, .95),
                "no_answer_cases": [r for r in rows if not r["relevant_document_ids"]], "results": rows,
            }
    return report, 1 if failed else 0


def main() -> int:
    load_dotenv(ROOT / ".env")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=ROOT / "evaluation/dataset.json")
    parser.add_argument("--engine-url", default=os.getenv("RAG_ENGINE_URL", "http://127.0.0.1:8000"))
    parser.add_argument("--strategies", nargs="+", choices=STRATEGIES, default=list(STRATEGIES))
    parser.add_argument("--split", choices=["dev", "holdout", "all"], default="holdout")
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--candidate-k", type=int, default=20)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--index-samples", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "evaluation/reports/latest.json")
    args = parser.parse_args()
    if not (1 <= args.k <= 20 and args.k <= args.candidate_k <= 100):
        parser.error("需要 1 <= k <= 20 且 k <= candidate-k <= 100")
    if args.limit is not None and args.limit < 1:
        parser.error("limit 必须为正数")
    try:
        report, code = run(args)
    except (ValueError, httpx.HTTPError) as exc:
        print(f"评测未完成：{type(exc).__name__}。请检查引擎就绪状态与服务密钥。")
        return 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# 检索消融评测", "", f"数据集：{report['dataset_version']}；划分：{report['split']}；K={args.k}", "", "指标为文档相关性在前 K 个片段中的覆盖和首条相关结果排名。错误与降级样本不计入成功策略均值。", "", "| 策略 | 有答案有效样本 | Recall@K | MRR@K | P50(ms) | P95(ms) | 错误/降级 |", "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    def fmt(value):
        return "未测" if value is None else f"{value:.3f}"
    for name, data in report["strategies"].items():
        lines.append(f"| {name} | {data['scored_answerable_count']} | {fmt(data['document_recall_at_k'])} | {fmt(data['document_mrr_at_k'])} | {fmt(data['p50_ms'])} | {fmt(data['p95_ms'])} | {data['error_count']}/{data['degraded_count']} |")
    args.output.with_suffix(".md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"评测报告已保存：{args.output}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
