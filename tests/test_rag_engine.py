import asyncio
import io
import json
import threading
import time
import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx

from fastapi.testclient import TestClient

from rag_engine.app import create_app
from rag_engine.config import Settings
from rag_engine.errors import EngineError
from rag_engine.models import BgeEmbedding
from rag_engine.parsing import (DocumentParser, MAX_DOCUMENT_BLOCKS, MAX_DOCUMENT_CHARS,
                                ParsingBudget, TableParser, normalize_tables, split_markdown)
from rag_engine.prompts import evidence_block
from rag_engine.schemas import DocumentVersion, Evidence, IndexMetadata, QueryRequest, RetrievalOptions
from rag_engine.service import NO_EVIDENCE_ANSWER, RagEngine, rrf_merge, select_context
from rag_engine.store import MilvusStore, scope_filter, search_hit_id
from rag_engine.web import McpWebSearch


def settings(**overrides):
    values = dict(service_key="test-service-key", embedding_model="", rerank_model="", milvus_uri="",
                  llm_key="", llm_model="", device="cpu", enable_minio=False, enable_vlm=False,
                  enable_web=False, max_chunk_chars=512, context_chars=1024, domain_mode="general")
    values.update(overrides)
    return Settings(**values)


class FakeEmbedding:
    """仅测试使用的嵌入依赖，不会被生产配置选择。"""
    loaded = True

    def __init__(self):
        self.calls = 0

    def encode(self, texts, query=False):
        self.calls += 1
        return [{"dense": [1.0], "sparse": {1: 0.5}} for _ in texts]


class MemoryStore:
    def __init__(self):
        self.rows = {}
        self.search_calls = 0

    def replace(self, metadata, chunks, vectors):
        self.delete(metadata.knowledge_base_id, metadata.document_id, metadata.index_version)
        self.rows.update({chunk["chunk_id"]: chunk.copy() for chunk in chunks})

    def delete(self, kb, doc, version):
        self.rows = {key: row for key, row in self.rows.items()
                     if (row["knowledge_base_id"], row["document_id"], row["index_version"]) != (kb, doc, version)}

    def search(self, vector, kb, allowed, options, prefix):
        self.search_calls += 1
        scope = {(doc.document_id, doc.index_version) for doc in allowed}
        rows = [row for row in self.rows.values() if row["knowledge_base_id"] == kb
                and (row["document_id"], row["index_version"]) in scope]
        return [{**row, "fusion_score": 1 / rank, "channel_ranks": {f"{prefix}_dense": rank}}
                for rank, row in enumerate(rows[:options.candidate_k], 1)]


class FakeLlm:
    def __init__(self, fail=False):
        self.fail = fail
        self.closed = False
        self.calls = 0

    async def complete(self, messages, max_tokens=1400):
        self.calls += 1
        return "复位步骤见资料 [1]。"

    async def stream(self, messages):
        self.calls += 1
        try:
            yield "复位"
            if self.fail:
                raise EngineError("LLM_FAILED", "测试的模型失败", 502)
            yield "步骤 [1]。"
        finally:
            self.closed = True


class FailingReranker:
    loaded = False

    def score(self, query, texts):
        raise RuntimeError("test device error")


def make_engine(config=None, llm=None):
    return RagEngine(config or settings(), embedding=FakeEmbedding(), store=MemoryStore(),
                     reranker=FailingReranker(), llm=llm or FakeLlm())


def metadata(kb="kb-a", doc="manual", version="v1"):
    return IndexMetadata(knowledge_base_id=kb, document_id=doc, index_version=version)


def query(kb="kb-a", docs=None, **options):
    return QueryRequest(knowledge_base_id=kb, query="如何复位设备？",
                        allowed_documents=docs if docs is not None else [DocumentVersion(document_id="manual", index_version="v1")],
                        retrieval=RetrievalOptions(**options))


def evidence(key, content="支持的技术事实", **overrides):
    values = dict(chunk_id=key, document_id="manual", knowledge_base_id="kb-a", index_version="v1",
                  title="复位", content=content, source="manual.md")
    values.update(overrides)
    return Evidence(**values)


