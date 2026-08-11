import http from './http'

/** 资产库 API：经验教训与风险模式统计聚合。 */

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

/** 经验教训与风险模式相关接口。 */
export const assetsApi = {
  /** 查询经验教训列表；支持关键字/类别/项目筛选。 */
  lessons: (params?: { keyword?: string; category?: string; projectId?: number }) =>
    http.get<any, Lesson[]>('/assets/lessons', { params }),
  /** 新增一条经验教训，用于复盘知识沉淀。 */
  createLesson: (data: Partial<Lesson>) => http.post<any, Lesson>('/assets/lessons', data),
  /** 删除经验教训（通常用于误填或重复记录）。 */
  deleteLesson: (id: number) => http.delete<any, void>(`/assets/lessons/${id}`),
  /** 汇总风险模式分布与高频文本，用于风险识别页。 */
  riskPatterns: (keyword?: string) =>
    http.get<any, RiskPatterns>('/assets/risk-patterns', { params: { keyword } }),
}
