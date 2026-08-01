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

export interface TestCase {
  id: number
  code: string
  projectId: number
  title: string
  steps?: string
  expected?: string
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
  createCase: (data: Partial<TestCase>, verifiesRequirementId?: number) =>
    http.post<any, TestCase>('/tests/cases', data, { params: verifiesRequirementId ? { verifiesRequirementId } : {} }),
  listRuns: (caseId: number) => http.get<any, TestRun[]>(`/tests/cases/${caseId}/runs`),
  execute: (data: Partial<TestRun>, autoCreateDefect = true) =>
    http.post<any, TestRun>('/tests/runs', data, { params: { autoCreateDefect } }),
}
