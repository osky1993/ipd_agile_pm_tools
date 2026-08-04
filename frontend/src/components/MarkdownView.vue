<script lang="ts">
/**
 * Markdown 只读渲染：markdown-it(html:false) → DOMPurify 消毒 → attachment:// 图片经带 token 的
 * fetch 换成 blob objectURL（/api/evidence/{id}/preview 需要 Bearer 头，<img src> 直连带不上）。
 * blob URL 模块级缓存，跨实例共享，避免同图重复下载。
 */
const attachmentCache = new Map<string, Promise<string>>()

export function attachmentBlobUrl(id: string): Promise<string> {
  let p = attachmentCache.get(id)
  if (!p) {
    p = fetch(`/api/evidence/${id}/preview`, {
      headers: { Authorization: `Bearer ${localStorage.getItem('token') ?? ''}` },
    })
      .then((r) => {
        if (!r.ok) throw new Error(`附件加载失败 ${r.status}`)
        return r.blob()
      })
      .then((b) => URL.createObjectURL(b))
    attachmentCache.set(id, p)
    p.catch(() => attachmentCache.delete(id))
  }
  return p
}
</script>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const props = defineProps<{ source?: string | null }>()
const emit = defineEmits<{ (e: 'images-loaded'): void }>()

const el = ref<HTMLElement | null>(null)
const html = ref('')

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

// attachment://{id} → 占位属性 data-att-id，渲染后再补 blob src
const defaultImage = md.renderer.rules.image
md.renderer.rules.image = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const src = String(token.attrGet('src') ?? '')
  const m = src.match(/^attachment:\/\/(\d+)$/)
  if (m) {
    token.attrSet('src', '')
    token.attrSet('data-att-id', m[1])
  }
  return defaultImage ? defaultImage(tokens, idx, options, env, self) : self.renderToken(tokens, idx, options)
}

async function render() {
  const raw = props.source ?? ''
  html.value = DOMPurify.sanitize(md.render(raw), { ADD_ATTR: ['data-att-id'] })
  await nextTick()
  const imgs = Array.from(el.value?.querySelectorAll<HTMLImageElement>('img[data-att-id]') ?? [])
  await Promise.all(
    imgs.map(async (img) => {
      try {
        img.src = await attachmentBlobUrl(img.dataset.attId!)
        await img.decode().catch(() => {})
      } catch {
        img.alt = '（附件加载失败）'
      }
    }),
  )
  emit('images-loaded')
}

watch(() => props.source, render, { immediate: true })
</script>

<template>
  <div ref="el" class="md-view" v-html="html" />
</template>

<style scoped>
.md-view { font-size: 14px; line-height: 1.7; color: #303133; word-break: break-word; }
.md-view :deep(h1), .md-view :deep(h2), .md-view :deep(h3) { margin: 10px 0 6px; font-size: 15px; }
.md-view :deep(p) { margin: 4px 0; }
.md-view :deep(ul), .md-view :deep(ol) { padding-left: 20px; margin: 4px 0; }
.md-view :deep(code) { background: #f5f7fa; padding: 1px 5px; border-radius: 3px; font-size: 13px; }
.md-view :deep(pre) { background: #f5f7fa; padding: 8px 10px; border-radius: 4px; overflow-x: auto; }
.md-view :deep(pre code) { background: none; padding: 0; }
.md-view :deep(blockquote) { border-left: 3px solid #dcdfe6; margin: 6px 0; padding: 2px 10px; color: #909399; }
.md-view :deep(img) { max-width: 100%; border: 1px solid #ebeef5; border-radius: 4px; margin: 4px 0; }
.md-view :deep(table) { border-collapse: collapse; margin: 6px 0; }
.md-view :deep(th), .md-view :deep(td) { border: 1px solid #ebeef5; padding: 4px 10px; }
</style>
