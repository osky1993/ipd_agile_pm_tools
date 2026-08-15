import { Api, dayOffset } from './api'

/**
 * 夹具造数：只造「前置数据」，被保护的那几步一律留给 UI。
 * 端点与请求体全部沿用 deploy/e2e_demo.sh 里已验证过的调用。
 */

export interface Skeleton {
  projectId: number
  projectCode: string
  versionId: number
  userId: number
  displayName: string
  roles: string[]
  token: string
}

export const PROJECT_CODE = 'E2E1'

/** 全局骨架：项目 + 产品版本。globalSetup 调用一次。 */
export async function seedSkeleton(api: Api): Promise<Skeleton> {
  const auth = await api.login()

  const project = await api.post<{ id: number; code: string }>('/projects', {
    code: PROJECT_CODE,
    name: 'E2E 冒烟项目',
    goal: 'e2e 薄保护网（docs/07 §1 A1）',
  })
  const version = await api.post<{ id: number }>('/product-versions', {
    projectId: project.id,
    versionNo: 'V1.0',
  })

  return {
    projectId: project.id,
    projectCode: project.code,
    versionId: version.id,
    userId: auth.userId,
    displayName: auth.displayName,
    roles: auth.roles,
    token: auth.token,
  }
}

interface WorkItem {
  id: number
  code: string
  status: string
}

/** 建工作项。 */
export function createItem(api: Api, projectId: number, body: Record<string, unknown>) {
  return api.post<WorkItem>('/work-items', { projectId, ...body })
}

/** 补齐 Ready 三要素（验收条件 / 责任人 / 估算）。 */
export function fillReady(api: Api, id: number, ownerId: number) {
  return api.put(`/work-items/${id}`, {
    acceptanceCriteria: 'e2e 验收条件',
    ownerId,
    estimate: '3',
  })
}

/** 顺序流转到目标状态。 */
export async function transitionTo(api: Api, id: number, ...statuses: string[]) {
  for (const toStatus of statuses) {
    await api.post(`/work-items/${id}/transition`, { toStatus })
  }
}

/**
 * 动线 1 的四栏数据：进行中 / 已超期 / 待复测缺陷 / 临近迭代。
 * 断言依赖「四栏都非空」，所以这里必须四样齐备。
 */
export async function seedMyToday(api: Api, s: Skeleton) {
  // 进行中：Backlog → Ready → In Progress（Ready 有守卫，先补齐三要素）
  const doing = await createItem(api, s.projectId, {
    type: 'REQUIREMENT',
    title: '[E2E] 进行中的需求',
    ownerId: s.userId,
  })
  await fillReady(api, doing.id, s.userId)
  await transitionTo(api, doing.id, 'Ready', 'In Progress')

  // 已超期：forecastDate 设为昨天即可（collectMine 只排除终态，不看状态）
  const late = await createItem(api, s.projectId, {
    type: 'TASK',
    title: '[E2E] 超期的任务',
    ownerId: s.userId,
  })
  await api.put(`/work-items/${late.id}`, { forecastDate: dayOffset(-1) })

  // 待复测缺陷：DEFECT 走到 Retesting（这条链上无守卫，DefectCloseGuard 只管 Closed）
  const defect = await createItem(api, s.projectId, {
    type: 'DEFECT',
    title: '[E2E] 待复测缺陷',
    ownerId: s.userId,
  })
  await transitionTo(api, defect.id, 'Analysing', 'Fixing', 'Retesting')

  // 临近迭代：必须显式 status=ACTIVE，否则默认 PLANNING，MyService.collectIterations 扫不到
  const iteration = await api.post<{ id: number; code: string }>('/iterations', {
    projectId: s.projectId,
    name: 'E2E-Sprint-1',
    goal: '冒烟',
    startDate: dayOffset(-3),
    endDate: dayOffset(3),
    status: 'ACTIVE',
  })
  // 迭代卡片显示"我的在办数"，挂一条进去让它非零
  await api.post(`/iterations/${iteration.id}/assign/${doing.id}`)

  return { doing, late, defect, iteration }
}

/** 动线 2 的前置：一个 ACTIVE 迭代（看板要有迭代才能拉入）。 */
export function seedBoardIteration(api: Api, s: Skeleton) {
  return api.post<{ id: number; name: string }>('/iterations', {
    projectId: s.projectId,
    name: 'E2E-Sprint-2',
    goal: '动线 2',
    startDate: dayOffset(-1),
    endDate: dayOffset(10),
    status: 'ACTIVE',
  })
}

/** 动线 3 的前置：一个阶段/DCP + 一条遗留风险（有条件通过必须绑定）。 */
export async function seedDcp(api: Api, s: Skeleton) {
  const gate = await api.post<{ id: number; code: string; gateName: string }>('/stage-gates', {
    projectId: s.projectId,
    stageName: '计划',
    gateName: 'DCP1',
    seq: 1,
  })
  const risk = await createItem(api, s.projectId, {
    type: 'RISK',
    title: '[E2E] 遗留风险',
    ownerId: s.userId,
    extFields: JSON.stringify({ mitigation: '锁定备选方案', dueDate: dayOffset(30) }),
  })
  return { gate, risk }
}
