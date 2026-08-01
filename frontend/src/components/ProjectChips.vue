<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http from '@/api/http'

/**
 * 项目平铺选择（chips）：替代下拉，一眼可见、一击切换。
 * 组件自带项目加载与"默认选中第一个"，选中（含自动选中）都会触发 change。
 */
interface Project { id: number; code: string; name: string; lifecycleStatus?: string }

const props = defineProps<{ modelValue: number | null; dark?: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: number): void
  (e: 'change', v: number): void
}>()

const projects = ref<Project[]>([])

function select(id: number) {
  if (props.modelValue === id) return
  emit('update:modelValue', id)
  emit('change', id)
}

onMounted(async () => {
  projects.value = await http.get<any, Project[]>('/projects')
  if (props.modelValue == null && projects.value.length) {
    emit('update:modelValue', projects.value[0].id)
    emit('change', projects.value[0].id)
  }
})
</script>

<template>
  <div class="project-strip" :class="{ dark: props.dark }">
    <div
      v-for="p in projects"
      :key="p.id"
      class="project-chip"
      :class="{ active: p.id === modelValue, hold: p.lifecycleStatus && p.lifecycleStatus !== 'ACTIVE' }"
      @click="select(p.id)"
    >
      <b class="p-code">{{ p.code }}</b>
      <span class="p-name">{{ p.name }}</span>
    </div>
  </div>
</template>

<style scoped>
.project-strip { display: flex; gap: 8px; flex-wrap: wrap; }
.project-chip { display: flex; align-items: center; gap: 6px; padding: 5px 12px; border: 1px solid #dcdfe6; border-radius: 16px; cursor: pointer; background: #fff; transition: all .15s; font-size: 13px; }
.project-chip:hover { border-color: #409eff; }
.project-chip.active { border-color: #409eff; background: #ecf5ff; color: #409eff; }
.project-chip.hold { opacity: .65; }
.project-chip .p-code { font-family: monospace; }
.project-chip .p-name { color: #606266; max-width: 130px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-chip.active .p-name { color: #409eff; }

/* 暗色投屏模式 */
.dark .project-chip { background: #1c2c49; border-color: #2a3a55; color: #c9d4e8; }
.dark .project-chip .p-name { color: #8fa2c0; }
.dark .project-chip:hover { border-color: #3987e5; }
.dark .project-chip.active { background: rgba(57, 135, 229, .18); border-color: #3987e5; color: #3987e5; box-shadow: 0 0 10px rgba(57, 135, 229, .25); }
.dark .project-chip.active .p-name { color: #3987e5; }
</style>
