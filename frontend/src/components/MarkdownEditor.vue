<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/api/http'
import MarkdownView from './MarkdownView.vue'

/**
 * Markdown 编辑器：编辑/预览双 Tab；编辑面是原生 textarea，
 * 粘贴剪贴板图片自动上传为附件（evidence 表 category=ATTACHMENT），
 * 并在光标处插入 `![截图](attachment://{id})` 引用。
 */
const props = defineProps<{
  modelValue?: string | null
  projectId?: number | null
  rows?: number
  placeholder?: string
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const text = computed({
  get: () => props.modelValue ?? '',
  set: (v: string) => emit('update:modelValue', v),
})

const mode = ref<'edit' | 'preview'>('edit')
const ta = ref<HTMLTextAreaElement | null>(null)
const uploading = ref(false)

function insertAtCursor(snippet: string) {
  const el = ta.value
  const cur = text.value
  if (!el) {
    text.value = cur + snippet
    return
  }
  const start = el.selectionStart ?? cur.length
  const end = el.selectionEnd ?? cur.length
  text.value = cur.slice(0, start) + snippet + cur.slice(end)
  requestAnimationFrame(() => {
    el.focus()
    el.selectionStart = el.selectionEnd = start + snippet.length
  })
}

async function onPaste(e: ClipboardEvent) {
  const items = Array.from(e.clipboardData?.items ?? [])
  const imgItem = items.find((i) => i.type.startsWith('image/'))
  if (!imgItem) return
  e.preventDefault()
  if (!props.projectId) {
    ElMessage.warning('未确定所属项目，无法上传截图')
    return
  }
  const file = imgItem.getAsFile()
  if (!file) return
  uploading.value = true
  try {
    const ext = (file.type.split('/')[1] || 'png').replace('jpeg', 'jpg')
    const named = new File([file], `paste_${Date.now()}.${ext}`, { type: file.type })
    const form = new FormData()
    form.append('file', named)
    const ev = await http.post<any, { id: number }>('/evidence', form, {
      params: { projectId: props.projectId, category: 'ATTACHMENT' },
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    insertAtCursor(`![截图](attachment://${ev.id})`)
    ElMessage.success('截图已上传')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="md-editor">
    <div class="md-toolbar">
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="edit">编辑</el-radio-button>
        <el-radio-button value="preview">预览</el-radio-button>
      </el-radio-group>
      <span class="hint" v-if="mode === 'edit'">支持 Markdown，可直接粘贴截图</span>
      <span class="hint uploading" v-if="uploading">截图上传中…</span>
    </div>
    <textarea
      v-if="mode === 'edit'"
      ref="ta"
      v-model="text"
      class="md-textarea"
      :rows="rows ?? 4"
      :placeholder="placeholder"
      @paste="onPaste"
    />
    <div v-else class="md-preview">
      <MarkdownView :source="text" />
      <div v-if="!text" class="empty">（无内容）</div>
    </div>
  </div>
</template>

<style scoped>
.md-editor { width: 100%; }
.md-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
.hint { font-size: 12px; color: #c0c4cc; }
.hint.uploading { color: #e6a23c; }
.md-textarea {
  width: 100%; box-sizing: border-box; padding: 6px 10px;
  border: 1px solid #dcdfe6; border-radius: 4px; resize: vertical;
  font-family: inherit; font-size: 14px; line-height: 1.6; color: #606266;
}
.md-textarea:focus { outline: none; border-color: #409eff; }
.md-preview { border: 1px dashed #dcdfe6; border-radius: 4px; padding: 8px 12px; min-height: 60px; }
.empty { color: #c0c4cc; font-size: 13px; }
</style>
