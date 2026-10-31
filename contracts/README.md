# v1 接口契约

所有 JSON 字段使用 snake_case，ID 为字符串，时间使用 UTC ISO 8601；耗时单位毫秒。
机器可读快照：`query-request.schema.json`、`retrieval-response.schema.json`、`answer-response.schema.json`、`internal-openapi.json`。内部接口启动后的 `/openapi.json` 为当前实现的实时描述。
Java 对外 `/api/v1`，Python 内部 `/internal/v1`。错误结构：
`{"code":"...","message":"...","request_id":"...","retryable":false}`。

## Python 内部

请求头：`X-Service-Key`（必填），`X-Request-Id`（可选，不传则生成）。

- `GET /health/live`、`GET /health/ready`：无需认证。ready 返回能力与依赖状态。
- `POST /internal/v1/documents/index`：multipart `file` + `metadata` JSON 字符串。
  metadata：`{knowledge_base_id,document_id,index_version,request_id}`。
  返回 `{document_id,index_version,chunk_count,trace}`。
- `DELETE /internal/v1/documents/{id}?knowledge_base_id=...&index_version=...`：幂等删除。
- `POST /internal/v1/retrieve`、`POST /internal/v1/answer`、`POST /internal/v1/answer/stream`：共用请求：

```json
{
  "knowledge_base_id":"kb_id",
  "query":"如何复位设备？",
  "history":[{"role":"user","content":"介绍设备A"}],
  "allowed_documents":[{"document_id":"doc_id","index_version":"v1"}],
  "retrieval":{"mode":"hybrid","top_k":5,"candidate_k":20,"use_rerank":true,"use_hyde":false,"use_web":false}
}
```

`allowed_documents` 必填；空数组表示无授权文档，绝不解释为查全部。
retrieve 返回 `{request_id,citations,candidates,trace,rewritten_query}`；answer 再包含 `answer`。
`citations` 为最终进入生成上下文的证据；`candidates` 为排序分析所需的召回候选。
证据字段：`chunk_id,document_id,knowledge_base_id,index_version,title,content,source,page,channel_ranks,fusion_score,rerank_score,selected`。
`channel_ranks` 为通道名到排名的对象；`page` 和 `rerank_score` 可空。
trace：`{durations_ms:{},degraded:[],config:{},prompt_version:"v1"}`。

SSE 使用 `meta`、`retrieval`、`delta`、`done`、`error`；每个 payload 含 request_id。
retrieval payload 与 retrieve 返回相同；delta 为 `{request_id,delta}`；
done 为 `{request_id,answer,citations,candidates,trace}`；error 为统一错误结构。
每个流恰好一个 done/error 终态，断连后取消上游。

## Java 对外

除注册、登录及健康外均使用 `Authorization: Bearer <token>`。列表直接返回数组，不包一层 data。

- `POST /auth/register`、`POST /auth/login`：`{username,password}` → `{token,user:{id,username,created_at}}`。
- `GET /auth/me` → `{id,username,created_at}`。
- `GET/POST /knowledge-bases`、`GET/PATCH/DELETE /knowledge-bases/{id}`。
  创建/修改 `{name,description}`；KB `{id,name,description,created_at,document_count}`。
- `GET/POST /knowledge-bases/{id}/documents`：上传 multipart `file`；
  上传返回 `{document_id,job_id,status,duplicate}`；文档列表元素
  `{id,knowledge_base_id,filename,status,error_message,index_version,chunk_count,created_at,job_id}`。
- `GET /jobs/{id}` → `{id,document_id,status,error_message,created_at,updated_at}`。
- `POST /documents/{id}/retry` → 上传同结构；`DELETE /documents/{id}` → `{status}`。
- `GET /sessions?knowledge_base_id=...`、`POST /sessions`（`{knowledge_base_id,title}`）。
  session `{id,knowledge_base_id,title,created_at}`。
- `GET /sessions/{id}` → session 字段 + `messages`；`DELETE /sessions/{id}`。
  message `{id,role,content,status,citations,trace,request_id,created_at}`。
- `POST /sessions/{id}/messages`、`POST /sessions/{id}/messages/stream`：
  `{query,retrieval}` → Python answer 同结构（Java 增加 `message_id`）。
  SSE done 必须在消息成功落库后发送；支持 fetch SSE 的认证头。
- `POST /knowledge-bases/{id}/search`：`{query,retrieval}` → retrieve 同结构。
- `GET /health` → Java 和引擎可用状态；无需认证，不返回密钥。

文档状态 `PENDING/INDEXING/READY/FAILED/DELETING/DELETED/DELETE_FAILED`，只有 READY 被加入 allowed_documents。
会话错误消息保存为 FAILED，取消保存为 CANCELLED，不自动重放流式请求。
