import http from './http'

export interface Lesson {
  id: number
  projectId: number
  category: 'WELL' | 'IMPROVE' | 'PROCESS' | 'TECH' | 'SUPPLY' | 'OTHER'
  title: string
  detail?: string | null
  sourceType?: string | null
  sourceId?: number | null
  createdBy?: number | null
  createdAt: string
}

export interface RiskRow {
  id: number
  code: string
  projectCode?: string | null
  title: string
  status: string
  exposure?: number | null
  exposureLevel?: string | null
  strategy?: string | null
  resolveDays?: number | null
}

export interface WordFreq {
  word: string
  count: number
}

export interface RiskPatterns {
  total: number
  closed: number
  accepted: number
  open: number
  avgResolveDays?: number | null
  byLevel: Record<string, number>
  byStrategy: Record<string, number>
  topWords: WordFreq[]
  rows: RiskRow[]
}

export const LESSON_CATEGORY: Record<string, string> = {
  WELL: '做得好', IMPROVE: '待改进', PROCESS: '流程', TECH: '技术', SUPPLY: '供应', OTHER: '其他',
}

export const assetsApi = {
  lessons: (params?: { keyword?: string; category?: string; projectId?: number }) =>
    http.get<any, Lesson[]>('/assets/lessons', { params }),
  createLesson: (data: Partial<Lesson>) => http.post<any, Lesson>('/assets/lessons', data),
  deleteLesson: (id: number) => http.delete<any, void>(`/assets/lessons/${id}`),
  riskPatterns: (keyword?: string) =>
    http.get<any, RiskPatterns>('/assets/risk-patterns', { params: { keyword } }),
}
