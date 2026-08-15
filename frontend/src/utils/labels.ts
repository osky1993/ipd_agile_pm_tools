/**
 * 全局中文标签字典：所有英文枚举值的展示层汉化在此集中维护。
 * 存储/接口仍用英文枚举，仅显示层转换。
 */

/** 工作项状态（通用/缺陷/变更/风险共用一张表；风险的 Accepted 语义特殊需传 type） */
const STATUS_ZH: Record<string, string> = {
  // 通用链
  'Backlog': '待办',
  'Ready': '就绪',
  'In Progress': '进行中',
  'Verification': '验证中',
  'Accepted': '已验收',
  // 缺陷链
  'Open': '打开',
  'Analysing': '分析中',
  'Fixing': '修复中',
  'Retesting': '复测中',
  'Closed': '已关闭',
  // 变更链
  'Submitted': '已提交',
  'Impact Analysed': '影响已分析',
  'Approved': '已批准',
  'Rejected': '已拒绝',
  'Implemented': '已实施',
  'Verified': '已验证',
  // 风险链（Open/Closed 复用上面）
  'Mitigating': '缓解中',
}

export function statusLabel(status: string | null | undefined, type?: string): string {
  if (!status) return ''
  if (type === 'RISK' && status === 'Accepted') return '已接受'
  return STATUS_ZH[status] ?? status
}

/** 工作项类型 */
const TYPE_ZH: Record<string, string> = {
  CAPABILITY: '产品能力', REQUIREMENT: '需求', STORY: '用户故事',
  TASK: '任务', DEFECT: '缺陷', RISK: '风险', CHANGE: '变更',
}
export const typeLabel = (t: string | null | undefined) => (t ? TYPE_ZH[t] ?? t : '')

/** 追溯关系类型 */
const REL_ZH: Record<string, string> = {
  contributes_to: '贡献于（商业目标）',
  parent_of: '分解为（父→子）',
  implements: '实现',
  verifies: '验证',
  blocks: '阻塞',
  depends_on: '依赖于',
  changes: '变更涉及',
  affects: '影响',
  evidences: '佐证（挂证据）',
  released_in: '纳入版本',
}
export const relationLabel = (r: string) => REL_ZH[r] ?? r

/** 迭代状态 */
const SPRINT_ZH: Record<string, string> = {
  PLANNING: '规划中', ACTIVE: '进行中', DONE: '已完成', CLOSED: '已关闭',
}
export const sprintStatusLabel = (s: string) => SPRINT_ZH[s] ?? s

/** 决策结论（DCP 与变更审批） */
const DECISION_ZH: Record<string, string> = {
  PASS: '通过', CONDITIONAL: '有条件通过', REJECT: '不通过',
  APPROVED: '批准', REJECTED: '拒绝',
}
export const decisionLabel = (c: string) => DECISION_ZH[c] ?? c

/** 决策类型 */
const DECISION_TYPE_ZH: Record<string, string> = { DCP: '阶段决策', CHANGE: '变更决策' }
export const decisionTypeLabel = (t: string) => DECISION_TYPE_ZH[t] ?? t

/** 测试用例状态 */
const CASE_STATUS_ZH: Record<string, string> = { DRAFT: '草稿', ACTIVE: '启用', DISABLED: '停用' }
export const caseStatusLabel = (s: string | null | undefined) => (s ? CASE_STATUS_ZH[s] ?? s : '启用')

/** 测试执行结果 */
const RESULT_ZH: Record<string, string> = { PASS: '通过', FAIL: '失败', BLOCKED: '阻塞' }
export const testResultLabel = (r: string | null | undefined) => (r ? RESULT_ZH[r] ?? r : '未执行')

/** 审计动作（AuditEvent.action 的全部取值） */
const AUDIT_ACTION_ZH: Record<string, string> = {
  CREATE: '新建', UPDATE: '更新', DELETE: '删除',
  STATUS_CHANGE: '状态变更', OWNER_CHANGE: '责任人变更',
  DECISION: '决策', API_TOKEN: '签发令牌',
}
export const auditActionLabel = (a: string | null | undefined) => (a ? AUDIT_ACTION_ZH[a] ?? a : '')

/** 角色（与 V2__seed_roles.sql 的 sys_role.name 一致） */
const ROLE_ZH: Record<string, string> = {
  ADMIN: '系统管理员', PM: '项目经理', PO: '产品负责人', DEV: '开发',
  QA: '测试', QUALITY: '质量负责人', REVIEWER: '评审角色',
}
export const roleLabel = (r: string | null | undefined) => (r ? ROLE_ZH[r] ?? r : '')

/** 预警/阻塞严重度 */
const SEVERITY_ZH: Record<string, string> = { HIGH: '高', MED: '中', LOW: '低' }
export const severityLabel = (s: string | null | undefined) => (s ? SEVERITY_ZH[s] ?? s : '')

/** 项目生命周期状态 */
const LIFECYCLE_ZH: Record<string, string> = { ACTIVE: '进行中', ON_HOLD: '暂停', CLOSED: '已结项' }
export const lifecycleLabel = (s: string | null | undefined) => (s ? LIFECYCLE_ZH[s] ?? s : '')

/** 改进项状态（OPEN→DOING→DONE→VERIFIED） */
const IMPROVEMENT_ZH: Record<string, string> = {
  OPEN: '待启动', DOING: '进行中', DONE: '已落地', VERIFIED: '已验证',
}
export const improvementStatusLabel = (s: string | null | undefined) => (s ? IMPROVEMENT_ZH[s] ?? s : '')
