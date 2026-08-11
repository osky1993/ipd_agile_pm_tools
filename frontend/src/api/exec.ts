import http from './http'

/** 执行层总览 API：汇总项目、告警、缺陷与改进态势。 */

export interface ExecSummary {
  projectsActive: number
  projectsOnHold: number
  projectsClosed: number
  reqTotal: number
  reqAccepted: number
  openDefects: number
  pendingChanges: number
  activeSprints: number
  alertHigh: number
  alertMed: number
  improvementsDoing: number
  improvementsVerified: number
}

export interface ProjectCard {
  id: number
  code: string
  name: string
  lifecycleStatus: string
  stageName: string | null
  gateName: string | null
  lastDecision: string | null
  lastDecisionAt: string | null
  reqTotal: number
  reqAccepted: number
  testPassRate: number | null
  openDefects: number
  pendingChanges: number
  redlineUnmet: number
  ready: boolean
  alertHigh: number
  alertMed: number
  throughput4w: number
  leadP85: number | null
  health: 'GOOD' | 'RISK' | 'DANGER'
}

export interface WeekRow {
  weekStart: string
  byProject: Record<string, number>
}

export interface ExecAlert {
  severity: 'HIGH' | 'MED' | 'LOW'
  type: string
  projectCode: string
  title: string
  detail: string
}

export interface DefectWeek {
  weekStart: string
  inflow: number
  closed: number
}

export interface RecentEvent {
  projectCode: string
  summary: string
  at: string | null
}

export interface ActiveImprovement {
  projectCode: string
  code: string
  title: string
  metricName: string | null
}

export interface ExecOverview {
  summary: ExecSummary
  projects: ProjectCard[]
  weeklyThroughput: WeekRow[]
  alerts: ExecAlert[]
  combinedDefectTrend: DefectWeek[]
  recentEvents: RecentEvent[]
  activeImprovements: ActiveImprovement[]
}

export const execApi = {
  /** 拉取高层执行总览（领导驾驶舱）。 */
  overview: () => http.get<any, ExecOverview>('/exec/overview'),
}
