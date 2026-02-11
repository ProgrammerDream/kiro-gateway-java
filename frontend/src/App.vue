<template>
  <div class="app-container">
    <el-container v-if="isLoggedIn" style="height: 100vh">
      <el-aside width="220px" class="sidebar">
        <div class="logo">
          <h2>Kiro Gateway</h2>
        </div>
        <el-menu
          :default-active="$route.path"
          router
          background-color="#1d1e2c"
          text-color="#a0a0b0"
          active-text-color="#409eff"
        >
          <el-menu-item index="/admin/dashboard">
            <el-icon><DataBoard /></el-icon>
            <span>仪表盘</span>
          </el-menu-item>
          <el-menu-item index="/admin/accounts">
            <el-icon><User /></el-icon>
            <span>账号管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/conversations">
            <el-icon><ChatDotSquare /></el-icon>
            <span>对话记录</span>
          </el-menu-item>
          <el-menu-item index="/admin/app-logs">
            <el-icon><Document /></el-icon>
            <span>应用日志</span>
          </el-menu-item>
          <el-menu-item index="/admin/settings">
            <el-icon><Setting /></el-icon>
            <span>设置</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
    <router-view v-else />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const isLoggedIn = computed(() => {
  return route.path !== '/admin/login' && localStorage.getItem('admin_token')
})
</script>

<style scoped>
.sidebar {
  background-color: #1d1e2c;
  border-right: 1px solid #2a2b3d;
}
.logo {
  padding: 20px;
  text-align: center;
  border-bottom: 1px solid #2a2b3d;
}
.logo h2 {
  color: #409eff;
  margin: 0;
  font-size: 18px;
}
.main-content {
  background-color: #f5f7fa;
  padding: 20px;
  overflow-y: auto;
}
</style>
