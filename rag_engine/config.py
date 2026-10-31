import os
from dataclasses import dataclass, field


def env(*names: str, default: str = "") -> str:
    return next((os.environ[name] for name in names if os.environ.get(name)), default)


def flag(name: str, default: bool = False) -> bool:
    return os.getenv(name, str(default)).lower() in {"true", "1", "yes"}


@dataclass
class Settings:
    service_key: str = field(default_factory=lambda: env("RAG_SERVICE_KEY"))
    milvus_uri: str = field(default_factory=lambda: env("MILVUS_URI", "MILVUS_URL"))
    milvus_token: str = field(default_factory=lambda: env("MILVUS_TOKEN"))
    collection: str = field(default_factory=lambda: env("RAG_COLLECTION", default="knowledge_chunks_v2"))
    embedding_model: str = field(default_factory=lambda: env("BGE_M3_PATH"))
    rerank_model: str = field(default_factory=lambda: env("BGE_RERANKER_LARGE"))
    device: str = field(default_factory=lambda: env("RAG_DEVICE", "BGE_DEVICE", default="cpu"))
    fp16: bool = field(default_factory=lambda: flag("BGE_FP16"))
    dense_dim: int = 1024
    dense_weight: float = 0.7
    sparse_weight: float = 0.3
    llm_key: str = field(default_factory=lambda: env("OPENAI_API_KEY", "OPEN_API_KEY"))
    llm_base: str = field(default_factory=lambda: env("OPENAI_BASE_URL", "OPEN_API_BASE", "OPENAI_API_BASE"))
    llm_model: str = field(default_factory=lambda: env("LLM_MODEL", "LLM_DEFAULT_MODEL"))
    vlm_model: str = field(default_factory=lambda: env("VL_MODEL"))
    enable_vlm: bool = field(default_factory=lambda: flag("RAG_ENABLE_VLM"))
    mineru_url: str = field(default_factory=lambda: env("MINERU_BASE_URL"))
    mineru_token: str = field(default_factory=lambda: env("MINERU_API_TOKEN"))
    web_url: str = field(default_factory=lambda: env("MCP_DASHSCOPE_BASE_URL"))
    web_key: str = field(default_factory=lambda: env("MCP_DASHSCOPE_API_KEY", "OPENAI_API_KEY", "OPEN_API_KEY"))
    enable_web: bool = field(default_factory=lambda: flag("RAG_ENABLE_WEB"))
    web_timeout_seconds: float = field(default_factory=lambda: float(env("RAG_WEB_TIMEOUT_SECONDS", default="8")))
    minio_endpoint: str = field(default_factory=lambda: env("MINIO_ENDPOINT"))
    minio_access: str = field(default_factory=lambda: env("MINIO_ACCESS_KEY"))
    minio_secret: str = field(default_factory=lambda: env("MINIO_SECRET_KEY"))
    minio_bucket: str = field(default_factory=lambda: env("MINIO_BUCKET_NAME", default="knowledge-engine"))
    enable_minio: bool = field(default_factory=lambda: flag("RAG_ENABLE_MINIO"))
    minio_secure: bool = field(default_factory=lambda: flag("MINIO_SECURE"))
    max_upload_bytes: int = field(default_factory=lambda: int(env("RAG_MAX_UPLOAD_BYTES", default="20971520")))
    max_chunk_chars: int = field(default_factory=lambda: int(env("RAG_CHUNK_CHARS", default="1800")))
    min_chunk_chars: int = 300
    context_chars: int = field(default_factory=lambda: int(env("RAG_CONTEXT_CHARS", default="14000")))
    history_chars: int = 12000
    domain_mode: str = field(default_factory=lambda: env("RAG_DOMAIN_MODE", default="general"))
    timeout_seconds: float = field(default_factory=lambda: float(env("RAG_TIMEOUT_SECONDS", default="120")))
    index_timeout_seconds: float = field(default_factory=lambda: float(env("RAG_INDEX_TIMEOUT_SECONDS", default="600")))
    milvus_timeout_seconds: float = 20
    max_concurrency: int = field(default_factory=lambda: int(env("RAG_MAX_CONCURRENCY", default="2")))

    def __post_init__(self):
        if not 256 <= self.max_chunk_chars <= 12000:
            raise ValueError("RAG_CHUNK_CHARS 必须在 256 到 12000 之间")
        if self.context_chars < 128 or self.max_concurrency < 1:
            raise ValueError("上下文预算或并发配置无效")
        if self.domain_mode not in {"general", "product"}:
            raise ValueError("RAG_DOMAIN_MODE 必须为 general 或 product")
        if self.max_upload_bytes < 1 or min(self.timeout_seconds, self.index_timeout_seconds,
                self.web_timeout_seconds, self.milvus_timeout_seconds) <= 0:
            raise ValueError("文件大小和超时配置必须为正数")
