import http from './http'

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
  list: (projectId: number, type?: string) =>
    http.get<any, WorkItem[]>('/work-items', { params: { projectId, type } }),
  get: (id: number) => http.get<any, WorkItem>(`/work-items/${id}`),
  create: (data: Partial<WorkItem>) => http.post<any, WorkItem>('/work-items', data),
  update: (id: number, data: Partial<WorkItem>) => http.put<any, WorkItem>(`/work-items/${id}`, data),
  transition: (id: number, toStatus: string, reason?: string) =>
    http.post<any, WorkItem>(`/work-items/${id}/transition`, { toStatus, reason }),
  nextStatuses: (id: number) => http.get<any, string[]>(`/work-items/${id}/next-statuses`),
  statusHistory: (id: number) => http.get<any, StatusLog[]>(`/work-items/${id}/status-history`),
  audit: (id: number) => http.get<any, AuditEvent[]>(`/work-items/${id}/audit`),
  traces: (id: number) => http.get<any, TraceView[]>(`/work-items/${id}/traces`),
  search: (q: string) => http.get<any, WorkItem[]>('/work-items/search', { params: { q } }),
  treeTemplateUrl: () => '/api/work-items/import-template.xlsx',
  importTree: (projectId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<any, { created: number; errors: string[] }>('/work-items/import-tree', form, {
      params: { projectId },
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
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
  }) => http.post<any, BatchItemResult[]>('/work-items/batch', req),
}

export const riskChangeExcelApi = {
  templateUrl: (type: 'RISK' | 'CHANGE') => `/api/work-items/import-excel-template.xlsx?type=${type}`,
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
  create: (data: {
    projectId: number
    sourceType: string
    sourceId: number
    targetType: string
    targetId: number
    relation: string
  }) => http.post('/traces', data),
  delete: (id: number) => http.delete(`/traces/${id}`),
}

export const metaApi = {
  workItemTypes: () => http.get<any, { value: string; abbr: string; label: string }[]>('/meta/work-item-types'),
  traceRelations: () => http.get<any, string[]>('/meta/trace-relations'),
}
