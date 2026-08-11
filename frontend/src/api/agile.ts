import http from './http'
import type { WorkItem } from './workitem'

/**
 * 迭代与测试 API 集合（agile domain）。
 * 覆盖迭代管理、测试用例与执行记录的前端服务入口。
 */

export interface Iteration {
  id: number
  code: string
  projectId: number
  name: string
  goal?: string
  startDate?: string
  endDate?: string
  status: string
  hidden?: number
}

export interface VerifiedReq {
  id: number
  code: string
  title: string
}

export interface TestCase {
  id: number
  code: string
  projectId: number
  title: string
  steps?: string
  expected?: string
  status?: 'DRAFT' | 'ACTIVE' | 'DISABLED'
  verifies?: VerifiedReq[]
}

export interface TestRun {
  id: number
  code: string
  testCaseId: number
  result: string
  actual?: string
  runVersionId?: number | null
  defectId?: number | null
  runAt: string
}

export interface RetroItem {
  id: number
  code: string
  type: string
  title: string
  status: string
  estimateSnap?: string | null
  done: boolean
  movedOut: boolean
}

export interface VelocityPoint {
  iterationId: number
  code: string
  name: string
  endDate: string
  committed: number
  done: number
}

export interface Retro {
  iteration: Iteration
  items: RetroItem[]
  committedCount: number
  doneCount: number
  spilloverCount: number
  movedOutCount: number
  completionRate: number
  velocity: VelocityPoint[]
}

/** 迭代能力 API（迭代列表、复盘、事项归属管理）。 */
export const iterationApi = {
  /** 获取某项目全部迭代，按时间回放在看板/甘特中使用。 */
  retro: (id: number) => http.get<any, Retro>(`/iterations/${id}/retro`),
  /** 分页/筛选前先拉取列表，支持项目切换与看板初始化。 */
  list: (projectId: number) => http.get<any, Iteration[]>('/iterations', { params: { projectId } }),
  /** 新建迭代（通常由项目负责人在迭代维护页发起）。 */
  create: (data: Partial<Iteration>) => http.post<any, Iteration>('/iterations', data),
  /** 更新迭代属性（目标、时间窗、状态）。 */
  update: (id: number, data: Partial<Iteration>) => http.put<any, Iteration>(`/iterations/${id}`, data),
  /** 拉取某迭代的工作项清单，用于排期和能力核算。 */
  items: (id: number) => http.get<any, WorkItem[]>(`/iterations/${id}/items`),
  /** 将工作项绑定到迭代；会触发排期/责任人的归属计算。 */
  assign: (id: number, workItemId: number) => http.post(`/iterations/${id}/assign/${workItemId}`),
  /** 从迭代中移除工作项，保留历史用于追溯。 */
  remove: (workItemId: number) => http.delete(`/iterations/items/${workItemId}`),
}

/** 测试相关 API（导入/导出、用例管理、执行提交）。 */
export const testApi = {
  /** 拉取项目下测试用例清单。 */
  listCases: (projectId: number) => http.get<any, TestCase[]>('/tests/cases', { params: { projectId } }),
  /** 下载测试用例 Excel 导入模板。 */
  importTemplateUrl: () => '/api/tests/import-template.xlsx',
  /** 生成导出 URL（供 a 标签直接下载）。 */
  exportUrl: (projectId: number) => `/api/tests/export.xlsx?projectId=${projectId}`,
  /** 上传测试用例 Excel：支持断点重传前可直接复用标准 form-data。 */
  importCases: (projectId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<any, { created: number; errors: string[] }>('/tests/import', form, {
      params: { projectId },
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  /** 新建测试用例，支持额外传入 verifiesRequirementId 做需求追溯。 */
  createCase: (data: Partial<TestCase>, verifiesRequirementId?: number) =>
    http.post<any, TestCase>('/tests/cases', data, { params: verifiesRequirementId ? { verifiesRequirementId } : {} }),
  /** 修改用例字段（标题、步骤、期望结果等）。 */
  updateCase: (id: number, data: Partial<TestCase>) => http.put<any, TestCase>(`/tests/cases/${id}`, data),
  /** 修改用例状态（DRAFT/ACTIVE/DISABLED）。 */
  changeCaseStatus: (id: number, status: string) => http.post<any, TestCase>(`/tests/cases/${id}/status`, { status }),
  /** 删除测试用例。 */
  deleteCase: (id: number) => http.delete(`/tests/cases/${id}`),
  /** 查询某用例执行历史。 */
  listRuns: (caseId: number) => http.get<any, TestRun[]>(`/tests/cases/${caseId}/runs`),
  /** 提交一次测试执行，可设置是否自动创建缺陷。 */
  execute: (data: Partial<TestRun>, autoCreateDefect = true) =>
    http.post<any, TestRun>('/tests/runs', data, { params: { autoCreateDefect } }),
}
