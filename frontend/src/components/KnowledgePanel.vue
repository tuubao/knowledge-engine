<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
const props = defineProps({ knowledgeBases: { type: Array, required: true }, selectedId: String })
const emit = defineEmits(['refresh', 'select', 'chat'])
const current = computed(() => props.knowledgeBases.find(item => item.id === props.selectedId))
const documents = ref([])
const loading = ref(false)
const uploading = ref(false)
const error = ref('')
const input = ref()
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)
const name = ref('')
const description = ref('')
const actions = ref(new Set())
let poll
let loadVersion = 0
let active = true
const statuses = { PENDING: ['等待处理', 'info'], INDEXING: ['正在索引', 'warning'], READY: ['可检索', 'success'], FAILED: ['导入失败', 'danger'], DELETING: ['正在删除', 'warning'], DELETED: ['已删除', 'info'], DELETE_FAILED: ['删除失败', 'danger'] }
const activeCount = computed(() => documents.value.filter(item => ['PENDING', 'INDEXING', 'DELETING'].includes(item.status)).length)

function schedulePoll() {
  clearTimeout(poll)
  if (active && activeCount.value) poll = setTimeout(() => load(false), 2000)
}
async function load(showLoading = true) {
  if (!active) return
  const id = props.selectedId
  const version = ++loadVersion
  if (!id) { documents.value = []; loading.value = false; return }
  if (showLoading) loading.value = true
  try {
    const rows = await api(`/knowledge-bases/${encodeURIComponent(id)}/documents`)
    if (version !== loadVersion || id !== props.selectedId) return
    documents.value = rows
    error.value = ''
    schedulePoll()
  } catch (err) { if (version === loadVersion) { error.value = err.message; clearTimeout(poll) } }
  finally { if (version === loadVersion) loading.value = false }
}
watch(() => props.selectedId, () => { clearTimeout(poll); documents.value = []; error.value = ''; load() }, { immediate: true })
onBeforeUnmount(() => { active = false; clearTimeout(poll); loadVersion++ })

function openEditor(kb = null) {
  editing.value = kb
  name.value = kb?.name || ''
  description.value = kb?.description || ''
  dialog.value = true
}
async function save() {
  if (!name.value.trim() || saving.value) return
  saving.value = true
  try {
    const result = await api(editing.value ? `/knowledge-bases/${encodeURIComponent(editing.value.id)}` : '/knowledge-bases', { method: editing.value ? 'PATCH' : 'POST', body: { name: name.value.trim(), description: description.value.trim() } })
    dialog.value = false
    emit('refresh', result.id)
    ElMessage.success(editing.value ? '知识库已更新' : '知识库已创建')
  } catch (err) { ElMessage.error(err.message) }
  finally { saving.value = false }
}
async function removeKb(kb) {
  try { await ElMessageBox.confirm(`删除「${kb.name}」及其文档和会话？此操作无法撤销。`, '删除知识库', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '保留' }) }
  catch { return }
  try { await api(`/knowledge-bases/${encodeURIComponent(kb.id)}`, { method: 'DELETE' }); emit('refresh'); ElMessage.success('知识库已删除') }
  catch (err) { ElMessage.error(err.message) }
}
async function upload(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || uploading.value) return
  if (!file.size) { ElMessage.error('不能上传空文件，请选择包含正文的文档'); return }
  if (file.name.length > 255) { ElMessage.error('文件名不能超过 255 个字符'); return }
  if (!/\.(md|markdown|txt|pdf)$/i.test(file.name)) { ElMessage.error('请选择 Markdown、TXT 或 PDF 文件'); return }
  if (file.size > 20 * 1024 * 1024) { ElMessage.error('文件不能超过 20 MB'); return }
  uploading.value = true
  const data = new FormData()
  data.append('file', file)
  const id = props.selectedId
  try {
    const result = await api(`/knowledge-bases/${encodeURIComponent(id)}/documents`, { method: 'POST', body: data })
    ElMessage.success(result.duplicate ? '相同文件已存在，已复用原文档' : '上传成功，正在创建索引')
    if (props.selectedId === id) await load(false)
    // 上传期间可以切换知识库；旧请求不能把使用者强制切回原知识库。
    emit('refresh', props.selectedId === id ? id : undefined)
  } catch (err) { ElMessage.error(err.message) }
  finally { uploading.value = false }
}
async function documentAction(doc, action) {
  if (actions.value.has(doc.id)) return
  const id = props.selectedId
  if (action === 'delete') {
    try { await ElMessageBox.confirm(`删除「${doc.filename}」后，该文档将退出可检索范围。`, '删除文档', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '保留' }) } catch { return }
  }
  actions.value.add(doc.id)
  try {
    await api(`/documents/${encodeURIComponent(doc.id)}${action === 'retry' ? '/retry' : ''}`, { method: action === 'retry' ? 'POST' : 'DELETE' })
    if (id === props.selectedId) await load(false)
    emit('refresh')
    ElMessage.success(action === 'retry' ? '已重新提交索引任务' : '已提交删除')
  } catch (err) { ElMessage.error(err.message) }
  finally { actions.value.delete(doc.id) }
}
</script>

