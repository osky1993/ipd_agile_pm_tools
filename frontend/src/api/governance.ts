import http from './http'

/** 证据/决策/变更治理相关 API。 */

export interface Evidence {
  id: number
  code: string
  fileName: string
  sha256: string
  sizeBytes: number
  mime?: string
  uploadedBy?: number
  createdAt: string
}

export interface Decision {
  id: number
  code: string
  decisionType: string
  subjectType?: string
  subjectId?: number
  conclusion: string
  reason?: string
  decidedBy: number
  decidedAt: string
  prevDecisionId?: number | null
  snapshot?: string | null
  linkedRiskId?: number | null
  commitmentDue?: string | null
}

export interface ImpactItem {
  category: string
  type: string
  id: number
  code: string
  title: string
  via: string
}

export interface ImpactResult {
  items: ImpactItem[]
  total: number
}

export const evidenceApi = {
  /** 查询项目证据列表。 */
  list: (projectId: number) => http.get<any, Evidence[]>('/evidence', { params: { projectId } }),
  /** 上传证据文件，支持与对象（linkType/linkId）绑定。 */
  upload: (projectId: number, file: File, linkType?: string, linkId?: number) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<any, Evidence>('/evidence', form, {
      params: { projectId, linkType, linkId },
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  /** 下载证据文件直链。 */
  downloadUrl: (id: number) => `/api/evidence/${id}/download`,
  /** 预览证据文件直链。 */
  previewUrl: (id: number) => `/api/evidence/${id}/preview`,
}

export const decisionApi = {
  /** 列出项目决策清单。 */
  list: (projectId: number) => http.get<any, Decision[]>('/decisions', { params: { projectId } }),
}

export const changeApi = {
  /** 变更影响分析（联动需求/测试/风险等）。 */
  analyze: (id: number) => http.post<any, ImpactResult>(`/changes/${id}/analyze`),
  /** 提交变更决策（批准/拒绝并记录理由）。 */
  decide: (id: number, approve: boolean, reason: string) =>
    http.post<any, Decision>(`/changes/${id}/decide`, { approve, reason }),
}
