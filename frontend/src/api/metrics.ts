import http from './http'
import type { WorkItem } from './workitem'
import type { ReadinessSummary } from './readiness'

/** 指标域 API：返回全局指标、趋势、追溯矩阵及导出链接。 */

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

export interface TrendPoint {
  date: string
  defectInflow: number
  defectClosed: number
  openDefects: number | null
  criteriaTotal: number | null
  criteriaMet: number | null
  reqTotal: number | null
  reqAccepted: number | null
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
  /** 查询项目指标总览。 */
  overview: (projectId: number) => http.get<any, MetricsOverview>('/metrics/overview', { params: { projectId } }),
  /** 按指标类别钻取明细工作项。 */
  drilldown: (projectId: number, metric: string) =>
    http.get<any, WorkItem[]>('/metrics/drilldown', { params: { projectId, metric } }),
  /** 时间序列趋势（缺陷、验收、能力口径）。 */
  trend: (projectId: number, days = 30) =>
    http.get<any, TrendPoint[]>('/metrics/trend', { params: { projectId, days } }),
  /** 追溯矩阵，检查需求与测试映射完整性。 */
  traceMatrix: (projectId: number) => http.get<any, MatrixRow[]>('/metrics/trace-matrix', { params: { projectId } }),
  /** 导出工作项 CSV 的直接下载地址（保留 BOM 兼容）。 */
  exportCsvUrl: (projectId: number) => `/api/metrics/export/work-items.csv?projectId=${projectId}`,
}
