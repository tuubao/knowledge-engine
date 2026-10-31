<script setup>
import { nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, streamMessage } from '../api'
import RetrievalOptions from './RetrievalOptions.vue'
import TraceView from './TraceView.vue'
import EvidencePanel from './EvidencePanel.vue'
const props = defineProps({ selectedId: String })
const sessions = ref([])
const sessionId = ref('')
const messages = ref([])
const query = ref('')
const busy = ref(false)
const loading = ref(false)
const creating = ref(false)
const deleting = ref('')
const error = ref('')
const drawer = ref(false)
const evidence = ref(null)
const evidenceTab = ref('citations')
const conversation = ref()
const input = ref()
const sidebarOpen = ref(false)
const retrieval = ref({ mode: 'hybrid', top_k: 5, candidate_k: 20, use_rerank: true, use_hyde: false, use_web: false })
let controller
let version = 0
let listVersion = 0

async function scrollToBottom() { await nextTick(); if (conversation.value) conversation.value.scrollTop = conversation.value.scrollHeight }
async function loadSessions() {
  const kb = props.selectedId
  const current = version
  const list = ++listVersion
  if (!kb) { sessions.value = []; return }
  try {
    const result = await api(`/sessions?knowledge_base_id=${encodeURIComponent(kb)}`)
    if (current === version && list === listVersion) sessions.value = result
  } catch (err) { if (current === version && list === listVersion) error.value = err.message }
}
watch(() => props.selectedId, () => {
  controller?.abort()
  version++
  busy.value = false
  loading.value = false
  creating.value = false
  deleting.value = ''
  sessionId.value = ''
  messages.value = []
  sessions.value = []
  error.value = ''
  query.value = ''
  drawer.value = false
  evidence.value = null
  loadSessions()
}, { immediate: true })
onBeforeUnmount(() => { controller?.abort(); version++ })

async function selectSession(id) {
  if (busy.value || loading.value || creating.value) return
  const current = ++version
  loading.value = true
  error.value = ''
  try {
    const result = await api(`/sessions/${encodeURIComponent(id)}`)
    if (current !== version) return
    sessionId.value = id
    messages.value = result.messages || []
    sidebarOpen.value = false
    scrollToBottom()
  } catch (err) { if (current === version) error.value = err.message }
  finally { if (current === version) loading.value = false }
}
async function createSession(title = '新对话', signal) {
  if (!props.selectedId) return null
  const kb = props.selectedId
  const current = version
  const result = await api('/sessions', { method: 'POST', body: { knowledge_base_id: kb, title }, signal })
  if (current !== version || kb !== props.selectedId) throw new DOMException('知识库已切换', 'AbortError')
  sessions.value.unshift(result)
  sessionId.value = result.id
  messages.value = []
  return result.id
}
async function newSession() {
  if (busy.value || loading.value || creating.value || !props.selectedId) return
  creating.value = true
  error.value = ''
  const current = version
  try { await createSession(); await nextTick(); input.value?.focus(); sidebarOpen.value = false }
  catch (err) { if (current === version && err.name !== 'AbortError') error.value = err.message }
  finally { if (current === version) creating.value = false }
}
async function removeSession(session) {
  if (busy.value || loading.value || creating.value || deleting.value) return
  const current = version
  try { await ElMessageBox.confirm(`删除会话「${session.title}」及其消息？`, '删除会话', { confirmButtonText: '删除', cancelButtonText: '保留', type: 'warning' }) } catch { return }
  if (current !== version) return
  deleting.value = session.id
  try {
    await api(`/sessions/${encodeURIComponent(session.id)}`, { method: 'DELETE' })
    if (current !== version) return
    sessions.value = sessions.value.filter(item => item.id !== session.id)
    if (sessionId.value === session.id) { sessionId.value = ''; messages.value = [] }
  } catch (err) { if (current === version) ElMessage.error(err.message) }
  finally { if (current === version) deleting.value = '' }
}
async function send() {
  const text = query.value.trim()
  if (!text || busy.value || loading.value || creating.value || !props.selectedId) return
  busy.value = true
  error.value = ''
  const ownController = new AbortController()
  controller = ownController
  const current = version
  let assistant
  try {
    const id = sessionId.value || await createSession(text.slice(0, 32), ownController.signal)
    if (current !== version || ownController.signal.aborted) return
    messages.value.push({ id: `local-user-${Date.now()}`, role: 'user', content: text, status: 'COMPLETED' })
    assistant = reactive({ id: `local-assistant-${Date.now()}`, role: 'assistant', content: '', status: 'STREAMING', citations: [], candidates: [], trace: {}, request_id: '' })
    messages.value.push(assistant)
    query.value = ''
    scrollToBottom()
    await streamMessage(id, { query: text, retrieval: { ...retrieval.value } }, ownController.signal, (event, data) => {
      if (current !== version) return
      if (data.request_id) assistant.request_id = data.request_id
      if (event === 'retrieval') Object.assign(assistant, { citations: data.citations || [], candidates: data.candidates || [], trace: data.trace || {}, rewritten_query: data.rewritten_query })
      if (event === 'delta') { assistant.content += data.delta || ''; scrollToBottom() }
      if (event === 'done') {
        Object.assign(assistant, { id: data.message_id || assistant.id, content: data.answer ?? assistant.content, citations: data.citations || assistant.citations, candidates: data.candidates || assistant.candidates, trace: data.trace || assistant.trace, status: 'COMPLETED' })
        scrollToBottom()
      }
    })
  } catch (err) {
    if (assistant) assistant.status = err.name === 'AbortError' ? 'CANCELLED' : 'FAILED'
    if (current === version && err.name !== 'AbortError') error.value = `${err.message}${err.request_id ? `（请求 ${err.request_id}）` : ''}`
  } finally {
    // 旧知识库流的 finally 不能重置新知识库正在进行的请求。
    if (current === version) { busy.value = false; controller = null; loadSessions(); scrollToBottom() }
  }
}
function openEvidence(message, tab = 'citations') { evidence.value = message; evidenceTab.value = tab; drawer.value = true }
function stop() { controller?.abort() }
</script>

