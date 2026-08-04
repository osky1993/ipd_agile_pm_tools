/**
 * 工具箱 REST 客户端：Result{code,message,data} 统一解包，业务错误（含守卫 4091）转异常透传给模型。
 * 配置：IPD_BASE_URL（默认 http://localhost:8080）、IPD_TOKEN（POST /api/auth/api-token 获取的长效 token）。
 */
const BASE_URL = (process.env.IPD_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '')
const TOKEN = process.env.IPD_TOKEN ?? ''

interface Result<T> {
  code: number
  message: string
  data: T
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const resp = await fetch(`${BASE_URL}/api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status} ${path}${resp.status === 401 || resp.status === 403 ? '（请检查 IPD_TOKEN）' : ''}`)
  }
  const json = (await resp.json()) as Result<T>
  if (typeof json.code === 'number' && json.code !== 0) {
    throw new Error(`[${json.code}] ${json.message}`)
  }
  return json.data
}

export const api = {
  get: <T>(path: string, params?: Record<string, string | number | undefined>) => {
    const qs = params
      ? '?' + Object.entries(params)
          .filter(([, v]) => v !== undefined)
          .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
          .join('&')
      : ''
    return request<T>('GET', `${path}${qs}`)
  },
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
}

export function textResult(data: unknown) {
  return { content: [{ type: 'text' as const, text: JSON.stringify(data, null, 2) }] }
}
