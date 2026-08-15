import { readFileSync } from 'node:fs'
import { Api } from './support/api'
import { seedMyToday, type Skeleton } from './support/seed'
import { SEED_FILE } from './global-setup'
import { test, expect, SEL, MY_COLUMNS, expectToast } from './support/fixtures'

/**
 * 动线 1：登录 →「我的一天」正常聚合渲染（默认首页可用性）。
 *
 * 这是唯一走真实 UI 登录的用例，所以覆盖掉全局的免登录 storageState。
 */
test.use({ storageState: { cookies: [], origins: [] } })

test.beforeAll(async () => {
  const skeleton = JSON.parse(readFileSync(SEED_FILE, 'utf-8')) as Skeleton
  const api = new Api()
  api.setToken(skeleton.token)
  await seedMyToday(api, skeleton)
})

test('登录后「我的一天」四栏聚合正常渲染', async ({ page }) => {
  // 1. 未登录访问受保护页面 → 被路由守卫弹回登录页
  await page.goto('/my')
  await expect(page).toHaveURL(/\/login$/)

  // 2. 表单预填了演示账号
  const username = page.locator('.el-input__inner').first()
  await expect(username).toHaveValue('admin')

  // 3. 登录
  await page.getByRole('button', { name: '登录' }).click()
  await expectToast(page, 'success', '登录成功')
  await expect(page).toHaveURL(/\/dashboard$/)
  expect(await page.evaluate(() => localStorage.getItem('token'))).toBeTruthy()

  // 4. 进「我的一天」
  await page.goto('/my')
  await expect(page.locator(SEL.loadingMask)).toHaveCount(0)

  // 5. 四栏标题齐全
  const cols = page.locator('section.col')
  await expect(cols).toHaveCount(MY_COLUMNS.length)
  for (const [i, col] of MY_COLUMNS.entries()) {
    await expect(cols.nth(i).locator('h3')).toContainText(col.title)
  }

  // 6. 四栏各自的内容确实渲染出来了
  await expect(cols.nth(0)).toContainText('[E2E] 进行中的需求')
  await expect(cols.nth(1)).toContainText('[E2E] 超期的任务')
  await expect(cols.nth(2)).toContainText('[E2E] 待复测缺陷')
  await expect(cols.nth(2)).toContainText('复测中')
  await expect(cols.nth(3)).toContainText('E2E-Sprint-1')

  // 7. 反向断言（核心）：四条空态文案一个都不该出现。
  //    这一条防的是「聚合接口挂了但页面静默空白」——最典型的聚合回归。
  for (const col of MY_COLUMNS) {
    await expect(page.getByText(col.empty, { exact: true })).toHaveCount(0)
  }

  // 8. 全程无错误 toast
  await expect(page.locator(SEL.toastError)).toHaveCount(0)
})

test('今日焦点可置顶与取消', async ({ page }) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '登录' }).click()
  await expectToast(page, 'success', '登录成功')

  await page.goto('/my')
  await expect(page.locator(SEL.loadingMask)).toHaveCount(0)

  const firstCard = page.locator('section.col').first().locator('.card').first()
  await firstCard.locator('.pin').click()
  await expect(page.locator('.focus-zone')).toBeVisible()
  await expect(page.locator('.zone-title')).toContainText('今日焦点')

  // 再点一次取消置顶（焦点区随之消失）
  await page.locator('.focus-zone .card').first().locator('.pin').click()
  await expect(page.locator('.focus-zone')).toHaveCount(0)
})
