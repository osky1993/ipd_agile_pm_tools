import http from './http'

/** 工作项与批量操作 API：覆盖工作项 CRUD、追溯关系、导入导出与元数据。 */

export interface WorkItem {
  id: number
  code: string
  projectId: number
  productVersionId?: number | null
  type: string
  title: string
  description?: string | null
  status: string
  ownerId?: number | null
  priority?: string | null
  iterationId?: number | null
  baselineDate?: string | null
  forecastDate?: string | null
  acceptanceCriteria?: string | null
  estimate?: string | null
  acceptedBy?: number | null
  acceptedAt?: string | null
  extFields?: string | null
  createdAt?: string
}

export interface TraceView {
  linkId: number
  direction: 'OUT' | 'IN'
  relation: string
  otherType: string
  otherId: number
  otherCode: string
  otherTitle: string
}

export interface AuditEvent {
  id: number
  action: string
  actorId: number
  summary: string
  at: string
}

export interface StatusLog {
  id: number
  fromStatus: string | null
  toStatus: string
  actorId: number
  reason: string | null
  at: string
}

export const workItemApi = {
  /** 查询项目工作项（可按类型过滤）。 */
  list: (projectId: number, type?: string) =>
    http.get<any, WorkItem[]>('/work-items', { params: { projectId, type } }),
  /** 读取单条工作项详情。 */
  get: (id: number) => http.get<any, WorkItem>(`/work-items/${id}`),
  /** 创建工作项（需求/缺陷/任务/...）。 */
  create: (data: Partial<WorkItem>) => http.post<any, WorkItem>('/work-items', data),
  /** 更新工作项属性。 */
  update: (id: number, data: Partial<WorkItem>) => http.put<any, WorkItem>(`/work-items/${id}`, data),
  /** 提交流转动作，记录原因用于状态历史。 */
  transition: (id: number, toStatus: string, reason?: string) =>
    http.post<any, WorkItem>(`/work-items/${id}/transition`, { toStatus, reason }),
  /** 获取可达下一个状态列表（状态机驱动）。 */
  nextStatuses: (id: number) => http.get<any, string[]>(`/work-items/${id}/next-statuses`),
  /** 查询单条工作项状态历史。 */
  statusHistory: (id: number) => http.get<any, StatusLog[]>(`/work-items/${id}/status-history`),
  /** 查询工作项审计日志（操作与时间轴）。 */
  audit: (id: number) => http.get<any, AuditEvent[]>(`/work-items/${id}/audit`),
  /** 查询追溯关系。 */
  traces: (id: number) => http.get<any, TraceView[]>(`/work-items/${id}/traces`),
  /** 模糊搜索工作项（code/title）。 */
  search: (q: string) => http.get<any, WorkItem[]>('/work-items/search', { params: { q } }),
  /** 导入树形结构模板下载 URL。 */
  treeTemplateUrl: () => '/api/work-items/import-template.xlsx',
  /** 上传树形结构 Excel（父子关系导入）。 */
  importTree: (projectId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<any, { created: number; errors: string[] }>('/work-items/import-tree', form, {
      params: { projectId },
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  /** 上传扁平化 CSV / XLS 目录导入。 */
  importCsv: (projectId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<any, { created: number; errors: string[] }>('/work-items/import', form, {
      params: { projectId },
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}

export interface BatchItemResult {
  id: number
  code: string | null
  ok: boolean
  message: string
}

export const batchApi = {
  /** 批量操作：TRANSITION | UPDATE | ASSIGN_ITERATION，逐条返回成败 */
  execute: (req: {
    ids: number[]
    action: 'TRANSITION' | 'UPDATE' | 'ASSIGN_ITERATION'
    toStatus?: string
    reason?: string
    iterationId?: number
    patch?: { ownerId?: number | null; priority?: string | null }
    dryRun?: boolean
  }) => http.post<any, BatchItemResult[]>('/work-items/batch', req),
}

export const riskApi = {
  /** 风险任务化：按处置措施生成应对 TASK（TASK -affects→ RISK） */
  mitigationTask: (riskId: number) => http.post<any, WorkItem>(`/risks/${riskId}/mitigation-task`),
}

export const riskChangeExcelApi = {
  /** 风险/变更导入模板 URL；type 控制模板字段。 */
  templateUrl: (type: 'RISK' | 'CHANGE') => `/api/work-items/import-excel-template.xlsx?type=${type}`,
  /** 批量导入风险/变更（返回逐条成功与失败）。 */
  importExcel: (projectId: number, type: 'RISK' | 'CHANGE', file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<any, { created: number; errors: string[] }>('/work-items/import-excel', form, {
      params: { projectId, type },
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}

export const traceApi = {
  /** 新建追溯关系。 */
  create: (data: {
    projectId: number
    sourceType: string
    sourceId: number
    targetType: string
    targetId: number
    relation: string
  }) => http.post('/traces', data),
  /** 删除追溯关系。 */
  delete: (id: number) => http.delete(`/traces/${id}`),
}

export const metaApi = {
  /** 工作项类型字典。 */
  workItemTypes: () => http.get<any, { value: string; abbr: string; label: string }[]>('/meta/work-item-types'),
  /** 追溯关系字典。 */
  traceRelations: () => http.get<any, string[]>('/meta/trace-relations'),
}
