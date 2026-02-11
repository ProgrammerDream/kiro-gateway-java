<template>
  <div>
    <div class="page-header" style="display:flex;align-items:center;gap:12px">
      <el-button @click="$router.back()" text><el-icon><ArrowLeft /></el-icon>返回</el-button>
      <h1>对话详情</h1>
      <el-tag :type="trace.success ? 'success' : 'danger'" v-if="trace.traceId">{{ trace.success ? '成功' : '失败' }}</el-tag>
    </div>

    <div v-if="trace.traceId">
      <!-- 摘要信息 -->
      <div class="content-card" style="margin-bottom: 16px">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="Trace ID">{{ trace.traceId }}</el-descriptions-item>
          <el-descriptions-item label="API 类型">{{ trace.apiType }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ trace.model }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ trace.durationMs }}ms</el-descriptions-item>
          <el-descriptions-item label="输入 Tokens">{{ trace.inputTokens }}</el-descriptions-item>
          <el-descriptions-item label="输出 Tokens">{{ trace.outputTokens }}</el-descriptions-item>
          <el-descriptions-item label="Credits">{{ trace.credits }}</el-descriptions-item>
          <el-descriptions-item label="Kiro 端点">{{ trace.kiroEndpoint }}</el-descriptions-item>
          <el-descriptions-item label="Kiro 状态码">{{ trace.kiroStatus }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="trace.errorMessage" style="margin-top: 12px; color: #f56c6c">
          <strong>错误信息：</strong>{{ trace.errorMessage }}
        </div>
      </div>

      <!-- 四阶段可视化 -->
      <el-collapse v-model="activeCollapse">
        <el-collapse-item title="① 客户端请求" name="client_request">
          <div class="json-section">
            <h4>请求头</h4>
            <JsonViewer :data="parseJson(trace.clientHeaders)" />
            <h4 style="margin-top: 16px">请求体</h4>
            <JsonViewer :data="parseJson(trace.clientRequest)" />
          </div>
        </el-collapse-item>

        <el-collapse-item title="② Kiro 请求" name="kiro_request">
          <div class="json-section">
            <h4>端点: {{ trace.kiroEndpoint }}</h4>
            <h4 style="margin-top: 8px">请求头</h4>
            <JsonViewer :data="parseJson(trace.kiroHeaders)" />
            <h4 style="margin-top: 16px">请求体</h4>
            <JsonViewer :data="parseJson(trace.kiroRequest)" />
          </div>
        </el-collapse-item>

        <el-collapse-item title="③ Kiro 响应" name="kiro_response">
          <div class="json-section">
            <h4>状态码: {{ trace.kiroStatus }}</h4>
            <h4 style="margin-top: 16px">事件列表</h4>
            <JsonViewer :data="parseJson(trace.kiroEvents)" />
          </div>
        </el-collapse-item>

        <el-collapse-item title="④ 客户端响应" name="client_response">
          <div class="json-section">
            <h4>状态码: {{ trace.clientStatus }}</h4>
            <h4 style="margin-top: 16px">响应体</h4>
            <JsonViewer :data="parseJson(trace.clientResponse)" />
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <div v-else class="content-card" style="text-align: center; padding: 40px; color: #909399">
      加载中...
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api/index.js'
import JsonViewer from '../components/JsonViewer.vue'

const route = useRoute()
const trace = ref({})
const activeCollapse = ref(['client_request', 'kiro_request', 'kiro_response', 'client_response'])

function parseJson(str) {
  if (!str) return null
  try { return JSON.parse(str) } catch (e) { return str }
}

onMounted(async () => {
  try {
    trace.value = await api.getTrace(route.params.traceId)
  } catch (e) {
    console.error('加载追踪详情失败', e)
  }
})
</script>

<style scoped>
.json-section {
  padding: 8px 0;
}
.json-section h4 {
  color: #606266;
  font-size: 13px;
  margin-bottom: 8px;
}
</style>
