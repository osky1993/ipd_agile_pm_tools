# IPD 敏捷数字化工具箱

外层 IPD、内层敏捷的本地化数字项目管理工具箱：从商业目标 → 产品能力 → 需求 → 迭代/看板 → 测试与缺陷 → 变更与风险 → 证据 → DCP 准入 → 跨职能准备度 → 阶段决策，全程可正/反向追溯、有守卫、有审计，指标由业务数据自动计算并可下钻。

第一版「全流程能力验证」（规划阶段0～4）已于 2026-08 通过 M5 验收；此后持续增量演进（效能改进、多项目大屏、汇报输出、AI 对接等，见下文）。

## 功能总览

### 主导航页面

| 页面 | 路由 | 说明 |
|---|---|---|
| 我的一天 | `/my` | 跨项目个人工作台：我的待办、超期、待审批、DCP 临近事项聚合 |
| 项目驾驶舱 | `/dashboard` | 价值/交付流动/工程质量/产品成熟度四组指标，自动计算、可下钻、带趋势 |
| 项目·版本·阶段 | `/projects` | 项目生命周期、产品版本、阶段与 DCP 定义（含准入条件、红线） |
| 路标图 | `/roadmap` | 版本/DCP/迭代时间轴 + DCP 临近预警 |
| DCP准入条件 | `/dcp` | 准备度概览、规则引擎（红线/证据/豁免/有条件通过）、评审决策与快照下钻、跨职能五领域准备度 |
| 能力与需求树 | `/requirements` | 能力→需求→故事/任务树形视图，支持 Excel 层级导入 |
| Sprint看板 | `/board` | Backlog + 5 状态泳道拖拽（走状态机守卫），Work Item Age、迭代平铺切换 |
| 测试·缺陷·变更 | `/quality` | 用例管理与执行（Excel 导入/导出）、测试失败一键生成缺陷、缺陷闭环、变更影响分析与审批 |
| 追溯·风险·证据·决策 | `/trace` | 追溯矩阵、风险清单（超期高亮）、证据库（SHA-256）、决策记录（只增不改） |
| 效能改进 | `/performance` | 效能指标趋势、累积流图 CFD、站内预警聚合、改进闭环 |
| 工作项清单 | `/workitems` | 全类型工作项列表，全局搜索、批量操作、CSV 导入导出 |

### 独立投屏页与报告页

| 页面 | 路由 | 说明 |
|---|---|---|
| 数据大屏 | `/bigscreen` | 多项目聚合大屏，面向领导投屏 |
| 团队协作屏 | `/teamboard` | 上下游依赖网络（DAG 分层布局 + 成环检测）、阻塞规则引擎、交接台 |
| 项目状态报告 | `/report/project/:projectId` | 打印即 PDF 的汇报输出 |
| DCP 决策包 | `/report/dcp/:gateId` | 打印即 PDF 的决策评审材料 |

### 核心机制

- **统一 WorkItem 模型**：能力/需求/故事/任务/缺陷/风险/变更 7 类型单表承载，统一详情抽屉组件全站复用；
- **状态机 + 5 条守卫**：Ready 承诺、Verification 证据、Accepted 验收、缺陷复测、变更影响分析，集中在状态机引擎，回退强制填理由；
- **10 种追溯关系**：正反向建链/断链与全链查询，支撑影响分析与追溯矩阵；
- **证据与决策**：文件证据 SHA-256 摘要入库；Decision 只增不改，DCP 决策固化准备度快照、历史可复现；
- **全程审计**：状态、责任人、决策变化自动落 AuditEvent，业务代码不手写审计；
- **指标只读**：驾驶舱与效能指标全部来自 SQL 视图聚合，无人工填报入口，无个人绩效口径。

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + ECharts + Pinia |
| 后端 | Spring Boot 3.2 + Java 21 + MyBatis-Plus + Flyway |
| 数据库 | MySQL 8.4（Docker） |
| 认证 | JWT + 简单 RBAC（7 角色） |
| 证据 | 本地文件目录 + SHA-256 摘要（元数据入库） |
| AI 对接 | MCP Server（Node/TypeScript，封装 REST API 供 Claude Code 等使用） |

