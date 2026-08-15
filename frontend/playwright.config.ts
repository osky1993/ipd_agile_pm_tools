import { defineConfig, devices } from '@playwright/test'

const WEB_PORT = process.env.E2E_WEB_PORT ?? '15173'

/**
 * e2e 薄保护网（docs/07 §1 A1）：只护三条关键动线，不护像素。
 * 环境编排（MySQL / 后端 jar / 前端 dev server）由 deploy/e2e_ui.sh 负责，
 * 这里不用 webServer——它管不了「先起库、再起 jar、再起 vite」的顺序与清理。
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  // 产物放到 frontend/ 之外：否则 vite dev server 会监听到报告文件写入，
  // 在测试跑到一半时触发页面重载（实测出现过），是真实的抖动源。
  outputDir: '../deploy/.e2e/test-results',
  // 三条动线共享一个后端与一个库，且页面往 localStorage 写状态，并发只会换来假红
  fullyParallel: false,
  workers: 1,
  // 单人工具：重试等于把脆弱性藏起来，我们恰恰想知道哪条动线开始抖了
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 8_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: '../deploy/.e2e/report' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? `http://127.0.0.1:${WEB_PORT}`,
    storageState: 'e2e/.state/storage.json',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    // 失败诊断产物，不做像素比对（全程不使用 toHaveScreenshot）
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'chromium',
      // MyToday 的 .grid 在窄屏会从 4 列塌成 2 列，给足宽度走正常布局分支
      use: { ...devices['Desktop Chrome'], viewport: { width: 1600, height: 1000 } },
    },
  ],
})
