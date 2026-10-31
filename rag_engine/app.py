"""启动：python -m uvicorn rag_engine.app:app --host 127.0.0.1 --port 8000。"""
import asyncio
import hmac
import json
import logging
import uuid
from contextlib import asynccontextmanager
from pathlib import Path, PurePosixPath

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import ValidationError

from .config import Settings
from .errors import EngineError
from .schemas import IndexMetadata, QueryRequest
from .service import RagEngine

logger = logging.getLogger("rag_engine")


def sse(event: str, payload: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


def request_id(request: Request, fallback=None):
    value = request.headers.get("X-Request-Id") or fallback
    if value and len(value) <= 128 and all(32 <= ord(char) < 127 for char in value):
        return value
    return str(uuid.uuid4())


def create_app(settings: Settings | None = None, engine: RagEngine | None = None) -> FastAPI:
    if settings is None:
        # 仅正常读取配置，日志和健康响应绝不回显任何密钥。
        load_dotenv(Path(__file__).resolve().parents[1] / ".env", override=False)
        settings = Settings()
    rag = engine if engine is not None else RagEngine(settings)

    @asynccontextmanager
    async def lifespan(application):
        yield
        if hasattr(rag.store, "close"):
            await asyncio.to_thread(rag.store.close)

    application = FastAPI(title="Knowledge Engine Internal RAG API", version="2.0.0", lifespan=lifespan)
    application.state.engine = rag

    @application.middleware("http")
    async def attach_request_id(request: Request, call_next):
        request.state.request_id = request_id(request)
        response = await call_next(request)
        response.headers["X-Request-Id"] = request.state.request_id
        return response

    @application.exception_handler(EngineError)
    async def engine_error(request, exc):
        logger.warning("request failed request_id=%s code=%s", request.state.request_id, exc.code)
        return JSONResponse(exc.payload(request.state.request_id), status_code=exc.status)

    @application.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        fields = ", ".join(".".join(str(part) for part in error["loc"]) for error in exc.errors())
        error = EngineError("VALIDATION_ERROR", "请求字段无效：" + fields, 400)
        return JSONResponse(error.payload(request.state.request_id), status_code=400)

    @application.exception_handler(Exception)
    async def unexpected_error(request, exc):
        logger.error("unhandled request_id=%s exception_type=%s", request.state.request_id, type(exc).__name__)
        error = EngineError("INTERNAL_ERROR", "引擎处理失败，请使用 request_id 排查", 500)
        return JSONResponse(error.payload(request.state.request_id), status_code=500)

    async def authenticate(x_service_key: str | None = Header(default=None)):
        if not settings.service_key:
            raise EngineError("SERVICE_KEY_NOT_CONFIGURED", "引擎尚未配置内部服务认证密钥")
        if not x_service_key or not hmac.compare_digest(x_service_key, settings.service_key):
            raise EngineError("UNAUTHORIZED", "内部服务认证失败", 401)

    @application.get("/health/live")
    async def live():
        return {"status": "alive", "version": "2.0.0"}

    @application.get("/health/ready")
    async def ready():
        dependencies = {"service_auth": {"configured": bool(settings.service_key)},
            "embedding": {"configured": bool(settings.embedding_model),
                          "loaded": bool(getattr(rag.embedding, "loaded", False)), "device": settings.device},
            "rerank": {"configured": bool(settings.rerank_model), "loaded": bool(getattr(rag.reranker, "loaded", False))},
            "llm": {"configured": bool(settings.llm_key and settings.llm_model), "connected": "not_probed"},
            "milvus": {"configured": bool(settings.milvus_uri), "connected": False}}
        if hasattr(rag.embedding, "model_status"):
            dependencies["embedding"].update(rag.embedding.model_status())
        if settings.milvus_uri:
            try:
                await asyncio.wait_for(asyncio.to_thread(rag.store.ping), timeout=4)
                dependencies["milvus"]["connected"] = True
            except Exception:
                dependencies["milvus"]["error_code"] = "MILVUS_UNAVAILABLE"
        device_ok = True
        if settings.device.startswith("cuda"):
            try:
                import torch
                device_ok = torch.cuda.is_available()
            except ImportError:
                device_ok = False
        dependencies["embedding"]["device_compatible"] = device_ok
        is_ready = (bool(settings.service_key) and dependencies["embedding"]["loaded"] and device_ok
                    and dependencies["embedding"].get("inference_verified", False)
                    and dependencies["milvus"]["connected"] and dependencies["llm"]["configured"])
        # 冷启动不会为了健康探针下载/装载 GB 级模型；loaded=false 不宣称模型就绪。
        body = {"status": "ready" if is_ready else "not_ready", "ready": is_ready,
                "dependencies": dependencies, "capabilities": {"markdown": True, "txt": True,
                "pdf": bool(settings.mineru_url and settings.mineru_token), "vlm": settings.enable_vlm,
                "minio": settings.enable_minio, "web": bool(settings.enable_web and settings.web_url and settings.web_key)},
                "cold_start": not dependencies["embedding"]["loaded"]}
        return JSONResponse(body, status_code=200 if is_ready else 503)

    @application.post("/internal/v1/documents/index", dependencies=[Depends(authenticate)])
    async def index_document(request: Request, file: UploadFile = File(...), metadata: str = Form(...)):
        try:
            parsed = IndexMetadata.model_validate_json(metadata)
        except (ValidationError, ValueError):
            raise EngineError("VALIDATION_ERROR", "metadata 必须是含知识库、文档与版本 ID 的有效 JSON", 400)
        request.state.request_id = request_id(request, parsed.request_id)
        filename = PurePosixPath((file.filename or "document.md").replace("\\", "/")).name
        if not filename or len(filename.encode("utf-8")) > 512:
            raise EngineError("INVALID_FILENAME", "文件名为空或过长", 400)
        try:
            data = await file.read(settings.max_upload_bytes + 1)
        finally:
            await file.close()
        if len(data) > settings.max_upload_bytes:
            raise EngineError("FILE_TOO_LARGE", "文件超出上传大小限制", 413)
        if not data:
            raise EngineError("EMPTY_DOCUMENT", "上传文件为空", 400)
        try:
            async with asyncio.timeout(settings.index_timeout_seconds):
                async with rag.semaphore:
                    return await rag.index(data, filename, parsed)
        except TimeoutError:
            raise EngineError("INDEX_TIMEOUT", "文档索引超时，可幂等重试", 504, True)

    @application.delete("/internal/v1/documents/{document_id}", dependencies=[Depends(authenticate)])
    async def delete_document(document_id: str, knowledge_base_id: str, index_version: str):
        try:
            metadata = IndexMetadata(document_id=document_id, knowledge_base_id=knowledge_base_id, index_version=index_version)
        except ValidationError:
            raise EngineError("VALIDATION_ERROR", "文档、知识库或索引版本无效", 400)
        try:
            async with asyncio.timeout(settings.index_timeout_seconds):
                async with rag.semaphore:
                    return await rag.delete(metadata.knowledge_base_id, metadata.document_id, metadata.index_version)
        except TimeoutError:
            raise EngineError("DELETE_TIMEOUT", "文档删除超时，可幂等重试", 504, True)

    async def execute(request, query, operation):
        request.state.request_id = request_id(request, query.request_id)
        try:
            async with asyncio.timeout(settings.timeout_seconds):
                async with rag.semaphore:
                    return await operation(query, request.state.request_id)
        except TimeoutError:
            raise EngineError("REQUEST_TIMEOUT", "RAG 请求超时", 504, True)

    @application.post("/internal/v1/retrieve", dependencies=[Depends(authenticate)])
    async def retrieve(request: Request, query: QueryRequest):
        return await execute(request, query, rag.retrieve)

    @application.post("/internal/v1/answer", dependencies=[Depends(authenticate)])
    async def answer(request: Request, query: QueryRequest):
        return await execute(request, query, rag.answer)

    @application.post("/internal/v1/answer/stream", dependencies=[Depends(authenticate)])
    async def stream_answer(request: Request, query: QueryRequest):
        rid = request_id(request, query.request_id)
        request.state.request_id = rid

        async def events():
            upstream, pending = None, None
            try:
                async with asyncio.timeout(settings.timeout_seconds):
                    async with rag.semaphore:
                        upstream = rag.answer_events(query, rid)
                        while True:
                            pending = asyncio.create_task(anext(upstream))
                            while not pending.done():
                                await asyncio.wait({pending}, timeout=15)
                                if not pending.done():
                                    if await request.is_disconnected():
                                        return
                                    yield ": keep-alive\n\n"
                            try:
                                event, payload = pending.result()
                            except StopAsyncIteration:
                                break
                            pending = None
                            if await request.is_disconnected():
                                return
                            yield sse(event, payload)
                            if event in {"done", "error"}:
                                break
            except asyncio.CancelledError:
                # StreamingResponse 断连取消本生成器；finally 继续关闭 LLM 上游流。
                raise
            except TimeoutError:
                yield sse("error", EngineError("REQUEST_TIMEOUT", "RAG 流式请求超时", 504, True).payload(rid))
            except EngineError as exc:
                yield sse("error", exc.payload(rid))
            except Exception as exc:
                logger.error("stream failed request_id=%s exception_type=%s", rid, type(exc).__name__)
                yield sse("error", EngineError("STREAM_FAILED", "RAG 流式请求失败", 502, True).payload(rid))
            finally:
                if pending is not None:
                    pending.cancel()
                    try:
                        await pending
                    except (asyncio.CancelledError, StopAsyncIteration, Exception):
                        # 原异常已在上层映射为单个终态；这里只负责回收生成任务。
                        pass
                if upstream is not None:
                    await upstream.aclose()

        return StreamingResponse(events(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    return application


app = create_app()
