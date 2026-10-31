<script setup>
import { computed, ref } from 'vue'
import TraceView from './TraceView.vue'
const props = defineProps({ result: { type: Object, default: null }, compact: Boolean })
const expanded = ref([])
const sort = ref('final')
const score = value => value == null ? '—' : Number(value).toFixed(5)
const hasCandidateSnapshot = computed(() => Array.isArray(props.result?.candidates) || Array.isArray(props.result?.trace?.candidates))
const candidates = computed(() => {
  // Java 为会话保存候选快照；旧会话只能展示真正保存下来的引用。
  const values = [...(props.result?.candidates || props.result?.trace?.candidates || props.result?.citations || [])]
  const fusionOrder = [...values].sort((a, b) => (b.fusion_score ?? -Infinity) - (a.fusion_score ?? -Infinity))
  const rerankOrder = [...values].filter(item => item.rerank_score != null).sort((a, b) => b.rerank_score - a.rerank_score)
  const mapped = values.map((item, index) => ({ ...item, final_rank: index + 1, fusion_rank: fusionOrder.findIndex(value => value.chunk_id === item.chunk_id) + 1, rerank_rank: item.rerank_score == null ? null : rerankOrder.findIndex(value => value.chunk_id === item.chunk_id) + 1 }))
  if (sort.value === 'fusion') return mapped.sort((a, b) => a.fusion_rank - b.fusion_rank)
  if (sort.value === 'rerank') return mapped.sort((a, b) => (a.rerank_rank ?? Infinity) - (b.rerank_rank ?? Infinity))
  return mapped
})
const channels = computed(() => [...new Set(candidates.value.flatMap(item => Object.keys(item.channel_ranks || {})))])
</script>

<template>
  <div v-if="result" class="evidence-panel">
    <TraceView :trace="result.trace || {}" :request-id="result.request_id" />
    <div class="section-toolbar">
      <h3>{{ hasCandidateSnapshot ? '候选片段' : '已保存的引用' }} <span class="count">{{ candidates.length }}</span></h3>
      <el-select v-model="sort" class="sort-select" aria-label="候选排序">
        <el-option label="返回顺序" value="final" /><el-option label="融合分数排序" value="fusion" /><el-option label="重排分数排序" value="rerank" />
      </el-select>
    </div>
    <p v-if="result.rewritten_query" class="hint">检索查询：{{ result.rewritten_query }}</p>
    <p v-if="!hasCandidateSnapshot" class="hint">此历史记录未保存完整召回候选，仅展示已保存的入模引用。</p>
    <p class="hint">通道数字为召回排名；融合与重排分数只在当前查询内可比。标记「入模」的片段用于生成回答。</p>
    <div class="table-scroll">
      <el-table v-if="candidates.length" :data="candidates" row-key="chunk_id" :expand-row-keys="expanded" @expand-change="(row, rows) => expanded = rows.map(item => item.chunk_id)">
        <el-table-column type="expand">
          <template #default="{ row }"><div class="candidate-body"><div class="metadata">{{ row.source || row.title || '来源未提供' }}<span v-if="row.page != null"> · 第 {{ row.page }} 页</span></div><p class="preserve-text">{{ row.content }}</p><div class="metadata">片段 {{ row.chunk_id }}</div></div></template>
        </el-table-column>
        <el-table-column prop="final_rank" label="顺序" width="64" />
        <el-table-column label="来源" min-width="180"><template #default="{ row }"><div class="table-title">{{ row.title || row.source || row.document_id }}</div><el-tag v-if="row.selected" size="small" type="success" effect="plain">入模</el-tag></template></el-table-column>
        <el-table-column v-for="channel in channels" :key="channel" :label="`${channel} 排名`" min-width="108"><template #default="{ row }">{{ row.channel_ranks?.[channel] ?? '—' }}</template></el-table-column>
        <el-table-column label="融合分数" width="115"><template #default="{ row }">{{ score(row.fusion_score) }}<small class="rank-note">排名 {{ row.fusion_rank }}</small></template></el-table-column>
        <el-table-column label="重排分数" width="115"><template #default="{ row }">{{ score(row.rerank_score) }}<small v-if="row.rerank_rank" class="rank-note">排名 {{ row.rerank_rank }}</small></template></el-table-column>
      </el-table>
      <el-empty v-else description="未召回片段。请检查文档是否已就绪，或调整问题与检索设置。" :image-size="72" />
    </div>
  </div>
</template>