> 注意：本机默认 `java` 为 1.8，构建后端须用 JDK 21：
> `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`

## 快速启动

```bash
# 1. 起数据库
cd deploy && docker compose up -d

# 2. 起后端（JDK 21）
cd ../backend
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
mvn -DskipTests package
java -jar target/ipd-toolbox.jar          # http://localhost:8080

# 3. 起前端
cd ../frontend
npm install
npm run dev                                # http://localhost:5173
```

演示账号：**admin / admin123**（兼全部角色）。
接口文档：http://localhost:8080/swagger-ui.html

### 演示数据（可选）

```bash
./deploy/seed_demo_projects.sh   # 3 个典型示例项目（立项早期/开发中期/收尾），导览见 docs/05
./deploy/seed_perf_history.sh    # 5 项目效能历史数据，充分体现效能指标体系
./deploy/seed_team_links.sh      # 团队协作屏演示依赖链
./deploy/e2e_demo.sh             # 端到端剧本第 1~18 步一键连续演示
```

### 备份与恢复

```bash
./deploy/backup.sh               # mysqldump + 证据目录打包
./deploy/restore.sh <备份文件>    # 已实测：删库恢复后数据与 SQL 视图完整还原
```

## AI 对接（MCP Server）

[mcp/](./mcp/) 将工具箱 REST API 封装为 MCP tools，供 Claude Code 用自然语言操作：拆会议纪要为需求任务（默认 dry-run 预览）、查项目状态、汇编 DCP 评审材料等。读工具 9 个、写工具 4 个；DCP 评审、变更审批等决策类动作刻意不开放给 AI，留在 UI 由人完成。

安装注册见 [mcp/README.md](./mcp/README.md)，使用约定见 [docs/06-AI操作手册.md](./docs/06-AI操作手册.md)。

## 文档

| 文档 | 说明 |
|---|---|
| [docs/00-验收剧本.md](./docs/00-验收剧本.md) | 端到端验收剧本（阶段0契约） |
| [docs/01-对象模型与字段.md](./docs/01-对象模型与字段.md) | 业务对象、字段与统一编号规则 |
| [docs/02-状态机.md](./docs/02-状态机.md) | 状态流与守卫定义 |
| [docs/03-角色权限矩阵.md](./docs/03-角色权限矩阵.md) | 7 角色 × 对象操作矩阵 |
| [docs/04-全流程验收清单核验.md](./docs/04-全流程验收清单核验.md) | 第一版 M5 验收核验记录（21 项） |
| [docs/05-示例项目导览.md](./docs/05-示例项目导览.md) | 示例项目看点导览 |
| [docs/06-AI操作手册.md](./docs/06-AI操作手册.md) | AI 通过 MCP 操作工具箱的约定 |
| [docs/IPD敏捷数字化工具箱-分阶段落地规划.md](./docs/IPD敏捷数字化工具箱-分阶段落地规划.md) | 总体规划（原则/决策规则/验收清单仍为现行准绳，含现状对照） |
| [docs/archive/](./docs/archive/) | 历史归档（第一版开发计划 WBS，T 编号出处） |

## 项目状态与后续

- **第一版（规划阶段0～4）已完成**：七大核心页面、全流程闭环、§15 验收清单 21 项全部通过，端到端剧本可一键连续演示，备份恢复实测通过。
- **第一版后已增量落地**：效能指标体系与改进闭环、多项目聚合数据大屏、团队协作屏、路标图、「我的一天」、全站中文化、Excel/CSV/Markdown 导入导出与批量操作、汇报输出（打印即 PDF）、MCP Server 对接 Claude Code。
- **后续方向**：规划阶段5（工程化与真实系统集成）其余内容与阶段6（组合与经营管理）不提前建设，由承载真实项目暴露的瓶颈决定（规划§17）。
