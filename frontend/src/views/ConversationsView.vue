<template>
  <div>
    <div class="page-header"><h1>对话记录</h1></div>
    <div class="content-card">
      <el-table :data="conversations" stripe style="width: 100%" @expand-change="onExpand">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div style="padding: 0 20px 12px">
              <div v-if="row._loading" style="color:#909399;padding:12px">加载中...</div>
              <el-table v-else :data="row._messages || []" size="small" stripe>
                <el-table-column prop="timestamp" label="时间" width="170">
                  <template #default="{ row: msg }">{{ formatTime(msg.timestamp) }}</template>
                </el-table-column>
                <el-table-column label="输入 Tokens" width="100">
                  <template #default="{ row: msg }">{{ msg.inputTokens }}</template>
                </el-table-column>
                <el-table-column label="输出 Tokens" width="100">
                  <template #default="{ row: msg }">{{ msg.outputTokens }}</template>
                </el-table-column>
                <el-table-column label="Credits" width="100">
                  <template #default="{ row: msg }">{{ msg.credits?.toFixed(4) }}</template>
                </el-table-column>
                <el-table-column prop="durationMs" label="耗时" width="80">
                  <template #default="{ row: msg }">{{ msg.durationMs }}ms</template>
                </el-table-column>
                <el-table-column label="状态" width="70">
                  <template #default="{ row: msg }">
                    <el-tag :type="msg.success ? 'success' : 'danger'" size="small">{{ msg.success ? '成功' : '失败' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="详情" width="80">
                  <template #default="{ row: msg }">
                    <el-button type="primary" size="small" text @click.stop="goDetail(msg.traceId)">查看</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="200">
          <template #default="{ row }">
            <span>{{ formatTime(row.firstTime) }}</span>
            <span v-if="row.rounds > 1" style="color:#909399"> ~ {{ formatTime(row.lastTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="rounds" label="轮数" width="70" />
        <el-table-column prop="model" label="模型" width="180" />
        <el-table-column prop="accountName" label="账号" width="100" />
        <el-table-column label="输入 Tokens" width="100">
          <template #default="{ row }">{{ row.totalInput }}</template>
        </el-table-column>
        <el-table-column label="输出 Tokens" width="100">
          <template #default="{ row }">{{ row.totalOutput }}</template>
        </el-table-column>
        <el-table-column label="Credits" width="100">
          <template #default="{ row }">{{ row.totalCredits?.toFixed(4) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <el-tag :type="row.allSuccess ? 'success' : 'danger'" size="small">{{ row.allSuccess ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        style="margin-top: 16px; justify-content: flex-end"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="loadConversations"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api/index.js'

const router = useRouter()
const conversations = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 20

async function loadConversations() {
  try {
    const res = await api.getConversations({
      limit: pageSize,
      offset: (currentPage.value - 1) * pageSize
    })
    conversations.value = (res.data || []).map(c => ({ ...c, _messages: null, _loading: false }))
    total.value = res.total || 0
  } catch (e) { console.error(e) }
}

async function loadMessages(row) {
  if (row._messages) return
  row._loading = true
  try {
    row._messages = await api.getConversationMessages(row.conversationId)
  } catch (e) {
    console.error(e)
    row._messages = []
  } finally {
    row._loading = false
  }
}

function onExpand(row, expandedRows) {
  if (expandedRows.includes(row)) {
    loadMessages(row)
  }
}

function goDetail(traceId) {
  router.push(`/admin/conversations/${traceId}`)
}

function formatTime(t) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(loadConversations)
</script>
