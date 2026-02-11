<template>
  <div class="login-container">
    <div class="login-card">
      <h2>Kiro Gateway</h2>
      <p class="subtitle">管理面板登录</p>
      <el-form @submit.prevent="handleLogin">
        <el-form-item>
          <el-input
            v-model="password"
            type="password"
            placeholder="请输入管理密码"
            size="large"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" @click="handleLogin" :loading="loading">
          登录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api/index.js'

const router = useRouter()
const password = ref('')
const loading = ref(false)

async function handleLogin() {
  if (!password.value) return
  loading.value = true
  try {
    const res = await api.login(password.value)
    if (res.success) {
      localStorage.setItem('admin_token', res.token)
      router.push('/admin/dashboard')
    } else {
      ElMessage.error(res.message || '登录失败')
    }
  } catch (e) {
    ElMessage.error('登录请求失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: linear-gradient(135deg, #1d1e2c 0%, #2a2b3d 100%);
}
.login-card {
  background: white;
  padding: 40px;
  border-radius: 12px;
  width: 380px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}
.login-card h2 {
  text-align: center;
  color: #409eff;
  margin-bottom: 4px;
}
.subtitle {
  text-align: center;
  color: #909399;
  margin-bottom: 24px;
  font-size: 14px;
}
</style>
