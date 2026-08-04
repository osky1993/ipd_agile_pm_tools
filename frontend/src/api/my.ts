import http from './http'

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
  today: () => http.get<any, MyToday>('/my/today'),
}
