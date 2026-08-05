import { defineStore } from 'pinia'
import http from '@/api/http'

/** 用户字典：一次加载全站缓存，ownerId → 显示名。 */
export interface UserOption {
  id: number
  username: string
  displayName: string
}

export const useUserStore = defineStore('users', {
  state: () => ({
    users: [] as UserOption[],
    loaded: false,
    loading: null as Promise<void> | null,
  }),
  actions: {
    async load() {
      if (this.loaded) return
      if (!this.loading) {
        this.loading = http.get<any, UserOption[]>('/meta/users').then((list) => {
          this.users = list
          this.loaded = true
        }).finally(() => { this.loading = null })
      }
      await this.loading
    },
    /** 显示名；未知 id 回落 #id，空返回 — */
    label(id: number | null | undefined): string {
      if (id == null) return '—'
      return this.users.find((u) => u.id === id)?.displayName ?? `#${id}`
    },
  },
})
