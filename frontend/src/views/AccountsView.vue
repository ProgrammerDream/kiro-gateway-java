<template>
  <div>
    <div class="page-header" style="display:flex;justify-content:space-between;align-items:center">
      <h1>账号管理</h1>
      <div style="display:flex;gap:8px">
        <el-button @click="queryAllCredits" :loading="queryingAll">刷新额度</el-button>
        <el-button type="primary" @click="showAddDialog = true">添加账号</el-button>
      </div>
    </div>

    <!-- Credits 汇总卡片 -->
    <div v-if="creditsSummary" class="content-card" style="margin-bottom:16px">
      <div style="display:flex;gap:32px;align-items:center">
        <div>
          <span style="color:#909399;font-size:13px">总额度</span>
          <div style="font-size:20px;font-weight:bold;color:#409eff">{{ creditsSummary.totalLimit?.toFixed(2) }}</div>
        </div>
        <div>
          <span style="color:#909399;font-size:13px">已使用</span>
          <div style="font-size:20px;font-weight:bold;color:#e6a23c">{{ creditsSummary.totalUsed?.toFixed(2) }}</div>
        </div>
        <div>
          <span style="color:#909399;font-size:13px">剩余</span>
          <div style="font-size:20px;font-weight:bold;color:#67c23a">{{ creditsSummary.totalAvailable?.toFixed(2) }}</div>
        </div>
        <div style="flex:1">
          <el-progress
            :percentage="creditsSummary.totalLimit > 0 ? Math.min(100, (creditsSummary.totalUsed / creditsSummary.totalLimit * 100)) : 0"
            :stroke-width="16"
            :color="progressColor(creditsSummary.totalUsed, creditsSummary.totalLimit)"
          />
        </div>
      </div>
    </div>

    <div class="content-card">
      <el-table :data="accounts" stripe style="width: 100%">
        <el-table-column prop="name" label="名称" width="130" />
        <el-table-column prop="authMethod" label="认证" width="80" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="额度 (总/已用/剩余)" min-width="240">
          <template #default="{ row }">
            <div v-if="row._credits">
              <span style="color:#409eff">{{ row._credits.usageLimit?.toFixed(2) }}</span>
              <span style="color:#909399"> / </span>
              <span style="color:#e6a23c">{{ row._credits.currentUsage?.toFixed(2) }}</span>
              <span style="color:#909399"> / </span>
              <span style="color:#67c23a">{{ row._credits.available?.toFixed(2) }}</span>
              <span v-if="row._credits.subscriptionType" style="margin-left:8px">
                <el-tag size="small" type="info">{{ row._credits.subscriptionType }}</el-tag>
              </span>
            </div>
            <span v-else-if="row._creditsLoading" style="color:#909399">查询中...</span>
            <span v-else-if="row._creditsError" style="color:#f56c6c">{{ row._creditsError }}</span>
            <span v-else style="color:#c0c4cc">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="requestCount" label="请求" width="70" />
        <el-table-column prop="creditsTotal" label="已消耗" width="90">
          <template #default="{ row }">{{ row.creditsTotal?.toFixed(4) }}</template>
        </el-table-column>
        <el-table-column prop="lastUsedAt" label="最后使用" width="170">
          <template #default="{ row }">{{ formatTime(row.lastUsedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button type="primary" size="small" text @click="queryCredits(row)">额度</el-button>
            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button type="danger" size="small" text @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="showAddDialog" title="添加账号" width="500px">
      <el-form :model="addForm" label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="addForm.name" placeholder="账号名称" />
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="addForm.authMethod">
            <el-option label="Social" value="social" />
            <el-option label="IDC" value="idc" />
            <el-option label="Builder ID" value="builderid" />
          </el-select>
        </el-form-item>
        <el-form-item label="凭证">
          <el-input v-model="addForm.credentials" type="textarea" :rows="6" placeholder='{"refreshToken":"..."}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" @click="handleAdd">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showEditDialog" title="编辑账号" width="500px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="editForm.authMethod">
            <el-option label="Social" value="social" />
            <el-option label="IDC" value="idc" />
            <el-option label="Builder ID" value="builderid" />
          </el-select>
        </el-form-item>
        <el-form-item label="凭证">
          <el-input v-model="editForm.credentials" type="textarea" :rows="6" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" @click="handleEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api/index.js'

