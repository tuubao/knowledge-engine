"""仅供 smoke.py 使用的协议替身；真实运行入口没有假模型开关。"""
import os
from rag_engine.app import create_app
from rag_engine.config import Settings
from rag_engine.schemas import Evidence
from rag_engine.service import RagEngine


class EmbeddingFixture:
    loaded = True

    def encode(self, texts, query=False):
        return [{"dense": [1., 0.], "sparse": {1: 1.}} for _ in texts]

    def model_status(self):
        return {"inference_verified": True, "contract_fixture": True}


class MemoryStore:
    def __init__(self):
        self.rows = {}

    def replace(self, metadata, chunks, vectors):
        self.delete(metadata.knowledge_base_id, metadata.document_id, metadata.index_version)
        self.rows.update({row["chunk_id"]: dict(row) for row in chunks})

    def delete(self, kb, doc, version):
        self.rows = {key: row for key, row in self.rows.items()
                     if (row["knowledge_base_id"], row["document_id"], row["index_version"]) != (kb, doc, version)}

    def search(self, vector, kb, allowed, options, prefix="original"):
        scope = {(item.document_id, item.index_version) for item in allowed}
        rows = [Evidence(**row, fusion_score=1., channel_ranks={prefix + "_hybrid": rank}).model_dump()
                for rank, row in enumerate(self.rows.values(), 1)
                if row["knowledge_base_id"] == kb and (row["document_id"], row["index_version"]) in scope]
        return rows[:options.candidate_k]

    def ping(self):
        return True


class RerankFixture:
    loaded = True

    def score(self, query, texts):
        return list(range(len(texts), 0, -1))


class LlmFixture:
    async def complete(self, messages, **kwargs):
        return "协议测试替身：按住 Reset 5 秒后松开。[1]"

    async def stream(self, messages):
        if "触发协议失败" in str(messages):
            from rag_engine.errors import EngineError
            yield "部分协议测试文本"
            raise EngineError("FIXTURE_FAILURE", "协议测试预期失败", 502)
        for text in ("协议测试替身：", "按住 Reset 5 秒后松开。", "[1]"):
            yield text


settings = Settings(service_key=os.environ["RAG_SERVICE_KEY"], embedding_model="contract-fixture",
    rerank_model="contract-fixture", llm_key="fixture", llm_model="fixture",
    milvus_uri="http://contract-fixture", device="cpu", max_chunk_chars=800,
    enable_minio=False, enable_vlm=False, enable_web=False)
app = create_app(settings, RagEngine(settings, embedding=EmbeddingFixture(), store=MemoryStore(),
    reranker=RerankFixture(), llm=LlmFixture()))
