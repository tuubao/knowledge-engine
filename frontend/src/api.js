const TOKEN_KEY = 'knowledge-engine-token'
const BASE = '/api/v1'

export class ApiError extends Error {
  constructor(payload = {}, status = 0) {
    payload = payload && typeof payload === 'object' ? payload : {}
    super(payload.message || '请求失败，请稍后重试')
    this.name = 'ApiError'
    this.code = payload.code || 'REQUEST_FAILED'
    this.request_id = payload.request_id
    this.retryable = payload.retryable || false
    this.status = status
  }
}

export const getToken = () => globalThis.localStorage?.getItem(TOKEN_KEY) || ''
export function setToken(token) {
  if (token) globalThis.localStorage?.setItem(TOKEN_KEY, token)
  else globalThis.localStorage?.removeItem(TOKEN_KEY)
}

async function checkedResponse(path, options = {}) {
  const headers = new Headers(options.headers)
  if (!headers.has('Accept')) headers.set('Accept', 'application/json')
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  // FormData 由浏览器生成 boundary；不能手动覆盖 multipart 的类型。
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  let response
  try {
    response = await fetch(`${BASE}${path}`, { ...options, headers })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new ApiError({ code: 'NETWORK_ERROR', message: '无法连接业务服务，请检查 Java 服务和网络。', retryable: true })
  }
  if (!response.ok) {
    let payload
    try { payload = await response.json() } catch { payload = { message: `服务返回 HTTP ${response.status}` } }
    if (response.status === 401 && !path.startsWith('/auth/login') && !path.startsWith('/auth/register')) {
      setToken('')
      globalThis.dispatchEvent?.(new Event('auth-expired'))
    }
    throw new ApiError(payload, response.status)
  }
  return response
}

export async function api(path, { body, ...options } = {}) {
  const response = await checkedResponse(path, {
    ...options,
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
  })
  if (response.status === 204) return null
  try { return await response.json() }
  catch (error) {
    if (error.name === 'AbortError') throw error
    throw new ApiError({ code: 'INVALID_RESPONSE', message: '服务返回了无法解析的响应，请检查业务服务日志。', request_id: response.headers.get('x-request-id') })
  }
}

// 支持跨网络块的 UTF-8、多行 data、CRLF 与注释心跳；仅终态标记成功。
export async function* parseSse(stream, signal) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  function parse(frame) {
    let event = 'message'
    const data = []
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (!data.length) return null
    try {
      const payload = JSON.parse(data.join('\n'))
      if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error('invalid payload')
      return { event, data: payload }
    }
    catch { throw new ApiError({ code: 'INVALID_STREAM', message: '服务返回了无法解析的流式事件。' }) }
  }
  // 即便服务迟迟不再发送 token，停止按钮也能释放 reader 并结束请求。
  const abort = () => { void reader.cancel(signal.reason).catch(() => {}) }
  signal?.addEventListener('abort', abort, { once: true })
  try {
    while (true) {
      signal?.throwIfAborted()
      const { value, done } = await reader.read()
      signal?.throwIfAborted()
      buffer += decoder.decode(value, { stream: !done })
      let separator
      while ((separator = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, separator.index)
        buffer = buffer.slice(separator.index + separator[0].length)
        const parsed = parse(frame)
        if (parsed) yield parsed
      }
      if (done) {
        if (buffer.trim()) {
          const parsed = parse(buffer)
          if (parsed) yield parsed
        }
        break
      }
    }
  } finally {
    signal?.removeEventListener('abort', abort)
    // 提前收到终态或使用者停止消费时，关闭未读连接，取消上游生成。
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export async function streamMessage(sessionId, body, signal, onEvent) {
  const response = await checkedResponse(`/sessions/${encodeURIComponent(sessionId)}/messages/stream`, {
    method: 'POST', body: JSON.stringify(body), signal, headers: { Accept: 'text/event-stream' },
  })
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
    await response.body?.cancel().catch(() => {})
    throw new ApiError({ code: 'INVALID_STREAM', message: '服务没有返回预期的事件流。' })
  }
  for await (const { event, data } of parseSse(response.body, signal)) {
    if (event === 'delta' && typeof data.delta !== 'string') throw new ApiError({ code: 'INVALID_STREAM', message: '生成片段格式不正确。', request_id: data.request_id })
    if (event === 'done' && (typeof data.answer !== 'string' || !Array.isArray(data.citations) || !data.trace || typeof data.trace !== 'object')) {
      throw new ApiError({ code: 'INVALID_STREAM', message: '回答完成事件缺少答案或引用信息。', request_id: data.request_id })
    }
    onEvent(event, data)
    if (event === 'error') throw new ApiError(data)
    if (event === 'done') return data
  }
  throw new ApiError({ code: 'STREAM_INTERRUPTED', message: '连接已断开，回答尚未完成。请查看会话后再决定是否重新提问。', retryable: true })
}
