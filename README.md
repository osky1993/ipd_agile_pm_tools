# IPD 敏捷数字化工具箱

外层 IPD、内层敏捷的本地化数字项目管理工具箱。第一版目标：用一条"薄切纵向链路"验证从商业目标到交付与决策证据的全流程闭环。

- 规划：[IPD敏捷数字化工具箱-分阶段落地规划.md](./IPD敏捷数字化工具箱-分阶段落地规划.md)
- 开发计划：[IPD工具箱-开发任务分解与开发计划.md](./IPD工具箱-开发任务分解与开发计划.md)
- 阶段0契约：[docs/](./docs/)（验收剧本 / 对象模型 / 状态机 / 权限矩阵）

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + ECharts + Pinia |
| 后端 | Spring Boot 3.2 + Java 21 + MyBatis-Plus + Flyway |
| 数据库 | MySQL 8.4（Docker） |
| 认证 | JWT + 简单 RBAC（7 角色） |
| 证据 | 本地文件目录 + SHA-256 摘要（元数据入库） |

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

```bash
# 4.（可选）造 3 个典型示例项目：EBK 立项早期 / ROBO 开发中期 / PURE 收尾阶段
#    帮助新用户快速了解业务流程与工具能力，看点见 docs/05-示例项目导览.md
./deploy/seed_demo_projects.sh
```

## 当前进度（对照开发计划里程碑）

已完成并端到端验证：

- **E0 阶段0契约**（T001~T004）：验收剧本、对象模型与字段、状态机、角色权限矩阵——4 份文档已锁定。
- **E1 工程骨架**：monorepo 结构、后端骨架（统一响应/异常、OpenAPI）、前端骨架（7 页面导航壳 + 登录）、Docker Compose、简单 RBAC + JWT 登录、审计基础设施（AuditService）、统一编号生成器（`OVN1-REQ-001` 式，并发安全）。
- **E2 数据主线**：Flyway `V1__schema.sql` 建成 12 张核心表 + RBAC + 工作项状态时间线 + 编号序号表；项目 CRUD 可复用范式跑通。
- **T205 统一 WorkItem 模型 + 状态机引擎**：7 类型单表统一承载；状态机集中定义 4 条状态流（通用/缺陷/风险/变更）；5 条守卫框架（Ready/Verification/Accepted/缺陷复测/变更影响分析）——其中 3 条已实时生效，缺陷与变更守卫待 E3/E4 数据到位后触发；回退强制填理由；状态迁移写时间线 + 审计。
- **T206 统一工作项详情组件**（`WorkItemDrawer.vue`，全系统复用度最高）：三 Tab——基本信息（编辑 + 状态操作按钮）、关联追溯（正反向解析标题、建链/断链）、审计与时间线。宿主页 `WorkItems.vue`（"能力与需求树"列表形态）。
- **T207 追溯服务（最小版）**：10 种关系建链/断链 + 给定对象上下游查询。
- **T203/T204 项目·版本·阶段（页面②）**：ProductVersion / StageGate / GateCriterion CRUD；页面②「管理」抽屉两 Tab 维护产品版本与阶段/DCP（含准入条件占位录入、红线标记）。
- **T208 能力与需求树（页面④）**：`/work-items/tree` 按 parent_of 构树；`RequirementTree.vue` 用 el-tree 呈现能力→需求→故事/任务，节点可开详情、可就地建子项（自动建 parent_of）。

## ✅ M1 数字主线可见（达成）

可端到端建立一条完整但尚未执行的项目链路并双向导航：
项目 → 产品版本 → 阶段/DCP → 准入条件（含红线）→ 产品能力 → 需求（树形）→ 追溯关系 → 工作项详情（状态流转 / 关联 / 审计时间线）。守卫、审计、编号、验收记录全程生效。

## ✅ M2 交付质量闭环（达成）

- **T301/T302/T303 迭代与 Backlog**：Iteration CRUD、工作项进出 Sprint、**Ready 承诺守卫**（未满足验收条件/责任人/估算不能进 Sprint）。
- **T304 Sprint 看板**（`Board.vue`）：产品 Backlog + 5 状态泳道，卡片拖拽触发流转（走状态机与守卫），卡片显示 Work Item Age，点标题开 `WorkItemDrawer`。
- **T305/T306 测试**：TestCase（verifies 需求）、TestRun 执行；**测试失败一键生成缺陷**并自动建链 缺陷-affects->需求/用例、缺陷-released_in->版本。
- **T307 缺陷闭环**：Open→Analysing→Fixing→Retesting→Closed，**关闭前必须有复测 PASS（守卫#2）**。
- **T310 测试·缺陷页**（`TestDefect.vue`）：用例+执行记录、缺陷列表复用详情组件。

端到端验证（curl + 浏览器）：建 Sprint → 需求进 Sprint 被 Ready 守卫拦截→补齐后放行 → 建用例 verifies 需求 → 测试 FAIL 自动生成缺陷（3 条追溯链）→ 缺陷未复测关闭被守卫#2 拦截 → 复测 PASS → 缺陷 Closed。看板拖拽流转（真实鼠标）+ 状态刷新已验证。

## ✅ M3 变更·风险·证据·决策闭环（达成）

