<script setup>
import { computed } from 'vue'
const props = defineProps({ trace: { type: Object, default: () => ({}) }, requestId: String })
const stages = computed(() => Object.entries(props.trace?.durations_ms || {}).filter(([, v]) => Number.isFinite(Number(v))))
const maxDuration = computed(() => Math.max(1, ...stages.value.map(([, value]) => Number(value))))
const failure = computed(() => props.trace?.error)
const labels = { retrieve: '检索', retrieval: '检索', retrieval_total: '检索总计', embedding: '向量化', dense: '稠密召回', sparse: '稀疏召回', original_recall: '原问题召回', rewrite: '查询改写', domain: '领域识别', context: '组织上下文', web: '网络检索', fusion: '融合排序', rerank: '重排序', hyde: 'HyDE 扩展', generate: '生成', generation: '生成', total: '总计', parse: '解析', parse_and_chunk: '解析与切片', vector_write: '写入向量', index: '索引' }
</script>

<template>
  <div class="trace-view">
    <div v-if="requestId" class="request-id">请求标识 <code>{{ requestId }}</code></div>
    <el-alert v-if="failure" type="error" :closable="false" :title="failure.message || '本次请求未完成'" show-icon />
    <el-alert v-for="(reason, index) in trace?.degraded || []" :key="index" type="warning" :closable="false" :title="typeof reason === 'string' ? reason : JSON.stringify(reason)" show-icon />
    <div v-if="stages.length" class="timing-list" aria-label="阶段耗时">
      <div v-for="[name, value] in stages" :key="name" class="timing-row">
        <span>{{ labels[name] || name }}</span>
        <div class="timing-track"><div :style="{ width: `${Math.max(1, Number(value) / maxDuration * 100)}%` }" /></div>
        <span>{{ Number(value).toFixed(1) }} ms</span>
      </div>
    </div>
    <details v-if="trace?.config && Object.keys(trace.config).length" class="trace-config"><summary>实际执行配置<span v-if="trace.prompt_version"> · Prompt {{ trace.prompt_version }}</span></summary><pre>{{ JSON.stringify(trace.config, null, 2) }}</pre></details>
  </div>
</template>
