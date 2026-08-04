import http from './http'

export interface ProductVersion {
  id: number
  code: string
  projectId: number
  model?: string
  versionNo: string
  baseline?: string
  planReleaseDate?: string | null
  actualReleaseDate?: string | null
}

export interface StageGate {
  id: number
  code: string
  projectId: number
  stageName: string
  gateName: string
  seq: number
  planDate?: string | null
  forecastDate?: string | null
}

export interface GateCriterion {
  id: number
  code: string
  projectId: number
  stageGateId?: number | null
  domain: string
  criterion: string
  ownerId?: number | null
  status: string
  evidenceReq?: string | null
  isRedline: number
  reviewConclusion?: string | null
}

export interface TreeNode {
  id: number
  code: string
  type: string
  title: string
  status: string
  priority?: string | null
  children: TreeNode[]
}

export const versionApi = {
  list: (projectId: number) => http.get<any, ProductVersion[]>('/product-versions', { params: { projectId } }),
  create: (data: Partial<ProductVersion>) => http.post<any, ProductVersion>('/product-versions', data),
  update: (id: number, data: Partial<ProductVersion>) => http.put<any, ProductVersion>(`/product-versions/${id}`, data),
}

export const stageApi = {
  list: (projectId: number) => http.get<any, StageGate[]>('/stage-gates', { params: { projectId } }),
  create: (data: Partial<StageGate>) => http.post<any, StageGate>('/stage-gates', data),
  update: (id: number, data: Partial<StageGate>) => http.put<any, StageGate>(`/stage-gates/${id}`, data),
}

export const criterionApi = {
  list: (projectId: number, stageGateId?: number) =>
    http.get<any, GateCriterion[]>('/gate-criteria', { params: { projectId, stageGateId } }),
  create: (data: Partial<GateCriterion>) => http.post<any, GateCriterion>('/gate-criteria', data),
  update: (id: number, data: Partial<GateCriterion>) => http.put<any, GateCriterion>(`/gate-criteria/${id}`, data),
}

export const treeApi = {
  get: (projectId: number) => http.get<any, TreeNode[]>('/work-items/tree', { params: { projectId } }),
  createChild: (parentId: number | null, data: { projectId: number; type: string; title: string }) =>
    http.post<any, any>('/work-items', data, { params: parentId ? { parentId } : {} }),
}
