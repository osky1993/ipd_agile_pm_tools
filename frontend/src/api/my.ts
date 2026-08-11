import http from './http'

/** 我的工作台 API。 */

export interface MyItem {
  id: number
  code: string
  type: string
  title: string
  status: string
  projectCode: string
  priority?: string | null
  due?: string | null
}

export interface IterationEnding {
  id: number
  code: string
  name: string
  projectCode: string
  endDate: string
  daysLeft: number
  myOpenCount: number
}

export interface Alert {
  severity: 'HIGH' | 'MED' | 'LOW'
  type: string
  title: string
  detail: string
  refType: string
  refId: number
  refCode: string
  due?: string | null
}

export interface MyToday {
  inProgress: MyItem[]
  overdue: MyItem[]
  retest: MyItem[]
  endingSoon: IterationEnding[]
  projectAlerts: Alert[]
}

export const myApi = {
  /** 获取“我的一天”聚合数据：我的事项 + 即将到期项 + 项目预警。 */
  today: () => http.get<any, MyToday>('/my/today'),
}
