import http from './http'
import type { GateCriterion } from './catalog'

/** 就绪度 API：域就绪分布、项目可交付就绪状态。 */

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
  /** 获取可用域清单，用于就绪度筛选面板。 */
  domains: () => http.get<any, string[]>('/readiness/domains'),
  /** 查询某项目某域/全部的门禁项，用于就绪度明细页。 */
  items: (projectId: number, domain?: string) =>
    http.get<any, GateCriterion[]>('/readiness/items', { params: { projectId, domain } }),
  /** 聚合就绪度总览（ready、已满足、未满足）及汇总原因。 */
  summary: (projectId: number) => http.get<any, ReadinessSummary>('/readiness/summary', { params: { projectId } }),
}
