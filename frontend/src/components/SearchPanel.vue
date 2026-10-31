<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api'
import RetrievalOptions from './RetrievalOptions.vue'
import EvidencePanel from './EvidencePanel.vue'
const props = defineProps({ selectedId: String })
const query = ref('')
const busy = ref(false)
const error = ref('')
const result = ref(null)
const retrieval = ref({ mode: 'hybrid', top_k: 5, candidate_k: 20, use_rerank: true, use_hyde: false, use_web: false })
let controller
let version = 0
watch(() => props.selectedId, () => { controller?.abort(); version++; busy.value = false; result.value = null; error.value = '' })
onBeforeUnmount(() => { controller?.abort(); version++ })
async function search() {
  if (!props.selectedId || !query.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  result.value = null
  const current = ++version
  const ownController = new AbortController()
  controller = ownController
  try {
    const response = await api(`/knowledge-bases/${encodeURIComponent(props.selectedId)}/search`, { method: 'POST', body: { query: query.value.trim(), retrieval: { ...retrieval.value } }, signal: ownController.signal })
    if (current === version) result.value = response
  }
  catch (err) { if (current === version && err.name !== 'AbortError') error.value = `${err.message}${err.request_id ? `（请求 ${err.request_id}）` : ''}` }
  finally { if (current === version) { busy.value = false; controller = null } }
}
</script>

<template>
  <section class="search-workspace">
    <div class="workspace-heading"><div><h2>看清答案从哪里来</h2><p>查看同一问题的召回通道、融合与重排顺序，理解每个片段如何进入生成上下文。</p></div></div>
    <el-form class="search-form" @submit.prevent="search"><label for="search-query">检索问题</label><div class="search-input-row"><el-input id="search-query" v-model="query" maxlength="4000" clearable :disabled="busy || !selectedId" placeholder="输入要在当前知识库中核验的问题" /><el-button type="primary" native-type="submit" :disabled="!selectedId || !query.trim()" :loading="busy">检索分析</el-button><el-button v-if="busy" @click="controller?.abort()">取消</el-button></div><RetrievalOptions v-model="retrieval" :disabled="busy" /></el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert" />
    <div v-if="busy" class="search-loading" role="status"><span class="loading-dot"></span>正在召回与排序文档片段…</div>
    <EvidencePanel v-else-if="result" :result="result" />
    <div v-else-if="!error" class="search-empty"><h3>一次查询，一条可追溯的检索链路</h3><p>输入问题后，这里会展示真实的候选片段和执行耗时。调整检索设置后重新查询，可比较不同策略的结果。</p><div class="retrieval-chain"><span>问题</span><span>通道召回</span><span>融合排序</span><span>重排序</span><span>入模证据</span></div></div>
  </section>
</template>
