from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DocumentVersion(Contract):
    document_id: str = Field(min_length=1, max_length=128)
    index_version: str = Field(min_length=1, max_length=128)


class IndexMetadata(DocumentVersion):
    knowledge_base_id: str = Field(min_length=1, max_length=128)
    request_id: str | None = Field(default=None, max_length=128)


class HistoryMessage(Contract):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=16000)


class RetrievalOptions(Contract):
    mode: Literal["dense", "sparse", "hybrid"] = "hybrid"
    top_k: int = Field(default=5, ge=1, le=20)
    candidate_k: int = Field(default=20, ge=1, le=100)
    use_rerank: bool = True
    use_hyde: bool = False
    use_web: bool = False

    @model_validator(mode="after")
    def check_sizes(self):
        if self.candidate_k < self.top_k:
            raise ValueError("candidate_k 必须大于等于 top_k")
        return self


class QueryRequest(Contract):
    knowledge_base_id: str = Field(min_length=1, max_length=128)
    query: str = Field(min_length=1, max_length=4000)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=40)
    # 必填且不提供默认值；空数组直接返回空证据，禁止放宽为整个集合。
    allowed_documents: list[DocumentVersion] = Field(max_length=1000)
    retrieval: RetrievalOptions = Field(default_factory=RetrievalOptions)
    request_id: str | None = Field(default=None, max_length=128)

    @field_validator("query")
    @classmethod
    def non_blank(cls, value):
        if not value.strip():
            raise ValueError("query 不得为空白")
        return value.strip()


class Evidence(Contract):
    chunk_id: str
    document_id: str
    knowledge_base_id: str
    index_version: str
    title: str
    content: str
    source: str
    page: int | None = None
    channel_ranks: dict[str, int] = Field(default_factory=dict)
    fusion_score: float = 0
    rerank_score: float | None = None
    selected: bool = False


class Trace(Contract):
    durations_ms: dict[str, float] = Field(default_factory=dict)
    degraded: list[str] = Field(default_factory=list)
    config: dict = Field(default_factory=dict)
    prompt_version: str = "v1"


class RetrievalResponse(Contract):
    request_id: str
    citations: list[Evidence]
    candidates: list[Evidence]
    trace: Trace
    rewritten_query: str


class AnswerResponse(RetrievalResponse):
    answer: str
