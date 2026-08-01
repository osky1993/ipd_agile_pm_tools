import http from './http'

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

export interface ExecOverview {
  summary: ExecSummary
  projects: ProjectCard[]
  weeklyThroughput: WeekRow[]
  alerts: ExecAlert[]
}

export const execApi = {
  overview: () => http.get<any, ExecOverview>('/exec/overview'),
}
