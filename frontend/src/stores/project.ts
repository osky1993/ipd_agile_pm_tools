import { defineStore } from 'pinia'

/**
 * 全局当前项目：跨页面记住用户最后选择的项目（localStorage 持久化）。
 * ProjectChips 读写此 store；各页面仍可用 v-model 本地 ref，两者由 ProjectChips 桥接。
 */
const KEY = 'currentProjectId'

export const useProjectStore = defineStore('project', {
  state: () => ({
    currentProjectId: (() => {
      const v = localStorage.getItem(KEY)
      return v ? Number(v) : null
    })() as number | null,
  }),
  actions: {
    setCurrent(id: number) {
      this.currentProjectId = id
      localStorage.setItem(KEY, String(id))
    },
  },
})
