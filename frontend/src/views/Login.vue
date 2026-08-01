<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ username: 'admin', password: 'admin123' })

async function onSubmit() {
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch {
    // 错误已由 http 拦截器提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="title">IPD 敏捷数字化工具箱</div>
      <div class="subtitle">第一版 · 全流程能力验证</div>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" placeholder="密码" show-password @keyup.enter="onSubmit" />
        </el-form-item>
        <el-button type="primary" :loading="loading" class="submit" @click="onSubmit">登录</el-button>
      </el-form>
      <div class="hint">演示账号：admin / admin123（兼全部角色）</div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page { height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #1f2d3d 0%, #2c3e50 100%); }
.login-card { width: 380px; padding: 12px 20px 20px; }
.title { font-size: 20px; font-weight: 700; text-align: center; margin-bottom: 4px; }
.subtitle { font-size: 13px; color: #909399; text-align: center; margin-bottom: 24px; }
.submit { width: 100%; }
.hint { font-size: 12px; color: #c0c4cc; text-align: center; margin-top: 16px; }
</style>
