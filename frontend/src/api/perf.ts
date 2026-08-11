import http from './http'

/** 性能域 API：指标快照、趋势、改进行为和告警。 */

export interface Metric {
  key: string
  name: string
  level: number
  parent: string | null
  unit: string
  direction: 'higher' | 'lower'
  value: number | null
  target: number | null
  status: 'good' | 'warn' | 'none'
}

export interface MetricGroup {
  key: string
  name: string
  metrics: Metric[]
}

export interface WeekPoint {
  weekStart: string
  count: number
}

export interface TypeCount {
  type: string
  label: string
  count: number
}

export interface StageStay {
  stage: string
  avgDays: number | null
  samples: number
}

export interface StaleItem {
  id: number
  code: string
  title: string
  status: string
  days: number
}

export interface PerfOverview {
  groups: MetricGroup[]
  charts: {
    weeklyThroughput: WeekPoint[]
    throughputByType: TypeCount[]
    stageDurations: StageStay[]
    staleTop: StaleItem[]
  }
}

export interface Improvement {
  id: number
  projectId: number
  code: string
  metricKey: string | null
  title: string
  measure?: string
  baselineValue: number | null
  targetValue: number | null
  resultValue: number | null
  conclusion?: string
  dueDate?: string | null
  status: 'OPEN' | 'DOING' | 'DONE' | 'VERIFIED'
  createdAt?: string
}

export interface Alert {
  severity: 'HIGH' | 'MED' | 'LOW'
  type: string
  title: string
  detail: string
  refType: string
  refId: number
  refCode: string
  due: string | null
}

export interface TrendPoint {
  date: string
  value: number | null
}

export interface CfdPoint {
  date: string
  byStatus: Record<string, number>
}

export const perfApi = {
  /** 获取项目性能总览（吞吐、流转、滞留 top）。 */
  metrics: (projectId: number) => http.get<any, PerfOverview>('/perf/metrics', { params: { projectId } }),
  /** 读取关键指标趋势（默认 60 天）。 */
  trends: (projectId: number, days = 60) =>
    http.get<any, Record<string, TrendPoint[]>>('/perf/trends', { params: { projectId, days } }),
  /** 查询累积流图（按状态累计统计）。 */
  cfd: (projectId: number, days = 56) =>
    http.get<any, CfdPoint[]>('/perf/cfd', { params: { projectId, days } }),
  /** 设置指标目标值（支持清空目标）。 */
  setTarget: (projectId: number, metricKey: string, targetValue: number | null) =>
    http.put<any, Metric>('/perf/target', { projectId, metricKey, targetValue }),
  /** 查询改进行动（支持按状态筛选）。 */
  improvements: (projectId: number, status?: string) =>
    http.get<any, Improvement[]>('/perf/improvements', { params: { projectId, status } }),
  /** 新建改进项。 */
  createImprovement: (data: Partial<Improvement>) =>
    http.post<any, Improvement>('/perf/improvements', data),
  /** 更新改进项。 */
  updateImprovement: (id: number, data: Partial<Improvement>) =>
    http.put<any, Improvement>(`/perf/improvements/${id}`, data),
  /** 改进项状态流转（可记录实际结果与结论）。 */
  transitionImprovement: (id: number, toStatus: string, resultValue?: number | null, conclusion?: string) =>
    http.post<any, Improvement>(`/perf/improvements/${id}/transition`, { toStatus, resultValue, conclusion }),
  /** 删除改进项（用于废弃条目）。 */
  deleteImprovement: (id: number) => http.delete(`/perf/improvements/${id}`),
}

export const alertApi = {
  /** 查询项目告警列表（高/中/低）。 */
  list: (projectId: number) => http.get<any, Alert[]>('/alerts', { params: { projectId } }),
}
