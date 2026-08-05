import http from './http'

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
  compare: (projectId: number, from: string, to: string) =>
    http.get<any, Compare>('/timemachine/compare', { params: { projectId, from, to } }),
  timeline: (projectId: number) =>
    http.get<any, Timeline>('/timemachine/timeline', { params: { projectId } }),
  asOf: (projectId: number, date: string) =>
    http.get<any, AsOf>('/timemachine/as-of', { params: { projectId, date } }),
}