const accounts = ref([])
const showAddDialog = ref(false)
const addForm = ref({ name: '', authMethod: 'social', credentials: '' })
const showEditDialog = ref(false)
const editForm = ref({ id: '', name: '', authMethod: 'social', credentials: '' })
const creditsSummary = ref(null)
const queryingAll = ref(false)

async function loadAccounts() {
  try {
    accounts.value = await api.getAccounts()
    restoreCreditsCache()
  } catch (e) { console.error(e) }
}

// 从 localStorage 恢复上次查询的额度数据
function restoreCreditsCache() {
  try {
    let cached = localStorage.getItem('credits_cache')
    if (!cached) return
    let data = JSON.parse(cached)
    if (data.summary) creditsSummary.value = data.summary
    if (data.accounts) {
      for (let item of data.accounts) {
        let row = accounts.value.find(a => a.id === item.id)
        if (row) {
          if (item.error) {
            row._creditsError = item.error
          } else {
            row._credits = item
          }
        }
      }
    }
  } catch (e) { /* ignore */ }
}

function saveCreditsCache(summary) {
  try {
    localStorage.setItem('credits_cache', JSON.stringify({
      summary,
      accounts: summary.accounts
    }))
  } catch (e) { /* ignore */ }
}

async function queryCredits(row) {
  row._creditsLoading = true
  row._credits = null
  row._creditsError = null
  try {
    let res = await api.getAccountCredits(row.id)
    if (res.error) {
      row._creditsError = res.error
    } else {
      row._credits = res
    }
  } catch (e) {
    row._creditsError = '查询失败'
  } finally {
    row._creditsLoading = false
  }
}

async function queryAllCredits() {
  queryingAll.value = true
  try {
    let res = await api.getCreditsSummary()
    creditsSummary.value = res
    saveCreditsCache(res)
    // 将每个账号的 credits 回填到表格
    if (res.accounts) {
      for (let item of res.accounts) {
        let row = accounts.value.find(a => a.id === item.id)
        if (row) {
          if (item.error) {
            row._creditsError = item.error
          } else {
            row._credits = item
          }
        }
      }
    }
  } catch (e) {
    ElMessage.error('查询额度失败')
  } finally {
    queryingAll.value = false
  }
}

function progressColor(used, limit) {
  if (limit <= 0) return '#909399'
  let pct = used / limit * 100
  if (pct >= 90) return '#f56c6c'
  if (pct >= 70) return '#e6a23c'
  return '#67c23a'
}

async function handleAdd() {
  try {
    await api.addAccount(addForm.value)
    ElMessage.success('添加成功')
    showAddDialog.value = false
    addForm.value = { name: '', authMethod: 'social', credentials: '' }
    await loadAccounts()
  } catch (e) { ElMessage.error('添加失败') }
}

function openEdit(row) {
  editForm.value = { id: row.id, name: row.name, authMethod: row.authMethod, credentials: row.credentials || '' }
  showEditDialog.value = true
}

async function handleEdit() {
  let data = { name: editForm.value.name, authMethod: editForm.value.authMethod }
  // 凭证为空则不更新
  if (editForm.value.credentials) data.credentials = editForm.value.credentials
  try {
    await api.updateAccount(editForm.value.id, data)
    ElMessage.success('更新成功')
    showEditDialog.value = false
    await loadAccounts()
  } catch (e) { ElMessage.error('更新失败') }
}

async function handleDelete(id) {
  try {
    await ElMessageBox.confirm('确定删除该账号？', '确认')
    await api.deleteAccount(id)
    ElMessage.success('删除成功')
    await loadAccounts()
  } catch (e) { /* cancelled */ }
}

function formatTime(t) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

onMounted(loadAccounts)
</script>
