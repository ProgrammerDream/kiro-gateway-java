<template>
  <div>
    <div class="page-header"><h1>对话记录</h1></div>
    <div class="content-card">
      <div style="margin-bottom: 16px; display: flex; gap: 12px">
        <el-select v-model="filters.apiType" placeholder="API类型" clearable style="width: 120px" @change="loadLogs">
          <el-option label="OpenAI" value="openai" />
          <el-option label="Claude" value="claude" />
        </el-select>
        <el-select v-model="filters.success" placeholder="状态" clearable style="width: 100px" @change="loadLogs">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
      </div>
      <el-table :data="logs" stripe @row-click="goDetail" style="cursor: pointer">
        <el-table-column prop="timestamp" label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
        </el-table-column>
        <el-table-column prop="apiType" label="API" width="80">
          <template #default="{ row }">
            <el-tag :type="row.apiType === 'openai' ? '' : 'warning'" size="small">{{ row.apiType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" width="200" />
        <el-table-column prop="accountName" label="账号" width="120" />
        <el-table-column label="Tokens" width="120">
          <template #default="{ row }">{{ row.inputTokens }} / {{ row.outputTokens }}</template>
        </el-table-column>
        <el-table-column prop="durationMs" label="耗时" width="80">
          <template #default="{ row }">{{ row.durationMs }}ms</template>
        </el-table-column>
        <el-table-column prop="success" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">{{ row.success ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="stream" label="流式" width="60">
          <template #default="{ row }">{{ row.stream ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="traceId" label="TraceID" width="160" />
      </el-table>
      <el-pagination
        style="margin-top: 16px; justify-content: flex-end"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="loadLogs"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api/index.js'

const router = useRouter()
const logs = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 20
const filters = ref({ apiType: '', success: '' })

async function loadLogs() {
  try {
    const res = await api.getRequestLogs({
      limit: pageSize,
      offset: (currentPage.value - 1) * pageSize
    })
    logs.value = res.data || []
    total.value = res.total || 0
  } catch (e) { console.error(e) }
}

function goDetail(row) {
  router.push(`/admin/conversations/${row.traceId}`)
}

function formatTime(t) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(loadLogs)
</script>
