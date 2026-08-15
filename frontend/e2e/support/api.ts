/**
 * 极简 REST 客户端：夹具造数专用。
 *
 * 为什么夹具走 API 而不是 UI——UI 造数既慢又脆，一旦某个无关页面的文案变了，
 * 三条动线会在「准备阶段」一起红，定位成本翻倍。只有被保护的那几步才走 UI。
 */

const API_BASE = process.env.E2E_API ?? 'http://127.0.0.1:18080/api'

/** 后端统一响应体 `{code, message, data}`，code 0 为成功。 */
interface Envelope<T> {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  constructor(public code: number, message: string, public path: string) {
    super(`${path} → [${code}] ${message}`)
  }
}

export class Api {
  private token = ''

  constructor(public readonly base = API_BASE) {}

  setToken(t: string) {
    this.token = t
  }

  async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (this.token) headers.Authorization = `Bearer ${this.token}`

    const resp = await fetch(`${this.base}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await resp.text()
    let env: Envelope<T>
    try {
      env = JSON.parse(text)
    } catch {
      throw new ApiError(resp.status, `非 JSON 响应：${text.slice(0, 200)}`, path)
    }
    if (env.code !== 0) throw new ApiError(env.code, env.message, path)
    return env.data
  }

  get<T>(path: string) {
    return this.request<T>('GET', path)
  }
  post<T>(path: string, body?: unknown) {
    return this.request<T>('POST', path, body)
  }
  put<T>(path: string, body?: unknown) {
    return this.request<T>('PUT', path, body)
  }

  /** 登录并记住 token。 */
  async login(username = 'admin', password = 'admin123') {
    const data = await this.post<{
      token: string
      userId: number
      username: string
      displayName: string
      roles: string[]
    }>('/auth/login', { username, password })
    this.setToken(data.token)
    return data
  }
}

/**
 * 等后端真正可用。
 *
 * 注意不能只探端口：Web 容器在 DataInitializer（ApplicationRunner，负责建 admin）
 * 之前就已监听端口，那个窗口里 login 会返回 4010。必须轮询到真的拿得到 token。
 */
export async function waitForBackend(api: Api, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs
  let last = ''
  while (Date.now() < deadline) {
    try {
      await api.login()
      return
    } catch (e) {
      last = e instanceof Error ? e.message : String(e)
      await new Promise((r) => setTimeout(r, 1000))
    }
  }
  throw new Error(
    `后端 ${api.base} 在 ${timeoutMs / 1000}s 内未就绪（最后一次错误：${last}）。\n` +
      '请用 `npm run e2e` 启动完整环境；若要单独跑用例，先 `bash deploy/e2e_ui.sh --keep --grep=__none__` 起环境。',
  )
}

/** 今天起偏移 n 天的 YYYY-MM-DD。 */
export function dayOffset(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
}
