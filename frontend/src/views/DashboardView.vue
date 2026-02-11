<template>
  <div>
    <div class="page-header"><h1>仪表盘</h1></div>
    <div class="stats-row">
      <div class="stat-card">
        <div class="label">总账号数</div>
        <div class="value primary">{{ data.accounts?.total || 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="label">活跃账号</div>
        <div class="value success">{{ data.accounts?.active || 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="label">冷却中</div>
        <div class="value warning">{{ data.accounts?.cooldown || 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="label">总请求数</div>
        <div class="value">{{ data.requests?.total || 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="label">错误数</div>
        <div class="value danger">{{ data.requests?.errors || 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="label">日志记录</div>
        <div class="value">{{ data.requests?.logCount || 0 }}</div>
      </div>
    </div>
    <div class="content-card">
      <h3 style="margin-bottom: 12px">API 端点</h3>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="OpenAI">POST /v1/chat/completions</el-descriptions-item>
        <el-descriptions-item label="Anthropic">POST /v1/messages</el-descriptions-item>
        <el-descriptions-item label="模型列表">GET /v1/models</el-descriptions-item>
        <el-descriptions-item label="健康检查">GET /health</el-descriptions-item>
      </el-descriptions>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api/index.js'

const data = ref({})

onMounted(async () => {
  try {
    data.value = await api.getDashboard()
  } catch (e) {
    console.error('加载仪表盘失败', e)
  }
})
</script>
