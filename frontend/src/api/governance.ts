import http from './http'

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
  list: (projectId: number) => http.get<any, Evidence[]>('/evidence', { params: { projectId } }),
  upload: (projectId: number, file: File, linkType?: string, linkId?: number) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<any, Evidence>('/evidence', form, {
      params: { projectId, linkType, linkId },
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  downloadUrl: (id: number) => `/api/evidence/${id}/download`,
  previewUrl: (id: number) => `/api/evidence/${id}/preview`,
}

export const decisionApi = {
  list: (projectId: number) => http.get<any, Decision[]>('/decisions', { params: { projectId } }),
}

export const changeApi = {
  analyze: (id: number) => http.post<any, ImpactResult>(`/changes/${id}/analyze`),
  decide: (id: number, approve: boolean, reason: string) =>
    http.post<any, Decision>(`/changes/${id}/decide`, { approve, reason }),
}
