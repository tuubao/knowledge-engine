<script setup>
defineProps({ modelValue: { type: Object, required: true }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
function update(current, key, value) { emit('update:modelValue', { ...current, [key]: value }) }
</script>

<template>
  <details class="retrieval-options">
    <summary>检索设置</summary>
    <div class="options-grid">
      <label>召回方式
        <el-select :model-value="modelValue.mode" :disabled="disabled" aria-label="召回方式" @update:model-value="update(modelValue, 'mode', $event)">
          <el-option label="混合检索" value="hybrid" />
          <el-option label="稠密向量" value="dense" />
        </el-select>
      </label>
      <label>最终证据数
        <el-input-number :model-value="modelValue.top_k" :min="1" :max="20" :disabled="disabled" aria-label="最终证据数" @update:model-value="update(modelValue, 'top_k', Math.min($event || 1, modelValue.candidate_k))" />
      </label>
      <label>候选数
        <el-input-number :model-value="modelValue.candidate_k" :min="modelValue.top_k" :max="100" :disabled="disabled" aria-label="候选数" @update:model-value="update(modelValue, 'candidate_k', $event || modelValue.top_k)" />
      </label>
      <label class="switch-label"><el-switch :model-value="modelValue.use_rerank" :disabled="disabled" aria-label="使用重排序" @update:model-value="update(modelValue, 'use_rerank', $event)" />重排序</label>
      <label class="switch-label"><el-switch :model-value="modelValue.use_hyde" :disabled="disabled" aria-label="使用 HyDE" @update:model-value="update(modelValue, 'use_hyde', $event)" />HyDE 扩展</label>
    </div>
    <p class="hint">重排序分数用于比较相关性，不代表概率。HyDE 需要额外调用模型，可能增加耗时。</p>
  </details>
</template>
