import http from './http'

/** DCP 执行域 API：门禁概览、复审决策和条件维护。 */

export interface CriterionView {
  id: number
  code: string
  domain: string
  criterion: string
  ownerId?: number | null
  status: string
  isRedline: number
  evidenceReq?: string | null
  evidenceCount: number
  linkedRiskId?: number | null
}

export interface DomainStat {
  total: number
  met: number
  partial: number
  notReady: number
  waived: number
}

export interface Snapshot {
  byDomain: Record<string, DomainStat>
  redlineUnmet: string[]
  evidenceMissing: string[]
  ownerMissing: string[]
  pending: number
}

export interface Overview {
  criteria: CriterionView[]
  snapshot: Snapshot
}

export const dcpApi = {
  /** 查询某门禁阶段的 DCP 概览（按域统计 + 未满足项）。 */
  overview: (stageGateId: number) => http.get<any, Overview>(`/dcp/gates/${stageGateId}/overview`),
  /** 提交决策复审（通过、退回、补充风控原因）。 */
  review: (stageGateId: number, body: { conclusion: string; reason: string; linkedRiskId?: number; commitmentDue?: string }) =>
    http.post<any, any>(`/dcp/gates/${stageGateId}/review`, body),
  /** 更新门禁条件本体（状态、证据、文本）。 */
  updateCriterion: (id: number, data: Record<string, unknown>) => http.put<any, any>(`/gate-criteria/${id}`, data),
}
