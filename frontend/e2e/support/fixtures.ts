import { readFileSync } from 'node:fs'
import { test as base, expect, type Page } from '@playwright/test'
import { Api } from './api'
import type { Skeleton } from './seed'
import { SEED_FILE } from '../global-setup'

/**
 * 选择器与文案常量集中一处。
 *
 * 取舍：**驱动型**定位（为了走到下一步而点的东西）用 CSS/结构，
 * **断言型**定位（守卫报错、状态中文名、空态文案）一律硬断文案——
 * 因为那些文案本身就是被保护的契约，改了就该有人确认。
 */
export const SEL = {
  toastError: '.el-message--error',
  toastSuccess: '.el-message--success',
  toastContent: '.el-message__content',
  loadingMask: '.el-loading-mask',
  // 页面里常同时存在多个 el-drawer（如清单页自带抽屉 + WorkItemDrawer），
  // 只有打开的那个带 .open，否则会 strict mode violation
  drawer: '.el-drawer.open',
  dialog: '.el-dialog',
  projectChipActive: '.project-chip.active',
  statusActions: '.status-actions',
  mdTextarea: '.md-textarea',
  hiddenFile: 'input.hidden-file',
} as const

/** 守卫报错全文——这是契约，不抽象成模式匹配。 */
export const GUARD = {
  readyUnmetAcceptance: '[GUARD_READY_UNMET] 进入 Ready 需先填写验收条件',
  readyUnmetAssign: '[GUARD_READY_UNMET] 进入 Sprint 承诺前需满足 Ready 条件（验收条件、责任人、估算）',
  redlineUnmet: '[GUARD_REDLINE_UNMET] 存在红线未满足项，不能判通过：',
} as const

/** 「我的一天」四栏标题与对应空态文案（空态出现即视为聚合失败）。 */
export const MY_COLUMNS = [
  { title: '进行中', empty: '没有进行中的事项 🎉' },
  { title: '已超期', empty: '无超期事项' },
  { title: '待复测缺陷', empty: '无待复测缺陷' },
  { title: '迭代与 DCP', empty: '无临近事项与关键预警' },
] as const

/** 按 Element Plus 表单项的 label 精确定位其输入框。 */
export function formInput(page: Page, label: string) {
  return page.locator(`.el-form-item:has(> .el-form-item__label:text-is("${label}")) input`)
}

/**
 * el-select 的下拉选项。
 * 必须限定在**可见**的那个 popper 里：Element Plus 关闭下拉后 DOM 仍在，
 * 页面上有多个 select 时，不限定就会命中已关闭下拉里的同名选项。
 */
export function selectOption(page: Page, text: string) {
  return page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: text })
}

/**
 * 断言 toast 并**等它消失**。
 *
 * 第二步是本套用例最重要的稳定性措施：ElMessage 默认 3 秒后消失但会堆叠，
 * 不等上一条清空就做下一步，下一次断言可能命中残留的旧 toast → 假绿。
 */
export async function expectToast(page: Page, kind: 'error' | 'success', text: string | RegExp) {
  const cls = kind === 'error' ? SEL.toastError : SEL.toastSuccess
  await expect(page.locator(`${cls} ${SEL.toastContent}`).first()).toContainText(text)
  await expect(page.locator(cls)).toHaveCount(0, { timeout: 10_000 })
}

/** 断言当前没有错误 toast（用于"这一步不该报错"）。 */
export async function expectNoErrorToast(page: Page) {
  await expect(page.locator(SEL.toastError)).toHaveCount(0)
}

/**
 * 等项目 chips 渲染并选中。
 * ProjectChips 是 onMounted 异步拉 /projects 后才 emit change，
 * 不等它就操作会点到还没渲染的元素。
 */
export async function waitProjectReady(page: Page) {
  await expect(page.locator(SEL.projectChipActive)).toBeVisible()
  await expect(page.locator(SEL.loadingMask)).toHaveCount(0)
}

interface Fixtures {
  api: Api
  seed: Skeleton
}

export const test = base.extend<Fixtures>({
  seed: async ({}, use) => {
    await use(JSON.parse(readFileSync(SEED_FILE, 'utf-8')) as Skeleton)
  },
  api: async ({ seed }, use) => {
    const api = new Api()
    api.setToken(seed.token)
    await use(api)
  },
  // 未捕获的 JS 异常直接判失败——能抓住「改了个 utils 导致某页白屏」这类
  // dogfooding 期最常见、且断言写不到的回归
  page: async ({ page }, use) => {
    const errors: string[] = []
    page.on('pageerror', (e) => errors.push(e.message))
    await use(page)
    expect(errors, '页面出现未捕获 JS 异常').toEqual([])
  },
})

export { expect }
