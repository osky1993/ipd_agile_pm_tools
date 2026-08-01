import http from './http'

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
  metrics: (projectId: number) => http.get<any, PerfOverview>('/perf/metrics', { params: { projectId } }),
  trends: (projectId: number, days = 60) =>
    http.get<any, Record<string, TrendPoint[]>>('/perf/trends', { params: { projectId, days } }),
  cfd: (projectId: number, days = 56) =>
    http.get<any, CfdPoint[]>('/perf/cfd', { params: { projectId, days } }),
  setTarget: (projectId: number, metricKey: string, targetValue: number | null) =>
    http.put<any, Metric>('/perf/target', { projectId, metricKey, targetValue }),
  improvements: (projectId: number, status?: string) =>
    http.get<any, Improvement[]>('/perf/improvements', { params: { projectId, status } }),
  createImprovement: (data: Partial<Improvement>) =>
    http.post<any, Improvement>('/perf/improvements', data),
  updateImprovement: (id: number, data: Partial<Improvement>) =>
    http.put<any, Improvement>(`/perf/improvements/${id}`, data),
  transitionImprovement: (id: number, toStatus: string, resultValue?: number | null, conclusion?: string) =>
    http.post<any, Improvement>(`/perf/improvements/${id}/transition`, { toStatus, resultValue, conclusion }),
  deleteImprovement: (id: number) => http.delete(`/perf/improvements/${id}`),
}

export const alertApi = {
  list: (projectId: number) => http.get<any, Alert[]>('/alerts', { params: { projectId } }),
}
