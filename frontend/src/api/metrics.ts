import http from './http'
import type { WorkItem } from './workitem'
import type { ReadinessSummary } from './readiness'

export interface MetricsOverview {
  value: {
    capabilityTotal: number
    capabilityAccepted: number
    requirementTotal: number
    requirementAccepted: number
    pendingChanges: number
    dcpByStatus: { status: string; cnt: number }[]
  }
  flow: {
    wip: number
    throughput: number
    blocked: number
    cycleP50: number
    cycleP85: number
    cycleP95: number
  }
  quality: {
    openDefects: number
    defectTotal: number
    defectClosed: number
    casesWithRuns: number
    passCases: number
    testPassRate: number
    reqTotal: number
    reqCovered: number
    reqCoverage: number
  }
  maturity: ReadinessSummary
}

export interface MatrixRow {
  requirementId: number
  code: string
  title: string
  status: string
  covered: boolean
  tests: { testCode: string; testTitle: string; latestResult: string | null }[]
}

export const metricsApi = {
  overview: (projectId: number) => http.get<any, MetricsOverview>('/metrics/overview', { params: { projectId } }),
  drilldown: (projectId: number, metric: string) =>
    http.get<any, WorkItem[]>('/metrics/drilldown', { params: { projectId, metric } }),
  traceMatrix: (projectId: number) => http.get<any, MatrixRow[]>('/metrics/trace-matrix', { params: { projectId } }),
  exportCsvUrl: (projectId: number) => `/api/metrics/export/work-items.csv?projectId=${projectId}`,
}
