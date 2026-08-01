import http from './http'
import type { WorkItem } from './workitem'

export interface Iteration {
  id: number
  code: string
  projectId: number
  name: string
  goal?: string
  startDate?: string
  endDate?: string
  status: string
  hidden?: number
}

export interface VerifiedReq {
  id: number
  code: string
  title: string
}

export interface TestCase {
  id: number
  code: string
  projectId: number
  title: string
  steps?: string
  expected?: string
  status?: 'DRAFT' | 'ACTIVE' | 'DISABLED'
  verifies?: VerifiedReq[]
}

export interface TestRun {
  id: number
  code: string
  testCaseId: number
  result: string
  actual?: string
  runVersionId?: number | null
  defectId?: number | null
  runAt: string
}

export const iterationApi = {
  list: (projectId: number) => http.get<any, Iteration[]>('/iterations', { params: { projectId } }),
  create: (data: Partial<Iteration>) => http.post<any, Iteration>('/iterations', data),
  update: (id: number, data: Partial<Iteration>) => http.put<any, Iteration>(`/iterations/${id}`, data),
  items: (id: number) => http.get<any, WorkItem[]>(`/iterations/${id}/items`),
  assign: (id: number, workItemId: number) => http.post(`/iterations/${id}/assign/${workItemId}`),
  remove: (workItemId: number) => http.delete(`/iterations/items/${workItemId}`),
}

export const testApi = {
  listCases: (projectId: number) => http.get<any, TestCase[]>('/tests/cases', { params: { projectId } }),
  importTemplateUrl: () => '/api/tests/import-template.xlsx',
  exportUrl: (projectId: number) => `/api/tests/export.xlsx?projectId=${projectId}`,
  importCases: (projectId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<any, { created: number; errors: string[] }>('/tests/import', form, {
      params: { projectId },
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  createCase: (data: Partial<TestCase>, verifiesRequirementId?: number) =>
    http.post<any, TestCase>('/tests/cases', data, { params: verifiesRequirementId ? { verifiesRequirementId } : {} }),
  updateCase: (id: number, data: Partial<TestCase>) => http.put<any, TestCase>(`/tests/cases/${id}`, data),
  changeCaseStatus: (id: number, status: string) => http.post<any, TestCase>(`/tests/cases/${id}/status`, { status }),
  deleteCase: (id: number) => http.delete(`/tests/cases/${id}`),
  listRuns: (caseId: number) => http.get<any, TestRun[]>(`/tests/cases/${caseId}/runs`),
  execute: (data: Partial<TestRun>, autoCreateDefect = true) =>
    http.post<any, TestRun>('/tests/runs', data, { params: { autoCreateDefect } }),
}