class StoreResultTests(unittest.TestCase):
    def test_custom_and_legacy_primary_key_results_keep_channel_ranks(self):
        entity = dict(chunk_id="chunk-a", knowledge_base_id="kb-a", document_id="manual",
                      index_version="v1", title="操作手册", content="复位步骤", source="manual.md", page=-1)
        for primary_key in ("chunk_id", "id"):
            with self.subTest(primary_key=primary_key):
                hit = {primary_key: "chunk-a", "distance": .9, "entity": entity}
                store = MilvusStore(settings())
                store._ready = True
                store._client = SimpleNamespace(search=lambda **kwargs: [[hit]],
                                                hybrid_search=lambda **kwargs: [[hit]])
                rows = store.search({"dense": [1.], "sparse": {1: 1.}}, "kb-a",
                                    [DocumentVersion(document_id="manual", index_version="v1")],
                                    RetrievalOptions(top_k=1, candidate_k=3))
                self.assertEqual(rows[0]["chunk_id"], "chunk-a")
                self.assertEqual(rows[0]["channel_ranks"],
                                 {"original_dense": 1, "original_sparse": 1, "original_hybrid": 1})
                self.assertIsNone(rows[0]["page"])

    def test_entity_primary_key_fallback_and_missing_key_validation(self):
        self.assertEqual(search_hit_id({"entity": {"chunk_id": "nested"}}), "nested")
        self.assertEqual(search_hit_id({"id": 0}), "0")
        with self.assertRaises(EngineError) as caught:
            search_hit_id({"entity": {}})
        self.assertEqual(caught.exception.code, "INVALID_SEARCH_RESULT")


