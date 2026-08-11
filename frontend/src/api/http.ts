import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

/**
 * 通用 axios 封装。
 * 约定：
 * - 统一拼接 /api 前缀；
 * - 自动注入 Bearer Token；
 * - 统一拦截业务码与 HTTP 失败并触发登录态恢复。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

// 请求拦截：在每次请求头上透传本地 token（登录态统一入口）。
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    // 透传后端统一响应体：成功返回 body.data，失败统一提示并触发状态清理。
    const body = resp.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      // 统一业务错误提示（含守卫错误码）
      ElMessage.error(body.message || '操作失败')
      if (body.code === 4010) {
        localStorage.removeItem('token')
        router.push('/login')
      }
      return Promise.reject(body)
    }
    return body?.data
  },
  // 网络错误/HTTP 非 2xx 时的兜底处理，401/403 会回退到登录。
  (err) => {
    const status = err.response?.status
    if (status === 401 || status === 403) {
      localStorage.removeItem('token')
      router.push('/login')
    }
    ElMessage.error(err.response?.data?.message || err.message || '网络错误')
    return Promise.reject(err)
  },
)

export default http
