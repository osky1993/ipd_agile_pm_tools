import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
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
