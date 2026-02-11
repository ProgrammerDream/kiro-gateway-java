import axios from 'axios'

const api = axios.create({
  baseURL: '/admin/api',
  timeout: 30000
})

// 请求拦截器
api.interceptors.request.use(config => {
  const token = localStorage.getItem('admin_token')
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

// 响应拦截器
api.interceptors.response.use(
  response => response.data,
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('admin_token')
      window.location.href = '/admin/login'
    }
    return Promise.reject(error)
  }
)

export default {
  // 登录
  login: (password) => api.post('/login', { password }),

  // 仪表盘
  getDashboard: () => api.get('/dashboard'),

  // 账号管理
  getAccounts: () => api.get('/accounts'),
  addAccount: (data) => api.post('/accounts', data),
  deleteAccount: (id) => api.delete(`/accounts/${id}`),

  // 请求日志
  getRequestLogs: (params) => api.get('/request-logs', { params }),

  // 追踪详情
  getTrace: (traceId) => api.get(`/traces/${traceId}`),

  // 模型
  getModels: () => api.get('/models'),

  // 应用日志
  getAppLogs: (lines = 100) => api.get('/app-logs', { params: { lines } }),

  // API Keys
  getApiKeys: () => api.get('/api-keys'),
}
