import http from './http'

export interface Baseline {
  id: number
  projectId: number
  name: string
  source: 'DCP' | 'MANUAL'
  stageGateId?: number | null
  decisionId?: number | null
  itemCount: number
  createdBy?: number | null
  createdAt: string
}

export interface BaselineItem {
  id: number
  baselineId: number
  workItemId: number
  code: string
  title: string
  type: string
  status: string
  estimate?: string | null
  plannedDate?: string | null
}

export interface DiffRow {
  workItemId: number
  code: string
  title: string
  type: string
  kind: 'ADDED' | 'REMOVED' | 'DONE' | 'OPEN'
  baselineStatus?: string | null
  currentStatus?: string | null
  plannedDate?: string | null
  forecastDate?: string | null
  slipDays?: number | null
  baselineEstimate?: string | null
  currentEstimate?: string | null
  estimateDelta?: number | null
}

export interface DiffSummary {
  baselineCount: number
  currentCount: number
  added: number
  removed: number
  done: number
  creepRate: number
  avgSlipDays?: number | null
  maxSlipDays?: number | null
  estimateDeltaTotal: number
}

export interface Diff {
  baseline: Baseline
  summary: DiffSummary
  rows: DiffRow[]
}

export const baselineApi = {
  list: (projectId: number) => http.get<any, Baseline[]>('/baselines', { params: { projectId } }),
  get: (id: number) => http.get<any, { baseline: Baseline; items: BaselineItem[] }>(`/baselines/${id}`),
  diff: (id: number) => http.get<any, Diff>(`/baselines/${id}/diff`),
  create: (projectId: number, name?: string) => http.post<any, Baseline>('/baselines', { projectId, name }),
}