- **T401/T405 变更**：影响分析按 TraceLink 自动列出受影响需求/测试/版本（两跳 BFS），完成置 `impactAnalysed`→守卫#5 放行；授权角色（REVIEWER）审批生成 Decision 并流转 Approved/Rejected。
- **T402 证据**：文件落本地目录 + DB 存元数据与 **SHA-256**，可建 对象-evidences->证据 追溯、下载；上传摘要与本地 `shasum` 一致。
- **T403 风险**：RISK 工作项（责任人/处置措施/期限存 ext_fields），**超期高亮 + Tab 角标计数**。
- **T404 决策**：Decision **只增不改**，修订走新记录 link prevDecisionId。
- **T406/T407 页面**：页面⑦（`Governance.vue`）风险/证据/决策三 Tab；页面⑥（`TestDefect.vue`）新增变更 Tab（影响分析对话框 + 审批）。

端到端验证（curl + 浏览器）：上传证据 SHA-256 校验一致 → 建变更 changes 关联需求 → 守卫#5 拦截未分析 → 影响分析列出 REQ-001+TC-001 → 进 Impact Analysed → 授权审批 DEC-001 APPROVED + 变更 Approved → 登记超期风险 → 决策只增不改。

## ✅ E5 IPD 决策闭环（达成，剧本第 16~17 步）

- **T502 DCP 规则引擎**：红线未满足不可判 PASS、已满足无证据→证据缺失、豁免必填理由+期限、有条件通过必须绑定遗留风险+期限、决策只增不改。
- **T503 准备度计算**：分领域满足率、红线未满足/证据缺失/无责任人清单，评审时**固化准备度快照**（可复现）。
- **T504/T506 页面③ DCP**（`DcpBoard.vue`）：准备度概览卡片、条件清单（可编辑状态、上传证据、红线标识）、发起评审、决策历史、**快照下钻**。
- **T507 下钻**：DCP 条件证据数、决策快照均可查看。

端到端验证（curl + 浏览器）：准备度概览 → 红线未满足拦截 PASS → 置 MET 无证据触发证据缺失 → 上传证据消除 → 有条件通过未绑风险被拦 → 绑定风险+期限生成 DEC-002 CONDITIONAL + 固化快照 → 快照下钻可复现。

**七个页面全部实现**（驾驶舱、项目版本阶段、DCP 准入、能力需求树、Sprint 看板、测试缺陷变更、追溯风险证据决策）。

## ✅ M4 跨职能准备度（达成，阶段4）

- **T601/T602 五领域准备度**：复用 GateCriterion（isReadiness=1）承载技术/质量/供应/制造/上市检查项；DCP 页「跨职能准备度」视图分领域维护。
- **T603 汇总视图**：驾驶舱产品成熟度区块 + DCP 页五领域仪表盘（满足率、红线标识）。
- **T604 整机就绪判断**：识别"局部完成但整机未就绪"（需求已验收但存在领域红线/未就绪项），列出阻碍原因。
- **红线影响 DCP（规划§8.4）**：准备度领域红线未满足项自动进入 DCP 红线清单，拦截 PASS。

端到端验证：五领域汇总（供应红线未满足）→ 整机未就绪 + 局部完成识别 → 准备度红线（GC-005）流入 DCP 红线清单并拦截 PASS。

## ✅ M5 全流程验收（达成）—— 第一版完成 🎉

- **T701/T702 驾驶舱指标**：Flyway V3 三个 SQL 视图 + 服务端聚合，四组指标（价值/交付流动/工程质量/产品成熟度）全部**由业务数据自动计算、无人工填报**；ECharts 图表；**每个指标可下钻**到原始工作项。
- **T703 追溯矩阵**：需求×测试覆盖矩阵，暴露未覆盖需求。
- **T704 备份恢复**：`deploy/backup.sh` / `restore.sh`（mysqldump + 证据打包），**已实测**：删除数据→恢复后全部还原（含 SQL 视图）。
- **T705 绩效红线自检**：无任何个人绩效口径。
- **T706 验收清单核验**：规划§15 **21 项全部通过**，见 `docs/04-全流程验收清单核验.md`。
- **T707 端到端连续演示**：`deploy/e2e_demo.sh` 一键在干净项目跑通剧本第 1~18 步（测试通过率/需求覆盖 100%，缺陷闭环）。
- **T709 CSV 导出**：工作项 CSV。

### 第一版完成定义（规划§16）核验通过

阶段 0～4 退出条件全部满足；端到端剧本可连续演示；验收清单无阻断项；DCP 决策可下钻到需求/测试/缺陷/风险/证据；备份恢复成功；第一轮反馈与下一阶段优先级已记录（见验收文档 T708）。

**第一版"全流程能力验证"完成**：商业目标 → 产品能力 → 需求 → 迭代/看板 → 测试与缺陷 → 变更与风险 → 证据 → DCP 准入 → 跨职能准备度 → 阶段决策，全程可正/反向追溯、有守卫、有审计、指标自动计算并可下钻。

## 后续（规划§17，由真实瓶颈决定）

阶段 5（工程化：数据范围权限、导入、REST/Webhook、真实系统集成）与阶段 6（组合与经营管理）不提前建设；下一步方向取决于承载第二个真实项目时暴露的真实瓶颈。
