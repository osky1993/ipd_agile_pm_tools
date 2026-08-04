<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { workItemApi, metaApi, type WorkItem } from '@/api/workitem'
import { useProjectStore } from '@/stores/project'

/** 全局快速新建工作项（快捷键 n 唤起）：项目取全局 store，三字段极简录入。 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  created: [item: WorkItem]
}>()

const store = useProjectStore()
const types = ref<{ value: string; abbr: string; label: string }[]>([])
const form = ref({ type: 'REQUIREMENT', title: '', priority: 'P2' })
const titleInput = ref<{ focus: () => void } | null>(null)

watch(() => props.modelValue, async (open) => {
  if (open) {
    form.value = { type: 'REQUIREMENT', title: '', priority: 'P2' }
    if (!types.value.length) types.value = await metaApi.workItemTypes()
    requestAnimationFrame(() => titleInput.value?.focus())
  }
})

async function submit() {
  if (!store.currentProjectId) return ElMessage.warning('尚未选择项目，请先进入任一页面选择项目')
  if (!form.value.title.trim()) return ElMessage.warning('请填写标题')
  const created = await workItemApi.create({
    projectId: store.currentProjectId,
    type: form.value.type,
    title: form.value.title.trim(),
    priority: form.value.priority,
  })
  ElMessage.success(`已创建 ${created.code}`)
  emit('update:modelValue', false)
  emit('created', created)
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="快速新建工作项" width="440px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <el-form label-width="64px" @submit.prevent>
      <el-form-item label="类型">
        <el-select v-model="form.type" style="width:100%">
          <el-option v-for="t in types" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="标题">
        <el-input ref="titleInput" v-model="form.title" placeholder="回车即创建" @keyup.enter="submit" />
      </el-form-item>
      <el-form-item label="优先级">
        <el-select v-model="form.priority" style="width:100%">
          <el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :label="p" :value="p" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="submit">创建并打开</el-button>
    </template>
  </el-dialog>
</template>
