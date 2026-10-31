"""懒加载真实模型；测试通过依赖注入提供替身，生产不提供假模型开关。"""
import asyncio
import json
import math
import threading
from pathlib import Path

from .config import Settings
from .errors import EngineError


class BgeEmbedding:
    def __init__(self, settings: Settings):
        self.settings, self._model, self._lock = settings, None, threading.RLock()
        self.inference_verified = False

    @property
    def loaded(self):
        return self._model is not None

    def model_status(self):
        path = Path(self.settings.embedding_model) if self.settings.embedding_model else None
        result = {"configured": path is not None, "local_path_exists": bool(path and path.is_dir()),
                  "model_compatible": False, "inference_verified": self.inference_verified}
        if not result["local_path_exists"]:
            result["error_code"] = "EMBEDDING_NOT_CONFIGURED"
            return result
        try:
            config = json.loads((path / "config.json").read_text(encoding="utf-8"))
            result["dimensions"] = config.get("hidden_size")
            result["model_compatible"] = (config.get("hidden_size") == self.settings.dense_dim
                and config.get("model_type") == "xlm-roberta" and (path / "sparse_linear.pt").is_file())
        except (OSError, ValueError):
            pass
        if not result["model_compatible"]:
            result["error_code"] = "EMBEDDING_MODEL_MISMATCH"
        return result

    def _get_model(self):
        if self._model is None:
            if not self.settings.embedding_model:
                raise EngineError("EMBEDDING_NOT_CONFIGURED", "请配置 BGE_M3_PATH 后再索引或检索")
            identity = self.model_status()
            if not identity["model_compatible"]:
                raise EngineError("EMBEDDING_MODEL_MISMATCH", "BGE_M3_PATH 必须指向真实 BGE-M3 本地目录（1024 维 XLM-RoBERTa 与 sparse_linear.pt），不能使用 BGE-base 代替")
            try:
                # 与原 pymilvus BGEM3EmbeddingFunction 使用同一 BGE-M3 权重与学习式词项权重。
                from FlagEmbedding import BGEM3FlagModel
                self._model = BGEM3FlagModel(self.settings.embedding_model,
                    devices=self.settings.device, use_fp16=self.settings.fp16 and self.settings.device != "cpu")
            except Exception as exc:
                raise EngineError("EMBEDDING_LOAD_FAILED", "BGE-M3 模型加载失败，请检查路径、设备与依赖兼容性") from exc
        return self._model

    def encode(self, texts: list[str], query: bool = False) -> list[dict]:
        if not texts or any(not text.strip() for text in texts):
            raise EngineError("INVALID_TEXT", "嵌入文本不得为空", 400)
        try:
            with self._lock:
                model = self._get_model()
                output = model.encode(texts, batch_size=4, max_length=2048, return_dense=True,
                                      return_sparse=True, return_colbert_vecs=False)
                dense, sparse = output["dense_vecs"], output["lexical_weights"]
                vectors = [{"dense": vector.tolist() if hasattr(vector, "tolist") else list(vector),
                         "sparse": {int(token): float(weight) for token, weight in lexical.items()}}
                        for vector, lexical in zip(dense, sparse, strict=True)]
                if len(vectors) != len(texts) or any(len(item["dense"]) != self.settings.dense_dim
                        or not item["sparse"] for item in vectors):
                    raise EngineError("EMBEDDING_MODEL_MISMATCH", "模型未生成预期的 1024 维稠密向量和非空学习式稀疏向量")
                if any(not all(math.isfinite(float(value)) for value in item["dense"])
                        or not all(math.isfinite(value) and value >= 0 for value in item["sparse"].values()) for item in vectors):
                    raise EngineError("EMBEDDING_INVALID_VECTOR", "模型输出包含无效向量数值", 502)
                self.inference_verified = True
                return vectors
        except EngineError:
            raise
        except Exception as exc:
            raise EngineError("EMBEDDING_FAILED", "BGE-M3 嵌入计算失败，请检查模型兼容性与设备显存", 503, True) from exc


class CrossEncoderReranker:
    def __init__(self, settings):
        self.settings, self._model, self._lock = settings, None, threading.RLock()

    @property
    def loaded(self):
        return self._model is not None

    def score(self, query: str, texts: list[str]) -> list[float]:
        if not self.settings.rerank_model:
            raise EngineError("RERANK_NOT_CONFIGURED", "未配置 BGE_RERANKER_LARGE")
        with self._lock:
            if self._model is None:
                from sentence_transformers import CrossEncoder
                import torch
                model_kwargs = {"torch_dtype": torch.float16} if self.settings.fp16 and self.settings.device != "cpu" else {}
                # 沿用原项目的 transformers 5 兼容方案，不调用已移除的 tokenizer API。
                self._model = CrossEncoder(self.settings.rerank_model, device=self.settings.device,
                    max_length=512, activation_fn=torch.nn.Identity(), model_kwargs=model_kwargs or None)
            scores = self._model.predict([(query, text) for text in texts], batch_size=4,
                                          show_progress_bar=False)
            values = scores.tolist() if hasattr(scores, "tolist") else scores
            return [float(value[0] if isinstance(value, list) else value) for value in values]


class LlmClient:
    def __init__(self, settings):
        self.settings = settings

    def configured(self):
        return bool(self.settings.llm_key and self.settings.llm_model)

    def _client(self):
        if not self.configured():
            raise EngineError("LLM_NOT_CONFIGURED", "请配置 OPENAI_API_KEY 与 LLM_MODEL")
        from openai import AsyncOpenAI
        return AsyncOpenAI(api_key=self.settings.llm_key, base_url=self.settings.llm_base or None,
                           timeout=self.settings.timeout_seconds, max_retries=0)

    async def complete(self, messages: list[dict], max_tokens: int = 1400) -> str:
        try:
            async with self._client() as client:
                response = await client.chat.completions.create(model=self.settings.llm_model,
                    messages=messages, temperature=0, max_tokens=max_tokens)
                text = response.choices[0].message.content
                if not text:
                    raise EngineError("LLM_EMPTY_RESPONSE", "模型未返回文本", 502, True)
                return text
        except (EngineError, asyncio.CancelledError):
            raise
        except Exception as exc:
            raise EngineError("LLM_FAILED", "语言模型调用失败，请检查服务配置或请求关联日志", 502, True) from exc

    async def stream(self, messages: list[dict]):
        stream = None
        try:
            async with self._client() as client:
                stream = await client.chat.completions.create(model=self.settings.llm_model,
                    messages=messages, temperature=0, max_tokens=1800, stream=True)
                try:
                    async for part in stream:
                        if part.choices:
                            text = part.choices[0].delta.content
                            if text:
                                yield text
                finally:
                    await stream.close()
        except (EngineError, asyncio.CancelledError):
            raise
        except Exception as exc:
            raise EngineError("LLM_FAILED", "语言模型流式调用失败", 502, True) from exc
