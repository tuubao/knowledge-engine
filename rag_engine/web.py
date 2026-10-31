"""保留原 DashScope MCP 网搜通道；只有明确开启时连接外部服务。"""
import asyncio
import hashlib
import json
from urllib.parse import urlparse

from .errors import EngineError
from .schemas import Evidence


class McpWebSearch:
    def __init__(self, settings):
        self.settings = settings

    async def search(self, query, kb):
        config = self.settings
        if not config.enable_web:
            raise EngineError("WEB_DISABLED", "网搜需要显式设置 RAG_ENABLE_WEB=true")
        if not config.web_url or not config.web_key:
            raise EngineError("WEB_NOT_CONFIGURED", "网搜缺少 MCP_DASHSCOPE_BASE_URL 或 MCP_DASHSCOPE_API_KEY")
        try:
            # SDK 2.x 使用 httpx2，避免旧节点依赖整个 Agent 运行时。
            import httpx2
            from mcp import ClientSession
            from mcp.client.streamable_http import streamable_http_client
            async with asyncio.timeout(config.web_timeout_seconds):
                async with httpx2.AsyncClient(headers={"Authorization": config.web_key},
                                               timeout=config.web_timeout_seconds) as http_client:
                    async with streamable_http_client(config.web_url, http_client=http_client) as streams:
                        async with ClientSession(*streams) as session:
                            await session.initialize()
                            result = await session.call_tool("bailian_web_search", {"query": query, "count": 3})
                            if getattr(result, "is_error", False) or getattr(result, "isError", False):
                                raise EngineError("WEB_SEARCH_FAILED", "MCP 网搜工具返回错误", 502)
                            pages = []
                            for block in result.content:
                                if getattr(block, "type", None) == "text":
                                    pages.extend(json.loads(block.text).get("pages") or [])
                            return self._evidence(pages, kb)
        except (EngineError, asyncio.CancelledError):
            raise
        except TimeoutError as exc:
            raise EngineError("WEB_TIMEOUT", "可选网搜超时", 504, True) from exc
        except Exception as exc:
            raise EngineError("WEB_SEARCH_FAILED", "可选 MCP 网搜不可用", 502, True) from exc

    @staticmethod
    def _evidence(pages, kb):
        results, seen = [], set()
        for page in pages[:10]:
            if not isinstance(page, dict):
                continue
            url, snippet = page.get("url", ""), page.get("snippet", "")
            if not isinstance(url, str) or not isinstance(snippet, str) or not snippet.strip():
                continue
            if urlparse(url).scheme not in {"https", "http"} or url in seen:
                continue
            seen.add(url)
            identity = hashlib.sha256(url.encode()).hexdigest()
            results.append(Evidence(chunk_id="web:" + identity, document_id="web:" + identity,
                knowledge_base_id=kb, index_version="external", title="[网络] " + str(page.get("title") or url)[:250],
                content=snippet[:4000], source=url, channel_ranks={"web": len(results) + 1}))
        return results
