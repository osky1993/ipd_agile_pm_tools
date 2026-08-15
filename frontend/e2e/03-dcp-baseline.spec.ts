import { readFileSync } from 'node:fs'
import { Api, dayOffset } from './support/api'
import { seedDcp, type Skeleton } from './support/seed'
import { SEED_FILE } from './global-setup'
import {
  test,
  expect,
  SEL,
  GUARD,
  expectToast,
  selectOption,
  waitProjectReady,
} from './support/fixtures'

/**
 * 动线 3：铺条件模板 → 上传证据 → 发起评审 → 决策落库 + 基线自动固化。
 *
 * 为什么走「先判通过被红线拦，再判有条件通过」而不是把红线全刷成已满足：
 *   - 红线校验只作用于 PASS（DcpService.review），CONDITIONAL 同样会落决策**并固化基线**；
 *   - 这样一次就拿到 守卫拦截 + 决策落库 + 基线固化 三个断言，
 *     且不依赖「6 条条件全部刷成 MET」（还要连带担心跨职能准备度红线），天然更稳。
 */

// 模板下拉的选项文案是「概念决策（Charter/DCP1）（6条）」，
// 阶段下拉在管理抽屉里是「计划/DCP1」、在 DCP 页是「计划 / DCP1」（带空格）——两处写法不同，别混用
const TEMPLATE_LABEL = '概念决策'
const GATE_OPTION_IN_DRAWER = '计划/DCP1'
const GATE_LABEL = '计划 / DCP1'
// DCP1 模板固定 6 条，其中 2 条红线（CriterionTemplateService.TEMPLATES）
const TEMPLATE_ITEMS = 6
const TEMPLATE_REDLINES = 2

let gateCode = ''
let riskId = 0

test.beforeAll(async () => {
  const skeleton = JSON.parse(readFileSync(SEED_FILE, 'utf-8')) as Skeleton
  const api = new Api()
  api.setToken(skeleton.token)
  const { gate, risk } = await seedDcp(api, skeleton)
  gateCode = gate.code
  riskId = risk.id
})

test('铺模板 → 传证据 → 红线拦截 → 有条件通过 → 决策与基线固化', async ({ page }) => {
  // ---- 1. 在项目管理抽屉里一键铺条件 ----
  await page.goto('/projects')
  await expect(page.locator(SEL.loadingMask)).toHaveCount(0)

  await page
    .locator('.el-table__row')
    .filter({ hasText: 'E2E 冒烟项目' })
    .getByRole('button', { name: '管理' })
    .click()
  const manage = page.locator(SEL.drawer)
  await expect(manage).toBeVisible()
  await manage.getByRole('tab', { name: /阶段\/DCP/ }).click()

  await manage.locator('.el-select').filter({ hasText: '选择条件模板' }).click()
  await selectOption(page, TEMPLATE_LABEL).first().click()
  await manage.locator('.el-select').filter({ hasText: '目标阶段/DCP' }).click()
  await selectOption(page, GATE_OPTION_IN_DRAWER).first().click()

  await manage.getByRole('button', { name: '一键铺条件' }).click()
  await expectToast(page, 'success', new RegExp(`^已铺 ${TEMPLATE_ITEMS} 条`))

  await page.keyboard.press('Escape')
  await expect(manage).toBeHidden()

  // ---- 2. DCP 页：条件已就位，红线未满足数符合模板定义 ----
  await page.goto('/dcp')
  await waitProjectReady(page)
  await page.locator('.toolbar .el-select').first().click()
  await selectOption(page, GATE_LABEL).first().click()
  await expect(page.locator(SEL.loadingMask)).toHaveCount(0)

  const criteria = page.locator('.el-table').first().locator('.el-table__row')
  await expect(criteria).toHaveCount(TEMPLATE_ITEMS)
  await expect(page.locator('.kpi-num').first()).toHaveText(String(TEMPLATE_REDLINES))

  // ---- 3. 上传证据（隐藏的原生 file input，直接 setInputFiles） ----
  await criteria.first().locator(SEL.hiddenFile).setInputFiles({
    name: 'e2e-evidence.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('e2e 证据文件'),
  })
  await expectToast(page, 'success', '已上传证据并关联')
  await expect(criteria.first()).toContainText('1 份')

  // ---- 4. 守卫断言：红线未满足时判「通过」被拦 ----
  await page.getByRole('button', { name: '发起 DCP 评审' }).click()
  const dialog = page.locator(SEL.dialog).filter({ hasText: '发起 DCP 评审' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.el-alert')).toContainText('存在红线未满足项')

  // el-radio-button 的原生 input 被 .el-radio-button__inner 遮挡，点可见的那层；
  // 且「通过」是「有条件通过」的子串，必须用 :text-is 精确匹配
  await dialog.locator('.el-radio-button__inner:text-is("通过")').click()
  await dialog.getByRole('button', { name: '提交决策' }).click()
  await expectToast(page, 'error', GUARD.redlineUnmet)
  // 抛异常时 reviewDialog=false 那行不会执行，对话框保持打开、用户输入不丢
  await expect(dialog).toBeVisible()

  // ---- 5. 改判「有条件通过」：必须绑定遗留风险与完成期限 ----
  const due = dayOffset(30)
  await dialog.locator('.el-radio-button__inner:text-is("有条件通过")').click()
  await dialog.locator('.el-form-item').filter({ hasText: '遗留风险' }).locator('.el-select').click()
  await selectOption(page, '[E2E] 遗留风险').first().click()
  await dialog.locator('.el-form-item').filter({ hasText: '完成期限' }).locator('input').fill(due)
  await page.keyboard.press('Enter')
  await dialog.getByRole('button', { name: '提交决策' }).click()
  await expectToast(page, 'success', '评审决策已记录')
  await expect(dialog).toBeHidden()

  // ---- 6. 决策落库（只增不改） ----
  const decisions = page.locator('.el-table').nth(1).locator('.el-table__row')
  await expect(decisions.first()).toContainText('有条件通过')
  await expect(decisions.first()).toContainText(`#${riskId}`)
  await expect(decisions.first()).toContainText(due)

  await decisions.first().getByRole('button', { name: '查看快照' }).click()
  await expect(page.locator(SEL.dialog).filter({ hasText: '决策时固化的准备度快照' })).toBeVisible()
  await page.keyboard.press('Escape')

  // ---- 7. 基线自动固化：B-{gateCode}，来源标记为 DCP 固化 ----
  await page.goto('/baseline')
  await waitProjectReady(page)
  await expect(page.getByText('尚无基线。DCP 评审通过时会自动固化，也可手动建立。')).toHaveCount(0)
  const baseline = page.locator('.bl-item').filter({ hasText: `B-${gateCode}` })
  await expect(baseline).toBeVisible()
  await expect(baseline.locator('.bl-name')).toContainText('DCP 固化')
})
