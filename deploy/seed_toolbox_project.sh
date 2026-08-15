#!/usr/bin/env bash
# 摩擦记录机制建档（docs/07 §1 A4）：用工具管理工具自己。
#
# 把「IPD 工具箱」自身注册为项目 TOOLBOX，dogfooding 期每条摩擦记为该项目下的
# improvement，复用既有 OPEN→DOING→DONE→VERIFIED 闭环。六周结束时，摩擦日志本身
# 就是一份走过闭环的数据集，也是对 improvement 功能的真实验证。
#
# 幂等：项目已存在则跳过建项；改进项按标题去重，已存在则跳过。
# 用法：./deploy/seed_toolbox_project.sh
set -uo pipefail

API="${API:-http://localhost:8080/api}"
CT="Content-Type: application/json"
CODE="TOOLBOX"

JWT=$(curl -s -X POST "$API/auth/login" -H "$CT" -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])") || {
  echo "登录失败，请确认后端已在 $API 运行"; exit 1; }
AUTH="Authorization: Bearer $JWT"

# ---------- 1. 建项目（幂等） ----------
PID=$(curl -s "$API/projects" -H "$AUTH" | python3 -c "
import sys,json
m=[p for p in json.load(sys.stdin)['data'] if p['code']=='$CODE']
print(m[0]['id'] if m else '')")

if [ -z "$PID" ]; then
  PID=$(curl -s -X POST "$API/projects" -H "$CT" -H "$AUTH" -d '{
    "code":"TOOLBOX",
    "name":"IPD 工具箱（工具自身）",
    "goal":"承载 dogfooding 期的摩擦日志：每条摩擦记为 improvement，走完 OPEN→DOING→DONE→VERIFIED"
  }' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
  echo "已建项目 $CODE(id=$PID)"
else
  echo "项目 $CODE(id=$PID) 已存在，跳过建项"
fi

# ---------- 2. 录入改进项（幂等，按标题去重） ----------
# 数值口径统一为「该摩擦每周消耗的分钟数」：
#   baselineValue=现状  targetValue=目标  resultValue=修复后实测（VERIFIED 时必填，见下）
# 之所以要定这个口径：ImprovementService.transition 进 VERIFIED 时强制校验 resultValue
# 非空（DECIMAL(10,2)），不绑指标就不会自动取值。同时它正好是「按频次×耗时排序」的依据。
EXISTING=$(curl -s "$API/perf/improvements?projectId=$PID" -H "$AUTH" | python3 -c "
import sys,json
print('\n'.join(i['title'] for i in json.load(sys.stdin)['data']))")

add_imp() { # add_imp <标题> <措施四要素> <基线分钟> <目标分钟>
  title="$1"; measure="$2"; base="$3"; target="$4"
  if printf '%s\n' "$EXISTING" | grep -Fxq "$title"; then
    echo "  跳过（已存在）：$title"
    return
  fi
  body=$(python3 -c "
import json,sys
print(json.dumps({
  'projectId': $PID,
  'title': sys.argv[1],
  'measure': sys.argv[2],
  'baselineValue': float(sys.argv[3]),
  'targetValue': float(sys.argv[4]),
}, ensure_ascii=False))" "$title" "$measure" "$base" "$target")
  code=$(curl -s -X POST "$API/perf/improvements" -H "$CT" -H "$AUTH" -d "$body" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['code'])")
  echo "  已录入 $code：$title"
}

echo "录入摩擦条目 ..."

# 三个候选：只记录不启动，留给迭代 C 决策（docs/07 §3）。停在 OPEN 不流转。
add_imp "候选：主动提醒（晨报 / 分级通知）" \
"场景：单人工具没有「别人催你」，超期项与临近 DCP 全靠自己想起来去看。
频次：每天开工时。
单次耗时或绕过方式：绕过方式=手工翻「我的一天」+ 预警列表，约 5 分钟；漏看则代价更大。
期望：每早自动收到一份晨报（我的一天聚合 + 预警），超期/DCP 临近单独弹。
—— 对应 docs/07 §3.2 候选 C1，阶梯 1 为 deploy/daily_brief.sh，零后端改动。" 35 5

add_imp "候选：录入成本（收件箱模式 / AI 拆分剧本）" \
"场景：想到一件事时，要选类型、选归属、填估算才能落库，念头常常就断在这。
频次：每天数次。
单次耗时或绕过方式：绕过方式=先记在备忘录，事后再补录，等于录两遍。
期望：一行速记先接住（落 Backlog 特定标记），每周五整理时再补类型/归属/估算。
—— 对应 docs/07 §3.3 候选 C2；基建（batch-create + dryRun）已备。" 40 10

add_imp "候选：性能实测与优化" \
"场景：时光机回放、CFD、效能趋势三类接口在数据量涨上来后可能变慢。
频次：复盘与周报时。
单次耗时或绕过方式：暂无实测数据，不预先优化。
期望：先造 5000 工作项 / 5 万条状态日志压测，超过 1.5s 交互阈值才动手。
—— 对应 docs/07 §3.4 候选 C3；先测再说，不预先优化。" 0 0

# 一条真实摩擦作为四要素模板的可照抄样板（本次调研中实际发现）。
add_imp "详情抽屉审计 Tab 的「动作」列显示英文原值" \
"场景：打开任一工作项详情 →「审计与时间线」Tab，动作列直接显示 STATUS_CHANGE /
OWNER_CHANGE / API_TOKEN 等英文枚举，需要在脑子里翻译一遍才知道发生了什么。
频次：每次查工作项历史，约每天 2~3 次。
单次耗时或绕过方式：绕过方式=靠边上的摘要文字反推，每次多花约 10 秒。
期望：显示「状态变更」「责任人变更」等中文。
定位：WorkItemDrawer.vue 的审计表格 action 列直接 prop 绑定原始字段，无 formatter；
修法是在 utils/labels.ts 加 auditActionLabel() 并改用 template 渲染——纯前端一处改动。
—— 属于 docs/07 §1 A5 汉化清扫范围。" 5 0

echo
echo "完成。查看：/performance → 选 $CODE 项目 →「改进项」Tab"
echo "（「候选：」前缀可用改进项 Tab 的标题关键词框筛出）"
