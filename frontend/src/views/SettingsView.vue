<template>
  <div>
    <div class="page-header"><h1>设置</h1></div>
    <div class="content-card" style="margin-bottom: 16px">
      <h3 style="margin-bottom: 16px">API 密钥</h3>
      <el-table :data="apiKeys" stripe>
        <el-table-column prop="key" label="Key" />
        <el-table-column prop="name" label="名称" width="200" />
        <el-table-column prop="createdAt" label="创建时间" width="200">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </div>
    <div class="content-card">
      <h3 style="margin-bottom: 16px">可用模型</h3>
      <el-table :data="models" stripe>
        <el-table-column prop="id" label="模型 ID" />
        <el-table-column prop="displayName" label="显示名称" width="200" />
        <el-table-column prop="maxTokens" label="最大 Tokens" width="120" />
        <el-table-column prop="ownedBy" label="提供者" width="120" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api/index.js'

const apiKeys = ref([])
const models = ref([])

function formatTime(t) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(async () => {
  try {
    apiKeys.value = await api.getApiKeys()
    models.value = await api.getModels()
  } catch (e) {
    console.error('加载设置失败', e)
  }
})
</script>
