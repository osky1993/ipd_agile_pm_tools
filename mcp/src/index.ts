#!/usr/bin/env node
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { registerReadTools } from './tools/read.js'
import { registerWriteTools } from './tools/write.js'

/**
 * IPD 敏捷数字化工具箱 MCP Server（stdio）。
 * 环境变量：IPD_BASE_URL（默认 http://localhost:8080）、IPD_TOKEN（长效 token，
 * 登录后 POST /api/auth/api-token 获取）。
 */
const server = new McpServer({ name: 'ipd-toolbox', version: '0.1.0' })

/**
 * MCP 服务器 bootstrap：先挂载读写工具，再启动 stdio transport。
 * 约定：
 * - 只支持本地进程通信（stdio），无 HTTP/WS 长连接
 * - 所有实际业务权限与边界判断由后端 API 层保证
 */
registerReadTools(server)
registerWriteTools(server)

const transport = new StdioServerTransport()
await server.connect(transport)
console.error('[ipd-toolbox-mcp] ready (stdio)')