class ParsingTests(unittest.TestCase):
    def test_plain_markdown_requires_no_pdf_vlm_minio(self):
        parser = DocumentParser(settings(mineru_url="", mineru_token=""))
        text, degraded = asyncio.run(parser.parse("# 标题\n无图正文".encode(), "manual.md", "namespace"))
        self.assertEqual(text, "# 标题\n无图正文")
        self.assertEqual(degraded, [])

    def test_empty_and_heading_only_are_errors(self):
        for text in ("", " \r\n ", "# 只有标题\n## 也没有正文"):
            with self.subTest(text=text), self.assertRaises(EngineError) as caught:
                split_markdown(text, "manual.md", metadata())
            self.assertEqual(caught.exception.code, "EMPTY_DOCUMENT")

    def test_all_chunks_fit_budget_after_merge(self):
        text = "# 父标题\r\n## 短段\r\n" + "字" * 40 + "\r\n## 长段\r\n" + "文。" * 1800
        chunks = split_markdown(text, "manual.md", metadata(), max_chars=300, min_chars=100)
        self.assertGreater(len(chunks), 5)
        self.assertTrue(all(len(chunk["content"]) <= 300 for chunk in chunks))
        self.assertEqual(sum(chunk["content"].count("文") for chunk in chunks), 1800)
        self.assertTrue(all(chunk["page"] is None for chunk in chunks))

    def test_fenced_heading_is_body(self):
        chunks = split_markdown("# 正标题\n```python\n# 注释\nprint(1)\n```", "manual.md", metadata())
        self.assertEqual(len(chunks), 1)
        self.assertEqual(chunks[0]["title"], "正标题")
        self.assertIn("# 注释", chunks[0]["content"])

    def test_markdown_and_html_tables_retain_column_labels(self):
        text = "| 模式 | 温度 |\n| --- | --- |\n| 高温 | 80度 |"
        self.assertIn("温度：80度", normalize_tables(text))
        html = "<table><tr><th>模式</th><th>温度</th></tr><tr><td>高温</td><td>80度</td></tr></table>"
        self.assertIn("温度：80度", normalize_tables(html))

    def test_malicious_html_span_bomb_is_rejected_before_large_allocation(self):
        text = "<table>" + '<tr><td rowspan="100" colspan="100">x</td></tr>' * 100 + "</table>"
        started = time.perf_counter()
        with self.assertRaises(EngineError) as caught:
            normalize_tables(text)
        self.assertEqual(caught.exception.code, "TABLE_TOO_LARGE")
        self.assertLess(time.perf_counter() - started, 1.0)

    def test_sparse_table_rectangle_area_is_bounded(self):
        parser = TableParser()
        # 占用单元格少于 20,000，但补齐矩形会超过 20,000；必须在分配前拒绝。
        parser.rows = [[("x", 1, 100)]] + [[("y", 1, 1)] for _ in range(201)]
        with self.assertRaises(EngineError) as caught:
            parser.grid()
        self.assertEqual(caught.exception.code, "TABLE_TOO_LARGE")

    def test_expansion_budget_is_shared_across_sections_and_tables(self):
        table = '<table><tr><td rowspan="100" colspan="100">x</td></tr></table>'
        with self.assertRaises(EngineError) as caught:
            split_markdown("# 一\n" + table + "\n# 二\n" + table + "\n# 三\n" + table,
                           "manual.md", metadata())
        self.assertEqual(caught.exception.code, "TABLE_TOO_LARGE")

    def test_table_expanded_text_is_bounded_before_large_concatenation(self):
        table = "<table><tr><th>" + "H" * 1000 + "</th></tr>" + (
            "<tr><td>" + "V" * 1000 + "</td></tr>") * 600 + "</table>"
        with self.assertRaises(EngineError) as caught:
            normalize_tables(table)
        self.assertEqual(caught.exception.code, "TABLE_TOO_LARGE")

    def test_document_text_and_block_limits_are_enforced(self):
        with self.assertRaises(EngineError) as caught:
            split_markdown("x" * (MAX_DOCUMENT_CHARS + 1), "manual.md", metadata())
        self.assertEqual(caught.exception.code, "DOCUMENT_TOO_COMPLEX")
        with self.assertRaises(EngineError) as caught:
            split_markdown("# 标题\n正文\n" * (MAX_DOCUMENT_BLOCKS + 1), "manual.md", metadata())
        self.assertEqual(caught.exception.code, "DOCUMENT_TOO_COMPLEX")

    def test_parser_deadline_is_enforced(self):
        budget = ParsingBudget()
        budget.deadline = time.monotonic() - 1
        with self.assertRaises(EngineError) as caught:
            normalize_tables("<table><tr><td>value</td></tr></table>", budget)
        self.assertEqual(caught.exception.code, "PARSING_BUDGET_EXCEEDED")

    def test_ids_are_stable_and_kb_version_isolated(self):
        first = split_markdown("# 标题\n正文", "a.md", metadata())
        self.assertEqual(first, split_markdown("# 标题\n正文", "a.md", metadata()))
        for other in (metadata(kb="kb-b"), metadata(version="v2"), metadata(doc="other")):
            self.assertNotEqual(first[0]["chunk_id"], split_markdown("# 标题\n正文", "a.md", other)[0]["chunk_id"])

    def test_zip_path_traversal_rejected(self):
        content = io.BytesIO()
        with zipfile.ZipFile(content, "w") as archive:
            archive.writestr("../full.md", "恶意路径")
        with self.assertRaises(EngineError) as caught:
            DocumentParser(settings())._read_archive(content.getvalue())
        self.assertEqual(caught.exception.code, "UNSAFE_ARCHIVE")

    def test_invalid_mineru_response_has_stable_error_code(self):
        for failure, code in ((httpx.ConnectError("private upstream details"), "PDF_UNAVAILABLE"),
                              (zipfile.BadZipFile("private archive details"), "PDF_PARSE_FAILED")):
            parser = DocumentParser(settings())
            parser._mineru = AsyncMock(side_effect=failure)
            with self.subTest(code=code), self.assertRaises(EngineError) as caught:
                asyncio.run(parser.parse(b"%PDF", "manual.pdf", "namespace"))
            self.assertEqual(caught.exception.code, code)
            self.assertNotIn("private", caught.exception.message)

    def test_filter_quotes_user_input_and_rejects_empty(self):
        with self.assertRaises(EngineError):
            scope_filter("kb-a", [])
        expression = scope_filter('kb" or true', [DocumentVersion(document_id='doc"', index_version="v1")])
        self.assertIn('kb\\" or true', expression)
        self.assertIn('doc\\"', expression)

    def test_rrf_counts_unique_hits_and_retains_ranks(self):
        a, b = evidence("a"), evidence("b")
        merged = rrf_merge({"original": [a, a, b], "hyde": [b, a]}, 10)
        score_a = next(item.fusion_score for item in merged if item.chunk_id == "a")
        self.assertAlmostEqual(score_a, 1 / 61 + 1 / 62)
        self.assertEqual(next(item for item in merged if item.chunk_id == "b").channel_ranks["hyde"], 1)

    def test_context_budget_counts_json_escaping_and_marks_selected(self):
        candidates = [evidence("a", '\\"\n' * 2000), evidence("b", "文字" * 2000)]
        citations = select_context(candidates, 2, 500)
        self.assertTrue(citations)
        actual = sum(len(evidence_block(index, item)) + 1 for index, item in enumerate(citations, 1))
        self.assertLessEqual(actual, 500)
        self.assertEqual(sum(item.selected for item in candidates), len(citations))
        self.assertLess(len(citations[0].content), len(candidates[0].content))

    def test_wrong_model_rejected_before_loading(self):
        with TemporaryDirectory() as directory:
            Path(directory, "config.json").write_text(json.dumps({"hidden_size": 768, "model_type": "bert"}), encoding="utf-8")
            model = BgeEmbedding(settings(embedding_model=directory))
            self.assertFalse(model.model_status()["model_compatible"])
            with self.assertRaises(EngineError) as caught:
                model.encode(["测试"])
            self.assertEqual(caught.exception.code, "EMBEDDING_MODEL_MISMATCH")
            self.assertFalse(model.loaded)

    def test_web_sources_explicit_and_invalid_urls_ignored(self):
        results = McpWebSearch._evidence([{"url": "https://example.org/doc", "snippet": "事实", "title": "官方文档"},
                                         {"url": "file:///etc/passwd", "snippet": "泄露"}], "kb-a")
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0].index_version, "external")
        self.assertEqual(results[0].channel_ranks, {"web": 1})


class EngineTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.engine = make_engine()
        await self.engine.index("# 复位\n长按复位按钮五秒。".encode(), "manual.md", metadata())

    async def test_kb_and_document_version_isolation(self):
        await self.engine.index("# 私密资料\n仅 B 用户可见。".encode(), "secret.md", metadata(kb="kb-b"))
        for request in (query(kb="kb-b", docs=[DocumentVersion(document_id="other", index_version="v1")]),
                        query(docs=[DocumentVersion(document_id="manual", index_version="v2")])):
            response = await self.engine.retrieve(request, "r-isolation")
            self.assertEqual(response.citations, [])
        response = await self.engine.retrieve(query(), "r-visible")
        self.assertTrue(response.citations)
        self.assertTrue(all(item.knowledge_base_id == "kb-a" for item in response.candidates))

    async def test_defensive_scope_check_after_store(self):
        original = self.engine.store.search
        def malicious(*args):
            return original(*args) + [evidence("cross", knowledge_base_id="kb-b").model_dump(),
                                      evidence("forbidden", document_id="other").model_dump()]
        self.engine.store.search = malicious
        result = await self.engine.retrieve(query(), "r-filter")
        self.assertEqual(len(result.candidates), 1)

    async def test_empty_scope_skips_models_and_external_tools(self):
        embedding_calls = self.engine.embedding.calls
        search_calls = self.engine.store.search_calls
        result = await self.engine.answer(query(docs=[], use_hyde=True, use_web=True), "r-empty")
        self.assertEqual(result.answer, NO_EVIDENCE_ANSWER)
        self.assertEqual(result.candidates, [])
        self.assertEqual(self.engine.embedding.calls, embedding_calls)
        self.assertEqual(self.engine.store.search_calls, search_calls)
        self.assertEqual(self.engine.llm.calls, 0)

    async def test_rerank_failure_falls_back_to_fusion(self):
        result = await self.engine.retrieve(query(), "r-fallback")
        self.assertTrue(result.citations)
        self.assertIsNone(result.candidates[0].rerank_score)
        self.assertTrue(result.candidates[0].selected)
        self.assertTrue(any("rerank_failed" in reason for reason in result.trace.degraded))

    async def test_idempotent_reindex_replaces_stale_chunks(self):
        await self.engine.index(("# 长文\n" + "正文" * 2000).encode(), "manual.md", metadata())
        old_ids = set(self.engine.store.rows)
        await self.engine.index(("# 长文\n" + "正文" * 2000).encode(), "manual.md", metadata())
        self.assertEqual(old_ids, set(self.engine.store.rows))
        await self.engine.index("# 更新\n新版内容".encode(), "manual.md", metadata())
        self.assertEqual(len(self.engine.store.rows), 1)
        await self.engine.delete("kb-a", "manual", "v1")
        await self.engine.delete("kb-a", "manual", "v1")
        self.assertEqual(self.engine.store.rows, {})
        self.assertEqual(self.engine._index_locks, {})

    async def test_document_locks_cleanup_with_waiters_and_cancellation(self):
        entered = asyncio.Event()
        release = asyncio.Event()
        async def holder():
            async with self.engine._document_lock("same"):
                entered.set()
                await release.wait()
        task = asyncio.create_task(holder())
        await entered.wait()
        async def waiter():
            async with self.engine._document_lock("same"):
                return
        waiting = asyncio.create_task(waiter())
        await asyncio.sleep(0)
        waiting.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await waiting
        release.set()
        await task
        self.assertEqual(self.engine._index_locks, {})

    async def test_cancel_stream_closes_llm_upstream(self):
        stream = self.engine.answer_events(query(), "r-cancel")
        for _ in range(3):
            await anext(stream)
        await stream.aclose()
        self.assertTrue(self.engine.llm.closed)

    async def test_cancelled_index_cannot_write_after_delete(self):
        started, release = threading.Event(), threading.Event()
        original = self.engine.store.replace
        def delayed_replace(*args):
            started.set()
            if not release.wait(3):
                raise RuntimeError("test worker timed out")
            original(*args)
        self.engine.store.replace = delayed_replace
        task = asyncio.create_task(self.engine.index("# 更新\n新版内容".encode(), "manual.md", metadata()))
        try:
            self.assertTrue(await asyncio.to_thread(started.wait, 2))
            task.cancel()
            await asyncio.sleep(0)
            deletion = asyncio.create_task(self.engine.delete("kb-a", "manual", "v1"))
            await asyncio.sleep(0)
            self.assertFalse(deletion.done(), "删除必须等待取消请求中不可中断的线程写入结束")
        finally:
            release.set()
        with self.assertRaises(asyncio.CancelledError):
            await task
        await deletion
        self.assertEqual(self.engine.store.rows, {})
        self.assertEqual(self.engine._index_locks, {})

    async def test_product_mode_retains_multiple_mentioned_names_without_scope_expansion(self):
        self.engine.settings.domain_mode = "product"
        async def extract(messages, max_tokens=1400):
            return json.dumps({"product_names": ["AX-100", "AX-200", "INVENTED-999"],
                               "rewritten_query": "AX-100 如何复位？"})
        self.engine.llm.complete = extract
        request = query()
        request.query = "AX-100 与 AX-200 的复位方式有何区别？"
        result = await self.engine.retrieve(request, "r-product")
        self.assertIn("AX-100", result.rewritten_query)
        self.assertIn("AX-200", result.rewritten_query)
        self.assertNotIn("INVENTED-999", result.rewritten_query)
        self.assertEqual(result.trace.config["product_names"], ["AX-100", "AX-200"])
        self.assertEqual(result.trace.config["product_scope"], "authorized_documents_only")
        self.assertTrue(all(item.document_id == "manual" for item in result.citations))

    async def test_product_mode_with_no_name_still_retrieves(self):
        self.engine.settings.domain_mode = "product"
        async def extract(messages, max_tokens=1400):
            return '{"product_names": [], "rewritten_query": "如何复位设备？"}'
        self.engine.llm.complete = extract
        result = await self.engine.retrieve(query(), "r-no-product")
        self.assertTrue(result.citations)
        self.assertEqual(result.trace.config["product_names"], [])

    async def test_product_invalid_json_degrades_to_general_retrieval(self):
        self.engine.settings.domain_mode = "product"
        async def invalid(messages, max_tokens=1400):
            return "不是 JSON"
        self.engine.llm.complete = invalid
        request = query()
        result = await self.engine.retrieve(request, "r-product-failed")
        self.assertTrue(result.citations)
        self.assertEqual(result.rewritten_query, request.query)
        self.assertTrue(any("product_extraction_failed:INVALID_RESPONSE" in reason for reason in result.trace.degraded))

    async def test_success_stream_has_one_terminal_and_request_ids(self):
        events = [(name, payload) async for name, payload in self.engine.answer_events(query(), "r-success")]
        self.assertEqual([name for name, _ in events if name in {"done", "error"}], ["done"])
        self.assertTrue(all(payload["request_id"] == "r-success" for _, payload in events))
        self.assertEqual(events[-1][1]["answer"], "复位步骤 [1]。")
        self.assertTrue(self.engine.llm.closed)

    async def test_web_failure_is_explicit_degradation(self):
        result = await self.engine.retrieve(query(use_web=True), "r-web")
        self.assertTrue(any("WEB_DISABLED" in reason for reason in result.trace.degraded))

    async def test_hyde_merges_with_original_and_retains_channel_ranks(self):
        result = await self.engine.retrieve(query(use_hyde=True, use_rerank=False), "r-hyde")
        self.assertEqual(len(result.candidates), 1)
        candidate = result.candidates[0]
        self.assertAlmostEqual(candidate.fusion_score, 2 / 61)
        self.assertEqual(candidate.channel_ranks["original"], 1)
        self.assertEqual(candidate.channel_ranks["hyde"], 1)
        self.assertIn("original_dense", candidate.channel_ranks)
        self.assertIn("hyde_dense", candidate.channel_ranks)
        self.assertEqual(result.trace.config["query_fusion"], "RRF(k=60)")

    async def test_original_and_hyde_run_in_parallel_then_join_before_fusion(self):
        original_started, hyde_started = asyncio.Event(), asyncio.Event()
        async def synchronized_search(text, request, prefix):
            if prefix == "original":
                original_started.set()
                await asyncio.wait_for(hyde_started.wait(), timeout=2)
            else:
                await asyncio.wait_for(original_started.wait(), timeout=2)
                hyde_started.set()
            return [evidence(prefix)]
        self.engine._search = synchronized_search
        result = await self.engine.retrieve(query(use_hyde=True, use_rerank=False), "r-parallel")
        self.assertEqual({item.chunk_id for item in result.candidates}, {"original", "hyde"})
        self.assertEqual(len(result.citations), 2)
        self.assertIn("original_recall", result.trace.durations_ms)
        self.assertIn("hyde", result.trace.durations_ms)

    async def test_asgi_request_cancellation_closes_upstream(self):
        started = asyncio.Event()
        llm = self.engine.llm
        async def slow_stream(messages):
            try:
                yield "开始"
                started.set()
                await asyncio.Event().wait()
            finally:
                llm.closed = True
        llm.stream = slow_stream
        application = create_app(self.engine.settings, self.engine)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=application), base_url="http://test") as client:
            pending = asyncio.create_task(client.post("/internal/v1/answer/stream",
                headers={"X-Service-Key": self.engine.settings.service_key}, json=query().model_dump()))
            await asyncio.wait_for(started.wait(), timeout=2)
            pending.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await pending
        self.assertTrue(llm.closed)
        self.assertEqual(self.engine.semaphore._value, self.engine.settings.max_concurrency)


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.config = settings(max_upload_bytes=1024)
        self.engine = make_engine(self.config)
        self.client = TestClient(create_app(self.config, self.engine))
        self.headers = {"X-Service-Key": self.config.service_key, "X-Request-Id": "api-request"}
        self.payload = query().model_dump()

    def upload(self, content=b"# Manual\nReset procedure."):
        return self.client.post("/internal/v1/documents/index", headers=self.headers,
            files={"file": ("manual.md", content, "text/markdown")}, data={"metadata": metadata().model_dump_json()})

    def test_auth_required_and_liveness_public(self):
        response = self.client.post("/internal/v1/retrieve", json=self.payload)
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["code"], "UNAUTHORIZED")
        self.assertEqual(self.client.get("/health/live").status_code, 200)

    def test_missing_allowed_documents_is_not_search_all(self):
        del self.payload["allowed_documents"]
        response = self.client.post("/internal/v1/retrieve", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["request_id"], "api-request")
        self.assertEqual(self.engine.store.search_calls, 0)

    def test_upload_and_delete_contract(self):
        response = self.upload()
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["document_id"], "manual")
        self.assertEqual(response.json()["index_version"], "v1")
        self.assertEqual(response.json()["chunk_count"], 1)
        deletion = self.client.delete("/internal/v1/documents/manual?knowledge_base_id=kb-a&index_version=v1", headers=self.headers)
        self.assertEqual(deletion.json(), {"status": "deleted"})

    def test_file_size_and_empty_validation(self):
        self.assertEqual(self.upload(b"x" * 1025).status_code, 413)
        self.assertEqual(self.upload(b"").status_code, 400)

    def test_ready_distinguishes_configured_and_connected(self):
        response = self.client.get("/health/ready")
        self.assertEqual(response.status_code, 503)
        body = response.json()
        self.assertFalse(body["ready"])
        self.assertIn("configured", body["dependencies"]["milvus"])
        self.assertFalse(body["dependencies"]["milvus"]["connected"])
        self.assertEqual(body["dependencies"]["llm"]["connected"], "not_probed")

    def test_sse_success_terminal(self):
        self.assertEqual(self.upload().status_code, 200)
        response = self.client.post("/internal/v1/answer/stream", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.text.count("event: done\n"), 1)
        self.assertEqual(response.text.count("event: error\n"), 0)
        self.assertIn("event: retrieval", response.text)

    def test_sse_error_terminal_after_partial_delta(self):
        self.upload()
        self.engine.llm = FakeLlm(fail=True)
        response = self.client.post("/internal/v1/answer/stream", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 200)
        self.assertIn("event: delta", response.text)
        self.assertEqual(response.text.count("event: done\n"), 0)
        self.assertEqual(response.text.count("event: error\n"), 1)
        self.assertIn('"code": "LLM_FAILED"', response.text)
        self.assertTrue(self.engine.llm.closed)

    def test_sse_timeout_emits_one_error_and_closes_upstream(self):
        self.upload()
        self.config.timeout_seconds = 0.2
        llm = self.engine.llm
        async def slow_stream(messages):
            try:
                yield "已开始"
                await asyncio.Event().wait()
            finally:
                llm.closed = True
        llm.stream = slow_stream
        response = self.client.post("/internal/v1/answer/stream", headers=self.headers, json=self.payload)
        self.assertIn("event: delta", response.text)
        self.assertEqual(response.text.count("event: done\n"), 0)
        self.assertEqual(response.text.count("event: error\n"), 1)
        self.assertIn('"code": "REQUEST_TIMEOUT"', response.text)
        self.assertTrue(llm.closed)


if __name__ == "__main__":
    unittest.main()
