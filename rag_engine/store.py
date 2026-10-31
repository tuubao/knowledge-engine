"""Milvus 的唯一索引适配层，所有读取和删除都绑定 KB 与文档版本。"""
import json
import threading

from .errors import EngineError


FIELDS = ["chunk_id", "knowledge_base_id", "document_id", "index_version", "title", "content", "source", "page"]


def literal(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def search_hit_id(hit) -> str:
    # 自定义主键可能以字段名返回；同时兼容字典与 SDK 的索引访问对象。
    for name in ("chunk_id", "id"):
        try:
            value = hit[name]
        except (KeyError, TypeError):
            continue
        if value is not None:
            return str(value)
    try:
        value = hit["entity"]["chunk_id"]
    except (KeyError, TypeError):
        value = None
    if value is None:
        raise EngineError("INVALID_SEARCH_RESULT", "向量检索结果缺少片段主键", 502, True)
    return str(value)


def document_filter(kb: str, document_id: str, version: str) -> str:
    return (f"knowledge_base_id == {literal(kb)} and document_id == {literal(document_id)} "
            f"and index_version == {literal(version)}")


def scope_filter(kb: str, allowed: list) -> str:
    if not allowed:
        # 即使调用者遗漏上层短路也无法检索整个集合。
        raise EngineError("EMPTY_SCOPE", "没有获准检索的文档", 400)
    clauses = [f"(document_id == {literal(doc.document_id)} and index_version == {literal(doc.index_version)})"
               for doc in allowed]
    return f"knowledge_base_id == {literal(kb)} and (" + " or ".join(clauses) + ")"


class MilvusStore:
    def __init__(self, settings):
        self.settings, self._client, self._ready = settings, None, False
        self._lock = threading.RLock()

    def _connect(self):
        with self._lock:
            if self._client is None:
                if not self.settings.milvus_uri:
                    raise EngineError("MILVUS_NOT_CONFIGURED", "请配置 MILVUS_URI")
                try:
                    from pymilvus import MilvusClient
                    self._client = MilvusClient(uri=self.settings.milvus_uri,
                        token=self.settings.milvus_token, timeout=self.settings.milvus_timeout_seconds)
                except Exception as exc:
                    raise EngineError("MILVUS_UNAVAILABLE", "无法连接 Milvus 服务", 503, True) from exc
            return self._client

    def ping(self):
        self._connect().list_collections(timeout=3)
        return True

    def _ensure_collection(self):
        with self._lock:
            if self._ready:
                return
            from pymilvus import DataType
            client, config = self._connect(), self.settings
            if not client.has_collection(config.collection):
                schema = client.create_schema(auto_id=False, enable_dynamic_field=False)
                schema.add_field("chunk_id", DataType.VARCHAR, is_primary=True, max_length=64)
                for name in ("knowledge_base_id", "document_id", "index_version"):
                    schema.add_field(name, DataType.VARCHAR, max_length=128)
                schema.add_field("title", DataType.VARCHAR, max_length=1024)
                schema.add_field("content", DataType.VARCHAR, max_length=65535)
                schema.add_field("source", DataType.VARCHAR, max_length=4096)
                # -1 存储未知页码，对外一律映射 null，不能伪造 Markdown 页码。
                schema.add_field("page", DataType.INT64)
                schema.add_field("dense", DataType.FLOAT_VECTOR, dim=config.dense_dim)
                schema.add_field("sparse", DataType.SPARSE_FLOAT_VECTOR)
                indexes = client.prepare_index_params()
                indexes.add_index(field_name="dense", index_type="AUTOINDEX", metric_type="COSINE")
                indexes.add_index(field_name="sparse", index_type="SPARSE_INVERTED_INDEX", metric_type="IP")
                client.create_collection(collection_name=config.collection, schema=schema,
                                         index_params=indexes, consistency_level="Strong")
            description = client.describe_collection(config.collection)
            fields = {field["name"]: field for field in description["fields"]}
            names = set(fields)
            if not set(FIELDS + ["dense", "sparse"]).issubset(names):
                raise EngineError("MILVUS_SCHEMA_MISMATCH", "集合 schema 与 v2 引擎不兼容，请使用新的 RAG_COLLECTION")
            if int(fields["dense"].get("params", {}).get("dim", 0)) != config.dense_dim:
                raise EngineError("MILVUS_SCHEMA_MISMATCH", "集合稠密向量维度不匹配，请使用新的 RAG_COLLECTION")
            client.load_collection(config.collection)
            self._ready = True

    def replace(self, metadata, chunks, vectors):
        self._ensure_collection()
        client, config = self._connect(), self.settings
        # Java 只有完整索引成功后才设 READY。先按稳定 ID upsert，再清理同版本过期片段。
        rows = [{**chunk, "page": chunk["page"] if chunk["page"] is not None else -1,
                 "dense": vector["dense"], "sparse": vector["sparse"]}
                for chunk, vector in zip(chunks, vectors, strict=True)]
        for offset in range(0, len(rows), 64):
            client.upsert(config.collection, rows[offset:offset + 64], timeout=config.milvus_timeout_seconds)
        ids = ",".join(literal(row["chunk_id"]) for row in rows)
        where = document_filter(metadata.knowledge_base_id, metadata.document_id, metadata.index_version)
        client.delete(config.collection, filter=where + f" and chunk_id not in [{ids}]",
                      timeout=config.milvus_timeout_seconds)

    def delete(self, kb, doc, version):
        client = self._connect()
        if client.has_collection(self.settings.collection):
            client.delete(self.settings.collection, filter=document_filter(kb, doc, version),
                          timeout=self.settings.milvus_timeout_seconds)

    def search(self, vector, kb, allowed, options, prefix="original"):
        if not allowed:
            return []
        self._ensure_collection()
        from pymilvus import AnnSearchRequest, WeightedRanker
        client, config, where = self._connect(), self.settings, scope_filter(kb, allowed)
        channels = {}
        modes = ["dense", "sparse"] if options.mode == "hybrid" else [options.mode]
        parameters = {"dense": {"metric_type": "COSINE", "params": {}},
                      "sparse": {"metric_type": "IP", "params": {}}}
        requests = []
        for mode in modes:
            # 单通道结果提供真实名次；最终混合排名仍由 Milvus WeightedRanker 执行。
            search_args = dict(collection_name=config.collection, data=[vector[mode]], anns_field=mode,
                search_params=parameters[mode], limit=options.candidate_k, filter=where,
                output_fields=FIELDS, consistency_level="Strong", timeout=config.milvus_timeout_seconds)
            channels[mode] = client.search(**search_args)[0]
            requests.append(AnnSearchRequest(data=[vector[mode]], anns_field=mode, param=parameters[mode],
                                            limit=options.candidate_k, expr=where))
        if len(modes) == 2:
            hits = client.hybrid_search(collection_name=config.collection, reqs=requests,
                ranker=WeightedRanker(config.dense_weight, config.sparse_weight), limit=options.candidate_k,
                output_fields=FIELDS, consistency_level="Strong", timeout=config.milvus_timeout_seconds)[0]
        else:
            hits = channels[modes[0]]
        ranks = {mode: {search_hit_id(hit): rank for rank, hit in enumerate(results, 1)}
                 for mode, results in channels.items()}
        result = []
        for rank, hit in enumerate(hits, 1):
            entity = dict(hit["entity"])
            entity["chunk_id"] = search_hit_id(hit)
            entity["page"] = entity["page"] if entity.get("page", -1) >= 0 else None
            entity["fusion_score"] = float(hit["distance"])
            entity["channel_ranks"] = {f"{prefix}_{mode}": values[entity["chunk_id"]]
                                       for mode, values in ranks.items() if entity["chunk_id"] in values}
            entity["channel_ranks"][f"{prefix}_{options.mode}"] = rank
            result.append(entity)
        return result

    def close(self):
        if self._client is not None:
            self._client.close()
