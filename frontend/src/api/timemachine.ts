import http from './http'

/** 时间机器 API：事件时间线、快照对比、时点投影。 */

export interface EventPoint {
  date: string
  kind: 'DECISION' | 'BASELINE' | 'ITER_START' | 'ITER_END'
  label: string
  refId: number
}

export interface Timeline {
  start: string
  end: string
  events: EventPoint[]
}

export interface AsOfItem {
  id: number
  code: string
  type: string
  title: string
  statusAtDate: string
  deletedNow: boolean
}

export interface DayEvent {
  kind: 'CREATE' | 'TRANSITION' | 'DECISION' | 'BASELINE' | 'EVIDENCE'
  text: string
}

export interface AsOf {
  date: string
  reqTotal: number
  reqAccepted: number
  defectsOpen: number
  wip: number
  risksOpen: number
  decisionCount: number
  evidenceCount: number
  byTypeStatus: Record<string, Record<string, number>>
  items: AsOfItem[]
  dayEvents: DayEvent[]
}

export interface CompareRow {
  id: number
  code: string
  type: string
  title: string
  statusFrom?: string | null
  statusTo: string
  kind: 'NEW' | 'COMPLETED' | 'CHANGED' | 'UNCHANGED'
}

export interface Kpis {
  reqTotal: number
  reqAccepted: number
  defectsOpen: number
  wip: number
  risksOpen: number
}

export interface Compare {
  from: string
  to: string
  kpisFrom: Kpis
  kpisTo: Kpis
  added: number
  completed: number
  changed: number
  unchanged: number
  transitionCount: number
  rows: CompareRow[]
  periodEvents: DayEvent[]
}

export const timeMachineApi = {
  /** 对比两时点的 KPI 与状态变更（支持回归评审）。 */
  compare: (projectId: number, from: string, to: string) =>
    http.get<any, Compare>('/timemachine/compare', { params: { projectId, from, to } }),
  /** 拉取项目关键事件时间线（决策、基线、迭代里程）。 */
  timeline: (projectId: number) =>
    http.get<any, Timeline>('/timemachine/timeline', { params: { projectId } }),
  /** 按时间点回放当日状态（含类型分布与日内事件）。 */
  asOf: (projectId: number, date: string) =>
    http.get<any, AsOf>('/timemachine/as-of', { params: { projectId, date } }),
}
