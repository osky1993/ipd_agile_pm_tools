import http from './http'

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
  overview: (stageGateId: number) => http.get<any, Overview>(`/dcp/gates/${stageGateId}/overview`),
  review: (stageGateId: number, body: { conclusion: string; reason: string; linkedRiskId?: number; commitmentDue?: string }) =>
    http.post<any, any>(`/dcp/gates/${stageGateId}/review`, body),
  updateCriterion: (id: number, data: Record<string, unknown>) => http.put<any, any>(`/gate-criteria/${id}`, data),
}
