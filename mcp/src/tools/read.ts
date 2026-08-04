import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { z } from 'zod'
import { api, textResult } from '../client.js'

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
}
