import http from './http'

/** 基线 API：基线清单、明细与差异对比入口。 */

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

/** 基线管理接口：查看历史快照、对比偏差用于治理台。 */
export const baselineApi = {
  /** 查询项目的基线列表。 */
  list: (projectId: number) => http.get<any, Baseline[]>('/baselines', { params: { projectId } }),
  /** 拉取某条基线详情及其明细条目。 */
  get: (id: number) => http.get<any, { baseline: Baseline; items: BaselineItem[] }>(`/baselines/${id}`),
  /** 对比当前项目状态与基线，返回新增/移除/延期等偏差。 */
  diff: (id: number) => http.get<any, Diff>(`/baselines/${id}/diff`),
  /** 手工创建基线（通常用于阶段性冻结）。 */
  create: (projectId: number, name?: string) => http.post<any, Baseline>('/baselines', { projectId, name }),
}
