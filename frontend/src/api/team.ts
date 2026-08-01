import http from './http'

export interface ColumnCount {
  status: string
  count: number
}

export interface SprintPulse {
  iterationId: number
  name: string
  goal: string | null
  startDate: string | null
  endDate: string | null
  totalDays: number
  daysGone: number
  timePct: number
  committedCount: number
  doneCount: number
  committedPoints: number
  donePoints: number
  donePct: number | null
  columns: ColumnCount[]
}

export interface GraphNode {
  id: number
  code: string
  type: string
  title: string
  status: string
  ownerName: string | null
  inActiveSprint: boolean
  blocked: boolean
  testBadge: 'PASS' | 'FAIL' | 'NO_RUN' | null
  degree: number
}

export interface GraphEdge {
  source: number
  target: number
  relation: 'dep' | 'parent_of' | 'affects' | 'changes'
}

export interface DependencyGraph {
  nodes: GraphNode[]
  edges: GraphEdge[]
  truncated: boolean
}

export interface Blocker {
  severity: 'HIGH' | 'MED' | 'LOW'
  rule: string
  title: string
  detail: string
  itemId: number | null
  itemCode: string | null
  causeIds: number[]
  days: number | null
}

export interface Handoff {
  at: string
  kind: 'TEST_READY' | 'CHANGE_APPROVED' | 'RETEST' | 'UNLOCKED'
  actionText: string
  itemId: number
  itemCode: string
  itemTitle: string
  toStatus: string
  downstreamId: number | null
  downstreamCode: string | null
}

export interface OwnerItem {
  id: number
  code: string
  type: string
  title: string
  status: string
  priority: string | null
  blocked: boolean
  stallDays: number
}

export interface OwnerLoad {
  ownerId: number | null
  ownerName: string
  points: number
  items: OwnerItem[]
}

export interface TeamOverview {
  projectId: number
  projectCode: string
  projectName: string
  sprint: SprintPulse | null
  graph: DependencyGraph
  blockers: Blocker[]
  handoffs: Handoff[]
  owners: OwnerLoad[]
}

export const teamApi = {
  overview: (projectId: number) => http.get<any, TeamOverview>('/team/overview', { params: { projectId } }),
}
