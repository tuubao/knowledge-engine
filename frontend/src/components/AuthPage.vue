<script setup>
import { reactive, ref } from 'vue'
import { api } from '../api'
const emit = defineEmits(['authenticated'])
const mode = ref('login')
const busy = ref(false)
const error = ref('')
const formRef = ref()
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }, { validator: (_, value, done) => /^[\p{L}\p{N}_-]{3,50}$/u.test(value.trim()) ? done() : done(new Error('使用 3–50 位字母、数字、下划线或连字符，支持中文')), trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { validator: (_, value, done) => value.length >= 8 && new TextEncoder().encode(value).length <= 72 ? done() : done(new Error('密码至少 8 个字符，UTF-8 编码不超过 72 字节')), trigger: 'blur' }],
}
async function submit() {
  if (busy.value || !(await formRef.value.validate().catch(() => false))) return
  busy.value = true
  error.value = ''
  try {
    const result = await api(`/auth/${mode.value}`, { method: 'POST', body: { username: form.username.trim(), password: form.password } })
    form.password = ''
    emit('authenticated', result)
  } catch (err) { error.value = err.message }
  finally { busy.value = false }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-intro">
      <div class="brand-mark" aria-hidden="true">知</div>
      <h1>让每一次回答<br>都有据可查。</h1>
      <p>把文档整理为知识，带着出处理解答案，沿着检索轨迹核验结果。</p>
      <div class="auth-flow"><span>整理知识</span><span>提出问题</span><span>核验证据</span></div>
      <div class="auth-product">知库 · 知识工作台</div>
    </section>
    <section class="auth-form-wrap">
      <div class="auth-form">
        <h2>{{ mode === 'login' ? '进入工作台' : '创建账户' }}</h2>
        <p class="hint">{{ mode === 'login' ? '登录后管理你的知识库和对话。' : '知识库与会话按账户独立保存。' }}</p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert" />
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名" prop="username"><el-input v-model="form.username" maxlength="50" autocomplete="username" :disabled="busy" placeholder="请输入用户名" /></el-form-item>
          <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" show-password maxlength="72" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :disabled="busy" placeholder="至少 8 个字符，最多 72 字节" /></el-form-item>
          <el-button type="primary" native-type="submit" :loading="busy" class="auth-submit">{{ mode === 'login' ? '登录' : '注册并登录' }}</el-button>
        </el-form>
        <button type="button" class="text-button auth-switch" :disabled="busy" @click="mode = mode === 'login' ? 'register' : 'login'; error = ''; formRef?.clearValidate()">{{ mode === 'login' ? '还没有账户？创建账户' : '已有账户？返回登录' }}</button>
      </div>
    </section>
  </main>
</template>
