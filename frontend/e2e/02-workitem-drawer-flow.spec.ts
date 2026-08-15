import { readFileSync } from 'node:fs'
import { Api } from './support/api'
import { seedBoardIteration, type Skeleton } from './support/seed'
import { SEED_FILE } from './global-setup'
import {
  test,
  expect,
  SEL,
  GUARD,
  formInput,
  expectToast,
  waitProjectReady,
} from './support/fixtures'

/**
 * 动线 2：建需求 → 拉入迭代 → 经 WorkItemDrawer 抽屉走状态流转 + 守卫拦截断言。
 *
 * 看板拖拽刻意不做 e2e（HTML5 原生拖拽合成鼠标事件触发不了，见 docs/07 §1 A1 已知坑），
 * 一律走抽屉流转替代——onDrop 与 doTransition 走的是同一个 transition 接口，业务逻辑已覆盖。
 *
 * 本条动线有**两个**守卫断言点，走的是两条独立代码路径：
 *   A. 拉入迭代    → IterationService.checkAssignable
 *   B. 流转 Ready  → statemachine/guards/ReadyGuard
 * 顺序必须是「先拦截 → 后补齐」：看板 backlog 只显示 `!iterationId && status==='Backlog'` 的项，
 * 一旦流转到 Ready，卡片就从 backlog 消失了。
 */

const SPRINT_NAME = 'E2E-Sprint-2'
let code = ''

test.beforeAll(async () => {
  const skeleton = JSON.parse(readFileSync(SEED_FILE, 'utf-8')) as Skeleton
  const api = new Api()
  api.setToken(skeleton.token)
  await seedBoardIteration(api, skeleton)
})

test('建需求 → 守卫两次拦截 → 补齐后拉入迭代并流转', async ({ page, seed }) => {
  // ---- 1. 在工作项清单建一条需求 ----
  await page.goto('/workitems')
  await waitProjectReady(page)

  await page.getByRole('button', { name: '新建工作项' }).click()
  const createDialog = page.locator(SEL.dialog).filter({ hasText: '新建工作项' })
  await expect(createDialog).toBeVisible()
  await createDialog.getByPlaceholder('工作项标题').fill('[E2E] 抽屉流转需求')
  await createDialog.getByRole('button', { name: '创建并打开' }).click()

  // toast 形如「已创建 E2E1-REQ-002」，从中取出编号供后续定位
  const toast = page.locator(`${SEL.toastSuccess} ${SEL.toastContent}`).first()
  await expect(toast).toContainText(/^已创建 /)
  code = ((await toast.textContent()) ?? '').replace('已创建', '').trim()
  expect(code).toMatch(/REQ/)
  await expect(page.locator(SEL.toastSuccess)).toHaveCount(0, { timeout: 10_000 })

  // 创建后抽屉自动打开
  const drawer = page.locator(SEL.drawer)
  await expect(drawer).toBeVisible()
  await expect(drawer).toContainText(code)

  // ---- 2. 守卫断言 B：未填验收条件就流转 Ready ----
  await drawer.locator(SEL.statusActions).getByRole('button', { name: '就绪' }).click()
  await expectToast(page, 'error', GUARD.readyUnmetAcceptance)
  // 状态没变
  await expect(drawer.locator('.head .el-tag').first()).toHaveText('待办')

  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()

  // ---- 3. 守卫断言 A：未达 Ready 条件就拉入迭代 ----
  await page.goto('/board')
  await waitProjectReady(page)
  await page.locator('.sprint-chip').filter({ hasText: SPRINT_NAME }).click()
  await expect(page.locator('.sprint-chip.active')).toContainText(SPRINT_NAME)

  const backlogCard = page.locator('.backlog .card').filter({ hasText: code })
  await expect(backlogCard).toBeVisible()
  await backlogCard.getByRole('button', { name: /拉入迭代/ }).click()
  await expectToast(page, 'error', GUARD.readyUnmetAssign)

  // ---- 4. 在抽屉里补齐 Ready 三要素 ----
  await backlogCard.locator('.card-title').click()
  await expect(drawer).toBeVisible()

  // 说明与验收条件共用一个「编辑」开关，点开后两个 MarkdownEditor 一起出现；
  // .md-textarea 的顺序是「说明在前、验收条件在后」，故取 nth(1)。
  await drawer.getByRole('button', { name: '编辑' }).click()
  const textareas = drawer.locator(SEL.mdTextarea)
  await expect(textareas).toHaveCount(2)
  await textareas.nth(1).fill('门未关闭时远程启动被拒绝')

  await formInput(page, '估算').fill('3')
  await drawer
    .locator('.el-form-item:has(> .el-form-item__label:text-is("责任人")) .el-select')
    .click()
  await page.locator('.el-select-dropdown__item').filter({ hasText: seed.displayName }).first().click()

  await drawer.getByRole('button', { name: '保存' }).click()
  await expectToast(page, 'success', '已保存')
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()

  // ---- 5. 补齐后拉入迭代成功 ----
  await page.reload()
  await waitProjectReady(page)
  await page.locator('.sprint-chip').filter({ hasText: SPRINT_NAME }).click()
  const card2 = page.locator('.backlog .card').filter({ hasText: code })
  await card2.getByRole('button', { name: /拉入迭代/ }).click()
  await expectToast(page, 'success', '已拉入迭代')
  // 从 backlog 消失，落到状态泳道
  await expect(page.locator('.backlog .card').filter({ hasText: code })).toHaveCount(0)

  // ---- 6. 抽屉流转两步：待办 → 就绪 → 进行中 ----
  const laneCard = page.locator('.column .card').filter({ hasText: code })
  await expect(laneCard).toBeVisible()
  await laneCard.locator('.card-title').click()
  await expect(drawer).toBeVisible()

  await drawer.locator(SEL.statusActions).getByRole('button', { name: '就绪' }).click()
  await expectToast(page, 'success', '状态已更新为 Ready')
  await expect(drawer.locator('.head .el-tag').first()).toHaveText('就绪')

  await drawer.locator(SEL.statusActions).getByRole('button', { name: '进行中' }).click()
  await expectToast(page, 'success', '状态已更新为 In Progress')
  await expect(drawer.locator('.head .el-tag').first()).toHaveText('进行中')

  // ---- 7. 审计留痕（动作与状态均为中文——A5 汉化后的契约）----
  await drawer.getByRole('tab', { name: /审计/ }).click()
  await expect(drawer).toContainText('状态变更')
  await expect(drawer).toContainText(`${code} 状态 待办 → 就绪`)
  await expect(drawer).toContainText(`${code} 状态 就绪 → 进行中`)
})
