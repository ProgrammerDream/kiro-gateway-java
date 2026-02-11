<template>
  <div class="json-viewer">
    <div v-if="data === null || data === undefined" class="null-value">null</div>
    <div v-else-if="typeof data === 'string'" class="string-value">
      <pre>{{ data }}</pre>
    </div>
    <div v-else>
      <vue-json-pretty :data="data" :deep="3" :showLength="true" :showLine="true" />
    </div>
    <el-button size="small" text class="copy-btn" @click="handleCopy">
      <el-icon><CopyDocument /></el-icon> 复制
    </el-button>
  </div>
</template>

<script setup>
import { defineProps } from 'vue'
import VueJsonPretty from 'vue-json-pretty'
import 'vue-json-pretty/lib/styles.css'
import { ElMessage } from 'element-plus'

const props = defineProps({
  data: { type: [Object, Array, String, Number, Boolean], default: null }
})

function handleCopy() {
  const text = typeof props.data === 'string' ? props.data : JSON.stringify(props.data, null, 2)
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制')
  }).catch(() => {
    ElMessage.warning('复制失败')
  })
}
</script>

<style scoped>
.json-viewer {
  position: relative;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 12px;
  max-height: 500px;
  overflow-y: auto;
}
.null-value {
  color: #909399;
  font-style: italic;
}
.string-value pre {
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'Consolas', monospace;
  font-size: 12px;
  margin: 0;
}
.copy-btn {
  position: absolute;
  top: 8px;
  right: 8px;
}
</style>
