<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, getToken, setToken } from './api'
import AuthPage from './components/AuthPage.vue'
import KnowledgePanel from './components/KnowledgePanel.vue'
import ChatPanel from './components/ChatPanel.vue'
import SearchPanel from './components/SearchPanel.vue'

const user = ref(null)
const restoring = ref(Boolean(getToken()))
const page = ref('knowledge')
const selectedId = ref('')
const knowledgeBases = ref([])
const loading = ref(false)
const listError = ref('')
const health = ref(null)
const healthOpen = ref(false)
const healthLoading = ref(false)
const pages = [{ id: 'knowledge', name: '知识库', description: '组织文档，准备可靠的知识来源。' }, { id: 'chat', name: '对话', description: '基于文档提问，跟随引用核验回答。' }, { id: 'search', name: '检索分析', description: '理解召回、排序与生成之间的每一步。' }]
const currentPage = computed(() => pages.find(item => item.id === page.value))
const healthy = computed(() => health.value?.status === 'UP')
let healthTimer
let listVersion = 0

async function refreshKnowledgeBases(preferredId) {
  if (!user.value) return
  const version = ++listVersion
  loading.value = true
  listError.value = ''
  try {
    const result = await api('/knowledge-bases')
    if (version !== listVersion) return
    knowledgeBases.value = result
    const desired = preferredId || selectedId.value
    selectedId.value = result.some(item => item.id === desired) ? desired : result[0]?.id || ''
  } catch (err) { if (version === listVersion) listError.value = err.message }
  finally { if (version === listVersion) loading.value = false }
}
async function checkHealth() {
  if (healthLoading.value) return
  healthLoading.value = true
  try { health.value = await api('/health') }
  catch (err) { health.value = { status: 'UNAVAILABLE', message: err.message } }
  finally { healthLoading.value = false }
}
function authenticated(result) {
  setToken(result.token)
  user.value = result.user
  page.value = 'knowledge'
  refreshKnowledgeBases()
}
function logout() {
  setToken('')
  listVersion++
  user.value = null
  knowledgeBases.value = []
  selectedId.value = ''
  listError.value = ''
}
function expired() { logout(); ElMessage.warning('登录已过期，请重新登录。') }
onMounted(async () => {
  window.addEventListener('auth-expired', expired)
  checkHealth()
  healthTimer = setInterval(checkHealth, 30000)
  if (getToken()) {
    try { user.value = await api('/auth/me'); await refreshKnowledgeBases() }
    catch (err) { if (err.status !== 401) ElMessage.error(err.message); logout() }
    finally { restoring.value = false }
  }
})
onBeforeUnmount(() => { clearInterval(healthTimer); window.removeEventListener('auth-expired', expired) })
</script>

<template>
  <div v-if="restoring" class="restoring" role="status">正在恢复工作台…</div>
  <AuthPage v-else-if="!user" @authenticated="authenticated" />
  <div v-else class="app-shell">
    <aside class="app-nav">
      <a href="#main" class="skip-link">跳转到内容</a>
      <div class="app-brand"><span class="brand-mark" aria-hidden="true">知</span><div><strong>知库</strong><span>知识工作台</span></div></div>
      <nav aria-label="主导航"><button v-for="item in pages" :key="item.id" type="button" :class="['nav-item', { active: page === item.id }]" :aria-current="page === item.id ? 'page' : undefined" @click="page = item.id"><svg v-if="item.id === 'knowledge'" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M4 4h6v16H4zM14 4h6v16h-6zM4 8h6M14 8h6M4 16h6M14 16h6" /></svg><svg v-else-if="item.id === 'chat'" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M4 4h16v13H9l-5 4V4zM8 8h8M8 12h6" /></svg><svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M4 20V4M4 20h17M8 16v-5M13 16V6M18 16v-8" /></svg>{{ item.name }}</button></nav>
      <div class="nav-bottom"><span>有知识，有出处。</span><button type="button" :class="['health-button', { good: healthy }]" @click="healthOpen = true"><span class="status-dot"></span>{{ !health ? '正在检查服务' : healthy ? '服务已就绪' : '查看服务状态' }}</button></div>
    </aside>
    <div class="app-content">
      <header class="app-header"><div><h1>{{ currentPage.name }}</h1><p>{{ currentPage.description }}</p></div><div class="user-actions"><span class="username">{{ user.username }}</span><el-button text @click="logout">退出</el-button></div></header>
      <div class="context-bar"><label for="kb-select">当前知识库</label><el-select id="kb-select" v-model="selectedId" :loading="loading" placeholder="请先创建知识库" filterable aria-label="当前知识库"><el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" /></el-select><button type="button" class="text-button service-link" @click="healthOpen = true"><span :class="['status-dot', { good: healthy }]"></span>{{ !health ? '检查服务中' : healthy ? '引擎已就绪' : '服务未完全就绪' }}</button></div>
      <main id="main" class="page-content" tabindex="-1">
        <div v-if="listError" class="global-error"><el-alert :title="listError" type="error" :closable="false" show-icon /><el-button @click="refreshKnowledgeBases()">重新加载知识库</el-button></div>
        <KnowledgePanel v-if="page === 'knowledge'" :knowledge-bases="knowledgeBases" :selected-id="selectedId" @select="selectedId = $event" @refresh="refreshKnowledgeBases" @chat="page = 'chat'" />
        <ChatPanel v-else-if="page === 'chat'" :selected-id="selectedId" />
        <SearchPanel v-else :selected-id="selectedId" />
      </main>
    </div>
  </div>
  <el-dialog v-model="healthOpen" title="服务状态" width="min(600px, 94vw)"><p class="hint">状态来自业务服务和引擎的实际健康检查，每 30 秒更新。上传、解析和生成能力取决于引擎配置。</p><el-alert :title="healthy ? '服务已就绪' : '服务未完全就绪，请检查下方依赖状态'" :type="healthy ? 'success' : 'warning'" :closable="false" show-icon /><pre class="health-json">{{ JSON.stringify(health, null, 2) }}</pre><template #footer><el-button :loading="healthLoading" @click="checkHealth">重新检查</el-button><el-button type="primary" @click="healthOpen = false">关闭</el-button></template></el-dialog>
</template>
