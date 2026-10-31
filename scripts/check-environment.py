"""只核查配置、权重身份与 Milvus TCP，不调用收费模型或输出密钥。"""
import argparse
import importlib.metadata
import json
import socket
import sys
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from dotenv import load_dotenv
from rag_engine.config import Settings
from rag_engine.models import BgeEmbedding


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-network", action="store_true")
    args = parser.parse_args()
    load_dotenv(ROOT / ".env")
    settings = Settings()
    report = {
        "python": sys.version.split()[0],
        "service_key_configured": bool(settings.service_key and not settings.service_key.startswith("replace-")),
        "embedding": BgeEmbedding(settings).model_status(),
        "rerank_path_exists": bool(settings.rerank_model and Path(settings.rerank_model).is_dir()),
        "llm_configured": bool(settings.llm_key and settings.llm_model),
        "milvus_configured": bool(settings.milvus_uri), "device": settings.device, "versions": {},
    }
    for name in ("fastapi", "langgraph", "pymilvus", "FlagEmbedding", "sentence-transformers", "torch"):
        try:
            report["versions"][name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            report["versions"][name] = "not_installed"
    if settings.milvus_uri and not args.skip_network:
        try:
            address = urlparse(settings.milvus_uri)
            with socket.create_connection((address.hostname, address.port or 19530), timeout=3):
                report["milvus_tcp_reachable"] = True
        except (OSError, ValueError):
            report["milvus_tcp_reachable"] = False
    report["note"] = "配置/TCP可达不等于模型推理和数据库协议已验证。"
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if (report["service_key_configured"] and report["embedding"]["model_compatible"]
                 and report["llm_configured"] and report.get("milvus_tcp_reachable", args.skip_network)) else 1


if __name__ == "__main__":
    raise SystemExit(main())
