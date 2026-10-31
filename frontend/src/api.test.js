import test from 'node:test'
import assert from 'node:assert/strict'
import { api, parseSse, streamMessage } from './api.js'

function chunks(...values) {
  const encoder = new TextEncoder()
  return new ReadableStream({ start(controller) { values.forEach(value => controller.enqueue(encoder.encode(value))); controller.close() } })
}

test('SSE preserves Chinese deltas across byte boundaries and CRLF heartbeat', async () => {
  const bytes = new TextEncoder().encode(': heartbeat\r\n\r\nevent: delta\r\ndata: {"delta":"中文"}\r\n\r\nevent: done\ndata: {"answer":"中文"}\n\n')
  const stream = new ReadableStream({ start(controller) { for (let i = 0; i < bytes.length; i += 7) controller.enqueue(bytes.slice(i, i + 7)); controller.close() } })
  const events = []
  for await (const event of parseSse(stream)) events.push(event)
  assert.deepEqual(events, [{ event: 'delta', data: { delta: '中文' } }, { event: 'done', data: { answer: '中文' } }])
})

test('SSE reads multiline data and trailing frame', async () => {
  const events = []
  for await (const event of parseSse(chunks('event: retrieval\ndata: {\ndata: "citations": []\ndata: }'))) events.push(event)
  assert.deepEqual(events, [{ event: 'retrieval', data: { citations: [] } }])
})

test('truncated stream fails instead of treating partial text as a completed answer', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(chunks('event: delta\ndata: {"delta":"partial"}\n\n'), { headers: { 'content-type': 'text/event-stream' } }))
  await assert.rejects(streamMessage('s1', { query: 'q' }, undefined, () => {}), error => error.code === 'STREAM_INTERRUPTED')
})

test('server error propagates request_id without automatic retry', async t => {
  let calls = 0
  t.mock.method(globalThis, 'fetch', async () => { calls++; return new Response(chunks('event: error\ndata: {"code":"MODEL_FAILED","message":"模型不可用","request_id":"r1"}\n\n'), { headers: { 'content-type': 'text/event-stream' } }) })
  await assert.rejects(streamMessage('s1', {}, undefined, () => {}), error => error.request_id === 'r1')
  assert.equal(calls, 1)
})

test('terminal event cancels the unread connection and preserves the saved message id', async t => {
  let cancelled = false
  const terminal = { request_id: 'r2', answer: '已保存', citations: [], trace: {}, message_id: 'm2' }
  const stream = new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode(`event: done\ndata: ${JSON.stringify(terminal)}\n\n`)) },
    cancel() { cancelled = true },
  })
  t.mock.method(globalThis, 'fetch', async () => new Response(stream, { headers: { 'content-type': 'text/event-stream' } }))
  const events = []
  const result = await streamMessage('s/2', {}, undefined, (event, data) => events.push([event, data]))
  assert.equal(result.message_id, 'm2')
  assert.equal(cancelled, true)
  assert.deepEqual(events, [['done', terminal]])
})

test('cancel releases a silent stream instead of waiting for another token', async t => {
  let cancelled = false
  const stream = new ReadableStream({ cancel() { cancelled = true } })
  t.mock.method(globalThis, 'fetch', async () => new Response(stream, { headers: { 'content-type': 'text/event-stream' } }))
  const controller = new AbortController()
  const pending = streamMessage('s1', {}, controller.signal, () => {})
  setTimeout(() => controller.abort(), 10)
  await assert.rejects(pending, error => error.name === 'AbortError')
  assert.equal(cancelled, true)
})

test('a done event without persisted answer fields cannot mark a turn successful', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(chunks('event: done\ndata: {"request_id":"bad"}\n\n'), { headers: { 'content-type': 'text/event-stream' } }))
  const events = []
  await assert.rejects(streamMessage('s1', {}, undefined, event => events.push(event)), error => error.code === 'INVALID_STREAM')
  assert.deepEqual(events, [])
})

test('a JSON response is not mistaken for a stream', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response('{}', { headers: { 'content-type': 'application/json' } }))
  await assert.rejects(streamMessage('s1', {}, undefined, () => {}), error => error.code === 'INVALID_STREAM')
})

test('malformed JSON events fail with a controlled protocol error', async () => {
  for (const body of ['data: {bad}\n\n', 'data: null\n\n', 'data: []\n\n']) {
    await assert.rejects(async () => { for await (const event of parseSse(chunks(body))) void event }, error => error.code === 'INVALID_STREAM')
  }
})

test('multipart upload lets the browser supply its boundary and handles 204 responses', async t => {
  let received
  t.mock.method(globalThis, 'fetch', async (url, options) => { received = { url, ...options }; return new Response(null, { status: 204 }) })
  const file = new FormData()
  file.append('file', new Blob(['# 文档']), 'manual.md')
  assert.equal(await api('/knowledge-bases/k1/documents', { method: 'POST', body: file }), null)
  assert.equal(received.headers.has('content-type'), false)
  assert.equal(received.body, file)
  assert.equal(received.url, '/api/v1/knowledge-bases/k1/documents')
})

test('HTTP errors preserve business error codes and trace identifiers', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ code: 'BUSY', message: '任务已满', request_id: 'r3', retryable: true }), { status: 429, headers: { 'content-type': 'application/json' } }))
  await assert.rejects(api('/knowledge-bases'), error => error.code === 'BUSY' && error.status === 429 && error.request_id === 'r3' && error.retryable)
})

test('invalid JSON and network failures use actionable error codes', async t => {
  const mock = t.mock.method(globalThis, 'fetch', async () => new Response('<html>proxy error</html>', { headers: { 'x-request-id': 'proxy-1' } }))
  await assert.rejects(api('/health'), error => error.code === 'INVALID_RESPONSE' && error.request_id === 'proxy-1')
  mock.mock.mockImplementation(async () => { throw new TypeError('offline') })
  await assert.rejects(api('/health'), error => error.code === 'NETWORK_ERROR' && error.retryable)
})
