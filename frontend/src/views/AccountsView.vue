<template>
  <div>
    <div class="page-header" style="display:flex;justify-content:space-between;align-items:center">
      <h1>账号管理</h1>
      <el-button type="primary" @click="showAddDialog = true">添加账号</el-button>
    </div>
    <div class="content-card">
      <el-table :data="accounts" stripe style="width: 100%">
        <el-table-column prop="name" label="名称" width="150" />
        <el-table-column prop="authMethod" label="认证方式" width="100" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="requestCount" label="请求数" width="80" />
        <el-table-column prop="successCount" label="成功" width="80" />
        <el-table-column prop="errorCount" label="错误" width="80" />
        <el-table-column prop="creditsTotal" label="Credits" width="100">
          <template #default="{ row }">{{ row.creditsTotal?.toFixed(4) }}</template>
        </el-table-column>
        <el-table-column prop="lastUsedAt" label="最后使用" width="180">
          <template #default="{ row }">{{ formatTime(row.lastUsedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api/index.js'

const accounts = ref([])
const showAddDialog = ref(false)
const addForm = ref({ name: '', authMethod: 'social', credentials: '' })

async function loadAccounts() {
  try { accounts.value = await api.getAccounts() } catch (e) { console.error(e) }
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
