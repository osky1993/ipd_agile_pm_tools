import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { z } from 'zod'
import { api, textResult } from '../client.js'

/**
 * 写工具：create_work_items 默认 dry_run 预览（AI 拆条的安全阀）；
 * 状态流转/建链由后端状态机与守卫兜底。
 * 刻意不封装 DCP 评审/变更审批等决策类写操作——高权重且只增不改，留人在 UI 完成。
 */
export function registerWriteTools(server: McpServer) {
  server.registerTool('create_work_items', {
    description:
      '批量创建工作项（会议纪要拆条等场景）。默认 dry_run=true 只返回预览不落库；' +
      '确认无误后显式传 dry_run=false 执行。parentCode 可选，按编号挂到已有父项（自动建 parent_of）。',
    inputSchema: {
      projectId: z.number().describe('项目 id'),
      dryRun: z.boolean().default(true).describe('true=只预览；false=实际创建'),
      items: z.array(z.object({
        type: z.string().describe('CAPABILITY/REQUIREMENT/STORY/TASK/DEFECT/RISK/CHANGE'),
        title: z.string().describe('标题（必填）'),
        description: z.string().optional(),
        priority: z.string().optional().describe('P0~P3'),
        acceptanceCriteria: z.string().optional().describe('验收条件（进 Ready 的前提）'),
        estimate: z.string().optional(),
        parentCode: z.string().optional().describe('父项编号，如 OVN1-CAP-001'),
      })).min(1),
    },
  }, async ({ projectId, dryRun, items }) =>
    textResult(await api.post('/work-items/batch-create', { projectId, dryRun, items })))

  server.registerTool('transition_work_item', {
    description: '工作项状态流转（走状态机与守卫；回退必须给 reason；守卫拦截时返回错误原因）',
    inputSchema: {
      id: z.number().describe('工作项 id'),
      toStatus: z.string().describe('目标状态（先用 get_work_item 查 nextStatuses）'),
      reason: z.string().optional().describe('理由（回退必填）'),
    },
  }, async ({ id, toStatus, reason }) =>
    textResult(await api.post(`/work-items/${id}/transition`, { toStatus, reason })))

  server.registerTool('update_work_item', {
    description: '更新工作项字段（null 字段不动；描述/验收条件支持 Markdown）',
    inputSchema: {
      id: z.number().describe('工作项 id'),
      patch: z.object({
        title: z.string().optional(),
        description: z.string().optional(),
        ownerId: z.number().optional(),
        priority: z.string().optional(),
        acceptanceCriteria: z.string().optional(),
        estimate: z.string().optional(),
        forecastDate: z.string().optional().describe('yyyy-MM-dd'),
      }),
    },
  }, async ({ id, patch }) => textResult(await api.put(`/work-items/${id}`, patch)))

  server.registerTool('create_trace_link', {
    description:
      '建立追溯关系。relation 可选：contributes_to/parent_of/implements/verifies/blocks/' +
      'depends_on/changes/affects/evidences/released_in',
    inputSchema: {
      projectId: z.number(),
      sourceType: z.string().describe('通常 WORK_ITEM'),
      sourceId: z.number(),
      targetType: z.string().describe('WORK_ITEM / PRODUCT_VERSION / EVIDENCE'),
      targetId: z.number(),
      relation: z.string(),
    },
  }, async (input) => textResult(await api.post('/traces', input)))
}
