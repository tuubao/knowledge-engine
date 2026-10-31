# 知库工作台

Vue 3.5.44、Vite 7.3.7、JavaScript、Element Plus 2.14.7。Node.js 要求 `>=22.12.0`；依赖版本由 `package-lock.json` 固定。

```powershell
cd frontend
npm ci
npm test
npm run dev
```

默认访问 `http://127.0.0.1:5173`，`/api` 开发代理指向 `http://127.0.0.1:8080`。如需使用其他 Java 端口：

```powershell
$env:JAVA_API_PROXY_TARGET = 'http://127.0.0.1:8081'
npm run dev -- --port 5174
```

Windows 的全局 npm 缓存目录不可写时，可使用 `npm ci --cache .npm-cache`。生产构建执行 `npm run build`，将生成的 `dist` 部署到提供 `/api` 反向代理的 Web 服务；`vite preview` 只用于检查静态产物，不是生产代理。

工作台只调用 Java `/api/v1`，不包含内部服务密钥。界面使用真实知识库、文档和会话数据；首次登录展示空态，不自动创建演示记录。知识库页支持上传、状态轮询、导入重试和删除重试；对话页使用带认证头的 `fetch` SSE，停止、切换知识库和离开页面都会取消连接。检索页与引用抽屉展示实际候选、通道排名、融合/重排分数及阶段耗时，分数不作为概率展示。

`npm test` 使用 Node 内置测试工具检查 UTF-8 分块、心跳、多行事件、截断/错误终态、静默连接取消、完成后的连接释放与 multipart 上传约定。测试替身只用于协议验证，不作为真实模型运行结果。
