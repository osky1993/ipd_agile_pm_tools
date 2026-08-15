import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { FullConfig } from '@playwright/test'
import { Api, waitForBackend } from './support/api'
import { seedSkeleton } from './support/seed'

// package.json 是 "type": "module"，没有 __dirname
const HERE = dirname(fileURLToPath(import.meta.url))

export const STATE_DIR = resolve(HERE, '.state')
export const STORAGE_STATE = resolve(STATE_DIR, 'storage.json')
export const SEED_FILE = resolve(STATE_DIR, 'seed.json')

/**
 * 全局准备：探活 → 登录 → 造项目骨架 → 手工拼 storageState 落盘。
 * storageState 不需要启浏览器——localStorage 直接写 origins 数组即可。
 */
export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0]?.use?.baseURL ?? 'http://127.0.0.1:15173'
  const api = new Api()

  await waitForBackend(api)
  const skeleton = await seedSkeleton(api)

  mkdirSync(dirname(STORAGE_STATE), { recursive: true })

  writeFileSync(
    STORAGE_STATE,
    JSON.stringify(
      {
        cookies: [],
        origins: [
          {
            origin: baseURL.replace(/\/$/, ''),
            localStorage: [
              { name: 'token', value: skeleton.token },
              { name: 'displayName', value: skeleton.displayName },
              { name: 'roles', value: JSON.stringify(skeleton.roles) },
              // stores/project.ts 的 key：预置后 ProjectChips 直接选中 e2e 项目
              { name: 'currentProjectId', value: String(skeleton.projectId) },
            ],
          },
        ],
      },
      null,
      2,
    ),
  )

  writeFileSync(SEED_FILE, JSON.stringify(skeleton, null, 2))
  console.log(`[e2e] 骨架就绪：项目 ${skeleton.projectCode}(id=${skeleton.projectId})`)
}