<template>
  <div class="knowledge-layout">
    <aside class="kb-list">
      <div class="section-toolbar"><h2>我的知识库 <span class="count">{{ knowledgeBases.length }}</span></h2><el-button size="small" @click="openEditor()">新建</el-button></div>
      <div v-if="knowledgeBases.length" class="kb-items">
        <button v-for="kb in knowledgeBases" :key="kb.id" type="button" :class="['kb-item', { active: kb.id === selectedId }]" @click="emit('select', kb.id)"><strong>{{ kb.name }}</strong><span>{{ kb.description || '暂无描述' }}</span><small>{{ kb.document_count ?? 0 }} 个文档</small></button>
      </div>
      <div v-else class="small-empty">尚未创建知识库。点击「新建」开始整理文档。</div>
    </aside>
    <section class="document-workspace">
      <template v-if="current">
        <div class="workspace-heading"><div><h2>{{ current.name }}</h2><p>{{ current.description || '为这个知识库添加文档，再开始提问。' }}</p></div><div class="inline-actions"><el-button @click="openEditor(current)">编辑</el-button><el-button type="danger" plain @click="removeKb(current)">删除知识库</el-button></div></div>
        <div class="document-toolbar"><div><h3>知识文档</h3><span class="hint">Markdown、TXT、PDF · 单文件不超过 20 MB</span></div><div class="inline-actions"><el-button :loading="loading" @click="load()">刷新</el-button><el-button type="primary" :loading="uploading" @click="input.click()">上传文档</el-button><input ref="input" class="sr-only" type="file" accept=".md,.markdown,.txt,.pdf" aria-label="选择知识文档" @change="upload" /></div></div>
        <p class="hint">PDF 解析需要引擎启用对应能力。索引成功后，文档才会用于回答。</p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert" />
        <p v-if="activeCount" class="poll-status" role="status">{{ activeCount }} 个任务处理中，每 2 秒更新状态。</p>
        <div v-loading="loading" class="table-scroll">
          <el-table v-if="documents.length" :data="documents" row-key="id">
            <el-table-column label="文档" min-width="240"><template #default="{ row }"><strong class="table-title">{{ row.filename }}</strong><span class="metadata">{{ new Date(row.created_at).toLocaleString('zh-CN') }}</span><p v-if="row.error_message" class="document-error">{{ row.error_message }}</p></template></el-table-column>
            <el-table-column label="状态" width="112"><template #default="{ row }"><el-tag :type="statuses[row.status]?.[1] || 'info'" effect="plain">{{ statuses[row.status]?.[0] || row.status }}</el-tag></template></el-table-column>
            <el-table-column label="片段数" width="90"><template #default="{ row }">{{ row.chunk_count ?? '—' }}</template></el-table-column>
            <el-table-column label="操作" width="190"><template #default="{ row }"><el-button v-if="row.status === 'FAILED'" size="small" :loading="actions.has(row.id)" @click="documentAction(row, 'retry')">重试索引</el-button><el-button v-if="!['DELETED','DELETING'].includes(row.status)" size="small" type="danger" plain :loading="actions.has(row.id)" @click="documentAction(row, 'delete')">{{ row.status === 'DELETE_FAILED' ? '重试删除' : '删除' }}</el-button></template></el-table-column>
          </el-table>
          <el-empty v-else-if="!loading" description="上传第一份文档，建立可追溯的知识来源。" :image-size="90"><el-button type="primary" :loading="uploading" @click="input.click()">上传文档</el-button></el-empty>
        </div>
        <div v-if="documents.some(item => item.status === 'READY')" class="next-step"><span>已有文档可用于回答。</span><el-button @click="emit('chat')">开始对话</el-button></div>
      </template>
      <el-empty v-else description="新建或选择一个知识库，开始管理文档。" :image-size="100"><el-button type="primary" @click="openEditor()">新建知识库</el-button></el-empty>
    </section>
    <el-dialog v-model="dialog" :title="editing ? '编辑知识库' : '新建知识库'" width="min(480px, 92vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="save"><el-form-item label="名称" required><el-input v-model="name" maxlength="100" show-word-limit :disabled="saving" placeholder="例如：设备维护手册" autofocus /></el-form-item><el-form-item label="描述"><el-input v-model="description" type="textarea" :rows="3" maxlength="500" show-word-limit :disabled="saving" placeholder="这个知识库包含什么内容？" /></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" :disabled="!name.trim()" :loading="saving" @click="save">{{ editing ? '保存' : '创建知识库' }}</el-button></template>
    </el-dialog>
  </div>
</template>