<template>
  <div class="chat-layout">
    <aside :class="['sessions-sidebar', { 'mobile-open': sidebarOpen }]">
      <div class="section-toolbar"><h2>会话</h2><el-button size="small" :disabled="busy || !selectedId" :loading="creating" @click="newSession">新建</el-button></div>
      <div v-if="sessions.length" class="session-list">
        <div v-for="session in sessions" :key="session.id" :class="['session-row', { active: sessionId === session.id }]"><button type="button" class="session-select" :disabled="busy" @click="selectSession(session.id)"><strong>{{ session.title }}</strong><span>{{ new Date(session.created_at).toLocaleDateString('zh-CN') }}</span></button><button class="session-delete" type="button" :disabled="busy || !!deleting" :aria-label="`删除会话 ${session.title}`" @click="removeSession(session)">×</button></div>
      </div>
      <p v-else class="small-empty">对话会自动保存在这里。选择知识库，开始第一次提问。</p>
    </aside>
    <section class="chat-main">
      <div class="chat-toolbar"><button type="button" class="text-button mobile-sessions" @click="sidebarOpen = !sidebarOpen">{{ sidebarOpen ? '收起会话' : '会话列表' }}</button><p>基于当前知识库回答，引用可随时核验。</p><el-tag v-if="busy" type="warning" effect="plain" role="status">正在生成</el-tag></div>
      <div ref="conversation" v-loading="loading" class="conversation" aria-label="对话记录" aria-busy="busy || loading">
        <div v-if="!messages.length && !loading" class="chat-empty"><div class="empty-symbol" aria-hidden="true">知</div><h2>从一个具体问题开始</h2><p>{{ selectedId ? '例如，询问文档中的操作步骤、限制条件或不同方案。回答会附上实际检索到的文档片段。' : '请先在顶部选择知识库，或前往知识库页创建并上传文档。' }}</p></div>
        <article v-for="message in messages" :key="message.id" :class="['message', message.role]">
          <div class="message-heading"><strong>{{ message.role === 'user' ? '你' : '知库' }}</strong><span v-if="message.status === 'STREAMING'" class="message-status" role="status">生成中…</span><span v-else-if="message.status === 'FAILED'" class="message-status failed">回答未完成</span><span v-else-if="message.status === 'CANCELLED'" class="message-status">已停止</span></div>
          <div class="message-content preserve-text">{{ message.content || (message.status === 'STREAMING' ? '正在检索相关内容…' : message.status === 'CANCELLED' ? '生成已停止。' : '没有完整的回答内容。') }}</div>
          <div v-if="message.role === 'assistant'" class="message-evidence">
            <button v-for="(citation, index) in message.citations || []" :key="citation.chunk_id || index" type="button" class="citation-chip" @click="openEvidence(message)"><span>{{ index + 1 }}</span>{{ citation.title || citation.source || '文档片段' }}</button>
            <button v-if="message.request_id || message.trace" type="button" class="text-button" @click="openEvidence(message, 'analysis')">检索详情</button>
          </div>
        </article>
      </div>
      <div class="composer">
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert" />
        <RetrievalOptions v-model="retrieval" :disabled="busy" />
        <el-input ref="input" v-model="query" type="textarea" :rows="3" resize="none" maxlength="4000" show-word-limit :disabled="!selectedId || busy || loading || creating" aria-label="输入知识库问题" placeholder="输入你的问题，Ctrl / Command + Enter 发送" @keydown.ctrl.enter.prevent="send" @keydown.meta.enter.prevent="send" />
        <div class="composer-footer"><span class="hint">请核验引用，模型回答可能存在遗漏。</span><el-button v-if="busy" type="danger" plain @click="stop">停止生成</el-button><el-button v-else type="primary" :disabled="!query.trim() || !selectedId || loading || creating" @click="send">发送问题</el-button></div>
      </div>
    </section>
    <el-drawer v-model="drawer" title="回答证据" size="min(780px, 96vw)">
      <el-tabs v-model="evidenceTab"><el-tab-pane label="引用片段" name="citations"><TraceView :trace="evidence?.trace || {}" :request-id="evidence?.request_id" /><div v-if="evidence?.citations?.length" class="citations-list"><article v-for="(citation, index) in evidence.citations" :key="citation.chunk_id || index" class="citation-document"><h3><span class="citation-number">{{ index + 1 }}</span>{{ citation.title || '文档片段' }}</h3><div class="metadata">{{ citation.source || citation.document_id }}<span v-if="citation.page != null"> · 第 {{ citation.page }} 页</span></div><p class="preserve-text">{{ citation.content }}</p><div class="metadata">片段 {{ citation.chunk_id }}</div></article></div><el-empty v-else description="本次回答没有引用片段。" :image-size="80" /></el-tab-pane><el-tab-pane label="排序与耗时" name="analysis"><EvidencePanel :result="evidence" /></el-tab-pane></el-tabs>
    </el-drawer>
  </div>
</template>
