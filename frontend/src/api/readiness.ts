import http from './http'
import type { GateCriterion } from './catalog'

export interface DomainReadiness {
  domain: string
  total: number
  met: number
  partial: number
  notReady: number
  waived: number
  redlineUnmet: string[]
  metPercent: number
}

export interface Overall {
  ready: boolean
  reqTotal: number
  reqAccepted: number
  reasons: string[]
}

export interface ReadinessSummary {
  domains: DomainReadiness[]
  overall: Overall
}

export const readinessApi = {
  domains: () => http.get<any, string[]>('/readiness/domains'),
  items: (projectId: number, domain?: string) =>
    http.get<any, GateCriterion[]>('/readiness/items', { params: { projectId, domain } }),
  summary: (projectId: number) => http.get<any, ReadinessSummary>('/readiness/summary', { params: { projectId } }),
}
