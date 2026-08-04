# IPD 工具箱 MCP Server

把工具箱的 REST API 封装为 MCP tools，供 Claude Code 用自然语言操作工具箱（拆需求、查 DCP、生成评审材料等）。

## 安装与构建

```bash
cd mcp
npm install
npm run build
```

## 获取长效 Token

工具箱后端运行中时（admin/admin123 为演示账号）：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

curl -s -X POST http://localhost:8080/api/auth/api-token \
  -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])'
```

输出即 365 天长效 token（签发动作会记入审计）。

## 在 Claude Code 注册

项目根目录（或任意工作目录）的 `.mcp.json`：

```json
{
  "mcpServers": {
    "ipd-toolbox": {
      "command": "node",
      "args": ["/Users/osky/workspaces/workspace_tools/pm/mcp/dist/index.js"],
      "env": {
        "IPD_BASE_URL": "http://localhost:8080",
        "IPD_TOKEN": "<上一步获取的长效token>"
      }
    }
  }
}
```

## 工具清单

读（9）：`list_projects`、`get_project_brief`、`list_work_items`、`get_work_item`、`search_work_items`、`get_dcp_overview`、`get_readiness`、`list_alerts`、`list_decisions`

写（4）：`create_work_items`（**默认 dry_run 预览**，确认后传 `dryRun:false` 落库）、`transition_work_item`、`update_work_item`、`create_trace_link`

刻意不提供：DCP 评审、变更审批等决策类写操作（只增不改的高权重动作，留在 UI 由人完成）。

## 典型用法

- 「把这段会议纪要拆成需求和任务，挂到 OVN1-CAP-001 下」→ Claude 调 `create_work_items`（先预览再落库）
- 「OVN1 项目现在什么状态？有什么要处理的？」→ `get_project_brief`
- 「帮我整理 DCP2 的评审材料」→ `get_dcp_overview` + `get_readiness` + `list_decisions` 后汇编成文档

更多约定见 [docs/06-AI操作手册.md](../docs/06-AI操作手册.md)。
