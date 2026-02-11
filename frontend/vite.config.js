import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/admin/api': 'http://localhost:8080',
      '/v1': 'http://localhost:8080',
      '/health': 'http://localhost:8080'
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  }
})
