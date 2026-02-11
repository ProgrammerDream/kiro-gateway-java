<template>
  <div>
    <div class="page-header" style="display:flex;justify-content:space-between;align-items:center">
      <h1>应用日志</h1>
      <div style="display:flex;gap:8px;align-items:center">
        <el-select v-model="levelFilter" placeholder="级别筛选" clearable style="width:120px" size="small">
          <el-option label="INFO" value="INFO" />
          <el-option label="WARN" value="WARN" />
          <el-option label="ERROR" value="ERROR" />
          <el-option label="DEBUG" value="DEBUG" />
        </el-select>
        <el-input v-model="searchText" placeholder="搜索关键字" clearable style="width:200px" size="small" />
        <el-button :type="autoScroll ? 'primary' : ''" size="small" @click="autoScroll = !autoScroll">
          {{ autoScroll ? '暂停' : '尾随' }}
        </el-button>
        <el-button size="small" @click="loadLogs">刷新</el-button>
      </div>
    </div>
    <div class="content-card log-container" ref="logContainer">
      <div v-for="(line, idx) in filteredLines" :key="idx" :class="['log-line', getLevel(line)]">
        {{ line }}
      </div>
      <div v-if="filteredLines.length === 0" style="color:#909399;text-align:center;padding:40px">
        暂无日志
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import api from '../api/index.js'

const logLines = ref([])
const levelFilter = ref('')
const searchText = ref('')
const autoScroll = ref(true)
const logContainer = ref(null)
let pollTimer = null

const filteredLines = computed(() => {
  let lines = logLines.value
  if (levelFilter.value) {
    lines = lines.filter(l => l.includes(`[${levelFilter.value}]`))
  }
  if (searchText.value) {
    const kw = searchText.value.toLowerCase()
    lines = lines.filter(l => l.toLowerCase().includes(kw))
  }
  return lines
})

function getLevel(line) {
  if (line.includes('[ERROR]')) return 'ERROR'
  if (line.includes('[WARN]')) return 'WARN'
  if (line.includes('[INFO]')) return 'INFO'
  if (line.includes('[DEBUG]')) return 'DEBUG'
  return ''
}

async function loadLogs() {
  try {
    const res = await api.getAppLogs(200)
    logLines.value = res.lines || []
    if (autoScroll.value) {
      nextTick(() => scrollToBottom())
    }
  } catch (e) {
    console.error('加载日志失败', e)
  }
}

function scrollToBottom() {
  if (logContainer.value) {
    logContainer.value.scrollTop = logContainer.value.scrollHeight
  }
}

watch(autoScroll, (val) => {
  if (val) nextTick(() => scrollToBottom())
})

onMounted(() => {
  loadLogs()
  // 每 3 秒轮询
  pollTimer = setInterval(loadLogs, 3000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.log-container {
  height: calc(100vh - 160px);
  overflow-y: auto;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 16px;
  border-radius: 8px;
}
</style>
