import { onMounted, onUnmounted } from 'vue'

/**
 * 全局快捷键（单人工具，固定绑定不做配置）。
 * 绑定 key 形如 '/'、'n'、'?'、'g d'（两键序列，前缀后 1.5s 内等第二键）。
 * 输入框聚焦、弹窗/抽屉打开、按住修饰键时全部忽略。
 */
type Handler = (e: KeyboardEvent) => void

const SEQ_TIMEOUT = 1500

function isTyping(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

function overlayOpen(): boolean {
  return Array.from(document.querySelectorAll<HTMLElement>('.el-overlay'))
    .some((el) => getComputedStyle(el).display !== 'none')
}

export function useHotkeys(bindings: Record<string, Handler>) {
  let prefix = ''
  let timer: number | undefined

  const prefixes = new Set(
    Object.keys(bindings).filter((k) => k.includes(' ')).map((k) => k.split(' ')[0]),
  )

  function clearPrefix() {
    prefix = ''
    if (timer) window.clearTimeout(timer)
  }

  function onKeydown(e: KeyboardEvent) {
    if (e.metaKey || e.ctrlKey || e.altKey) return
    if (isTyping(e.target) || overlayOpen()) {
      clearPrefix()
      return
    }
    const key = e.key
    if (prefix) {
      const combo = `${prefix} ${key}`
      clearPrefix()
      const h = bindings[combo]
      if (h) {
        e.preventDefault()
        h(e)
      }
      return
    }
    if (prefixes.has(key)) {
      prefix = key
      timer = window.setTimeout(clearPrefix, SEQ_TIMEOUT)
      e.preventDefault()
      return
    }
    const h = bindings[key]
    if (h) {
      e.preventDefault()
      h(e)
    }
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onUnmounted(() => {
    clearPrefix()
    window.removeEventListener('keydown', onKeydown)
  })
}
