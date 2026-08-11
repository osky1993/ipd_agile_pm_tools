import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { z } from 'zod'
import { api, textResult } from '../client.js'

/** 读工具（readOnlyHint）：面向查询场景，默认无副作用。 */
/** 读工具（readOnlyHint）：项目/工作项/追溯/DCP/准备度/预警/决策查询。 */
export function registerReadTools(server: McpServer) {
  server.registerTool('list_projects', {
    description: '列出全部项目（id、编号、名称、商业目标、生命周期状态）',
    inputSchema: {},
    annotations: { readOnlyHint: true },
  }, async () => textResult(await api.get('/projects')))

  server.registerTool('get_project_brief', {
    description: '项目简报：核心指标概览 + 阶段/DCP 清单 + 当前预警，一次拿全项目现状',
    inputSchema: { projectId: z.number().describe('项目 id') },
    annotations: { readOnlyHint: true },
  }, async ({ projectId }) => {
    const [metrics, gates, alerts] = await Promise.all([
      api.get('/metrics/overview', { projectId }),
      api.get('/stage-gates', { projectId }),
      api.get('/alerts', { projectId }),
    ])
    return textResult({ metrics, stageGates: gates, alerts })
  })

  server.registerTool('list_work_items', {
    description: '列出项目工作项，可按类型过滤（CAPABILITY/REQUIREMENT/STORY/TASK/DEFECT/RISK/CHANGE）',
    inputSchema: {
      projectId: z.number().describe('项目 id'),
      type: z.string().optional().describe('工作项类型（可选）'),
    },
    annotations: { readOnlyHint: true },
  }, async ({ projectId, type }) => textResult(await api.get('/work-items', { projectId, type })))

  server.registerTool('get_work_item', {
    description: '工作项详情：字段 + 追溯关系 + 状态时间线 + 可流转的下一状态',
    inputSchema: { id: z.number().describe('工作项 id') },
    annotations: { readOnlyHint: true },
  }, async ({ id }) => {
    const [item, traces, history, nextStatuses] = await Promise.all([
      api.get(`/work-items/${id}`),
      api.get(`/work-items/${id}/traces`),
      api.get(`/work-items/${id}/status-history`),
      api.get(`/work-items/${id}/next-statuses`),
    ])
    return textResult({ item, traces, statusHistory: history, nextStatuses })
  })

  server.registerTool('search_work_items', {
    description: '按编号/标题模糊搜索工作项（跨项目，最多 20 条）',
    inputSchema: { q: z.string().describe('关键词') },
    annotations: { readOnlyHint: true },
  }, async ({ q }) => textResult(await api.get('/work-items/search', { q })))

  server.registerTool('get_dcp_overview', {
    description: 'DCP 准入总览：条件清单（含红线/证据数）+ 准备度快照（红线未满足/证据缺失/无责任人清单）',
    inputSchema: { stageGateId: z.number().describe('阶段/DCP id（stage_gate）') },
    annotations: { readOnlyHint: true },
  }, async ({ stageGateId }) => textResult(await api.get(`/dcp/gates/${stageGateId}/overview`)))

  server.registerTool('get_readiness', {
    description: '五领域（技术/质量/供应/制造/上市）跨职能准备度汇总 + 整机就绪判断',
    inputSchema: { projectId: z.number().describe('项目 id') },
    annotations: { readOnlyHint: true },
  }, async ({ projectId }) => textResult(await api.get('/readiness/summary', { projectId })))

  server.registerTool('list_alerts', {
    description: '项目站内预警（超期风险/红线未满足/DCP 临近/承诺到期/证据缺失等 9 类规则）',
    inputSchema: { projectId: z.number().describe('项目 id') },
    annotations: { readOnlyHint: true },
  }, async ({ projectId }) => textResult(await api.get('/alerts', { projectId })))

  server.registerTool('list_decisions', {
    description: '项目决策记录（DCP 与变更审批；只增不改，修订通过 prevDecisionId 串链；含固化的准备度快照）',
    inputSchema: { projectId: z.number().describe('项目 id') },
    annotations: { readOnlyHint: true },
  }, async ({ projectId }) => textResult(await api.get('/decisions', { projectId })))

  server.registerTool('get_baseline_diff', {
    description: '范围基线对比：当前 vs 项目最新基线——蔓延（基线外新增）/移除/完成/日期偏差/估算漂移。回答"承诺了什么、后来变成了什么"',
    inputSchema: { projectId: z.number().describe('项目 id') },
    annotations: { readOnlyHint: true },
  }, async ({ projectId }) => {
    const baselines = (await api.get('/baselines', { projectId })) as { id: number }[]
    if (!baselines.length) return textResult({ message: '该项目尚未建立基线（DCP 评审通过时自动固化，或在基线管理页手动建立）' })
    return textResult(await api.get(`/baselines/${baselines[0].id}/diff`))
  })

  server.registerTool('get_critical_path', {
    description: '关键路径推演（CPM）：基于 depends_on/blocks 依赖与 estimate 推算 ES/EF/浮动/关键链与总工期（今天=T+0）。回答"哪件事拖一天全项目拖一天"',
    inputSchema: { projectId: z.number().describe('项目 id') },
    annotations: { readOnlyHint: true },
  }, async ({ projectId }) => textResult(await api.get('/schedule/critical-path', { projectId })))

  server.registerTool('list_lessons', {
    description: '经验教训库（组织资产）：跨项目检索历史经验（类别 WELL/IMPROVE/PROCESS/TECH/SUPPLY/OTHER）',
    inputSchema: {
      keyword: z.string().optional().describe('关键词（搜标题/内容）'),
      category: z.string().optional().describe('类别过滤'),
      projectId: z.number().optional().describe('项目过滤（不传=全部项目）'),
    },
    annotations: { readOnlyHint: true },
  }, async ({ keyword, category, projectId }) =>
    textResult(await api.get('/assets/lessons', { keyword, category, projectId })))
}
