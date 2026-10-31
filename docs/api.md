# API 使用说明

完整字段约束见 [v1 契约](../contracts/README.md)。Java 前缀 `/api/v1`，Python 前缀 `/internal/v1`；JSON 使用 snake_case，所有 ID 都是字符串，时间使用 UTC ISO 8601，耗时单位毫秒。

## 认证和知识库

```http
POST /api/v1/auth/register
Content-Type: application/json

{"username":"演示工程师","password":"DemoPass123"}
```

响应 `{token,user:{id,username,created_at}}`。`/auth/login` 使用同样请求。后续请求带 `Authorization: Bearer <token>`；过期返回 401。密码使用 BCrypt；数据库保存登录 token 的摘要，不提供明文查询接口。

`POST /knowledge-bases` 使用 `{name,description}`；`PATCH /knowledge-bases/{id}` 使用相同字段。列表直接返回数组。其他用户资源统一返回 404。`GET /auth/me` 不返回密码字段。

## 文件与任务

```http
POST /api/v1/knowledge-bases/{id}/documents
Authorization: Bearer <token>
Content-Type: multipart/form-data; boundary=...

file=<Markdown/TXT/PDF 文件字节>
```

返回 `{document_id,job_id,status,duplicate}`。同 KB 同内容复用摘要记录；失败记录可重试。通过 `GET /jobs/{id}` 查询，任务状态为 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED；文档另有 READY、DELETING 等生命周期状态，不能混用二者。

文档列表包含 `id,filename,status,error_message,index_version,chunk_count,created_at,job_id`。`POST /documents/{id}/retry` 重跑；`DELETE /documents/{id}` 将文档排除检索后清理数据。删除失败使用 DELETE_FAILED，支持再次 DELETE。上传最大 20MB，首版 KB 最多 1,000 个有效文档，避免内部授权列表超限。

## 会话与查询

`POST /sessions`：`{knowledge_base_id,title?}`；`GET /sessions?knowledge_base_id=...` 返回列表；`GET /sessions/{id}` 返回 messages；DELETE 删除会话。

```http
POST /api/v1/sessions/{id}/messages
Authorization: Bearer <token>
Content-Type: application/json

{"query":"KE-100如何复位？","retrieval":{"mode":"hybrid","top_k":5,"candidate_k":20,"use_rerank":true,"use_hyde":false,"use_web":false}}
```

query 最多 4,000 字符。mode 可取 dense/sparse/hybrid；top_k 1–20，candidate_k 不小于 top_k、最多 100。Java 根据会话所属 KB 自动附加授权文档，前端不能自行扩大范围。

响应包含 `message_id,request_id,answer,citations,candidates,trace`。不需要生成答案时，调用 `POST /knowledge-bases/{id}/search`，请求同上，返回检索查询、证据、候选与 trace。

`citations` 为实际进入生成上下文的证据，按 `[1]` 起编号；候选保留完整排序快照。字段包括来源、KB/文档/版本、chunk_id、正文、标题、可空页码、各通道排名、融合分数、可空重排分数、是否入模。文档来源不是自动公开下载链接；界面直接展示已授权证据正文。

## 流式事件

`POST /sessions/{id}/messages/stream` 使用相同请求，返回 `text/event-stream`；前端使用 fetch，允许发送认证头和 AbortSignal。

```text
event: meta
data: {"request_id":"..."}

event: retrieval
data: {"request_id":"...","citations":[],"candidates":[],"trace":{}}

event: delta
data: {"request_id":"...","delta":"回答片段"}

event: done
data: {"request_id":"...","message_id":"...","answer":"...","citations":[],"candidates":[],"trace":{}}

```

失败发送 error 终态；正常发送 done 终态，二者恰有一个。注释心跳应忽略。Java 在助手消息落库后发 done；取消保存 CANCELLED，故障保存 FAILED 和已产生的部分文本。断线后前端查询历史，不自动重放问题。

## Python 内部协议

必须使用 `X-Service-Key`，推荐传 `X-Request-Id`。对外不暴露该密钥。

- `/documents/index`：multipart file 与 metadata JSON 字符串；metadata 是 `{knowledge_base_id,document_id,index_version,request_id?}`。
- DELETE `/documents/{id}?knowledge_base_id=...&index_version=...`：幂等删除。
- `/retrieve`、`/answer`、`/answer/stream`：请求包含 `knowledge_base_id,query,history,allowed_documents,retrieval`；history 每项 `{role,content}`，允许 user/assistant。
- `allowed_documents` **必填**，每项 `{document_id,index_version}`；最多 1,000 项，空数组表示没有授权文档。Python 再次检查返回候选的 KB/文档/版本。

运行后 `/openapi.json` 与 `/docs` 可读取内部接口；仓库同时保存契约快照。Java 使用 JDK HttpClient 传递文件字节，不依赖两进程共享绝对文件路径。

## 错误与健康

统一错误：`{code,message,request_id,retryable}`。400 参数无效，401未登录/内部认证失败，404资源不存在/无权访问，409状态冲突或超限，413上传过大，429执行器饱和，502上游模型错误，503依赖不可用，504超时。

流式响应已经开始后用 error 事件表示失败，不能再改变 HTTP 状态。日志使用 request_id 定位，不回显密钥。

Python `/health/live` 仅检查进程；`/health/ready` 区分模型配置、身份、加载/推理验证、向量连接和可选能力，未就绪返回503。Java `/api/v1/health` 在Python不可用时返回DEGRADED，同时保持业务管理页可访问。
