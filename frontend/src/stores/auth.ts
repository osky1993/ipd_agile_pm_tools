import { defineStore } from 'pinia'
import http from '@/api/http'

interface AuthState {
  token: string
  userId: number | null
  username: string
  displayName: string
  roles: string[]
}

/** 认证状态（Pinia）：保存 token、用户信息与角色，负责登出清理本地持久化键。 */
export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem('token') || '',
    userId: null,
    username: '',
    displayName: localStorage.getItem('displayName') || '',
    roles: JSON.parse(localStorage.getItem('roles') || '[]'),
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    hasRole: (s) => (role: string) => s.roles.includes('ADMIN') || s.roles.includes(role),
  },
  actions: {
    async login(username: string, password: string) {
      const data = await http.post<any, {
        token: string
        userId: number
        username: string
        displayName: string
        roles: string[]
      }>('/auth/login', { username, password })
      this.token = data.token
      this.userId = data.userId
      this.username = data.username
      this.displayName = data.displayName
      this.roles = data.roles
      localStorage.setItem('token', data.token)
      localStorage.setItem('displayName', data.displayName)
      localStorage.setItem('roles', JSON.stringify(data.roles))
    },
    logout() {
      this.token = ''
      this.roles = []
      localStorage.removeItem('token')
      localStorage.removeItem('displayName')
      localStorage.removeItem('roles')
    },
  },
})
