import http from './http'

/** 版本、阶段门、门禁标准及树形结构服务 API。 */

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
  /** 查询项目版本列表。 */
  list: (projectId: number) => http.get<any, ProductVersion[]>('/product-versions', { params: { projectId } }),
  /** 新增产品版本。 */
  create: (data: Partial<ProductVersion>) => http.post<any, ProductVersion>('/product-versions', data),
  /** 更新版本字段（release 信息、标识码等）。 */
  update: (id: number, data: Partial<ProductVersion>) => http.put<any, ProductVersion>(`/product-versions/${id}`, data),
}

export const stageApi = {
  /** 查询项目阶段门节点。 */
  list: (projectId: number) => http.get<any, StageGate[]>('/stage-gates', { params: { projectId } }),
  /** 新增阶段门（新增里程碑节点）。 */
  create: (data: Partial<StageGate>) => http.post<any, StageGate>('/stage-gates', data),
  /** 更新阶段门（名称、日期、顺序）。 */
  update: (id: number, data: Partial<StageGate>) => http.put<any, StageGate>(`/stage-gates/${id}`, data),
}

export interface CriterionTemplateItem {
  domain: string
  criterion: string
  evidenceReq?: string
  redline: boolean
}

export interface CriterionTemplate {
  key: string
  name: string
  note: string
  items: CriterionTemplateItem[]
}

export const criterionApi = {
  /** 查询门禁条件；支持 projectId、stageGateId 过滤。 */
  list: (projectId: number, stageGateId?: number) =>
    http.get<any, GateCriterion[]>('/gate-criteria', { params: { projectId, stageGateId } }),
  /** 新建门禁条件。 */
  create: (data: Partial<GateCriterion>) => http.post<any, GateCriterion>('/gate-criteria', data),
  /** 更新门禁条件（owner、状态、证据要求）。 */
  update: (id: number, data: Partial<GateCriterion>) => http.put<any, GateCriterion>(`/gate-criteria/${id}`, data),
  /** 获取模板库（按业务域聚合常用条件）。 */
  templates: () => http.get<any, CriterionTemplate[]>('/gate-criteria/templates'),
  /** 基于模板快速铺设条件；支持重复应用时幂等处理。 */
  applyTemplate: (projectId: number, stageGateId: number, templateKey: string) =>
    http.post<any, { created: number; skipped: number; codes: string[] }>('/gate-criteria/apply-template', { projectId, stageGateId, templateKey }),
}

export const treeApi = {
  /** 获取工作项树（用于目录树渲染与导航）。 */
  get: (projectId: number) => http.get<any, TreeNode[]>('/work-items/tree', { params: { projectId } }),
  /** 新增子节点（type=需求/缺陷/任务等）。 */
  createChild: (parentId: number | null, data: { projectId: number; type: string; title: string }) =>
    http.post<any, any>('/work-items', data, { params: parentId ? { parentId } : {} }),
}
