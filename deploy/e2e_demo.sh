#!/usr/bin/env bash
# 端到端验收剧本连续演示（T707）。在运行中的系统上创建一个全新项目并跑通剧本第 1~18 步。
# 用法：./e2e_demo.sh   （每次使用带时间戳的项目代码，可重复运行，不干扰现有数据）
set -euo pipefail

API="${API:-http://localhost:8080/api}"
CODE="DEMO$(date +%H%M%S)"
CT="Content-Type: application/json"

jq_get() { python3 -c "import sys,json;print(json.load(sys.stdin)['data']$1)"; }
step() { echo; echo "▶ $1"; }

echo "=== 端到端演示：项目代码 ${CODE} ==="
JWT=$(curl -s -X POST "$API/auth/login" -H "$CT" -d '{"username":"admin","password":"admin123"}' | jq_get "['token']")
AUTH="Authorization: Bearer $JWT"

step "1-3 建项目/版本/阶段/DCP条件"
PID=$(curl -s -X POST "$API/projects" -H "$CT" -H "$AUTH" -d "{\"code\":\"$CODE\",\"name\":\"演示烤箱\",\"goal\":\"端到端演示\"}" | jq_get "['id']")
curl -s -X POST "$API/product-versions" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"versionNo\":\"V1.0\"}" >/dev/null
VID=$(curl -s "$API/product-versions?projectId=$PID" -H "$AUTH" | jq_get "[0]['id']")
curl -s -X POST "$API/stage-gates" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"stageName\":\"开发验证\",\"gateName\":\"DCP2\",\"seq\":2}" >/dev/null
GID=$(curl -s "$API/stage-gates?projectId=$PID" -H "$AUTH" | jq_get "[0]['id']")
GC=$(curl -s -X POST "$API/gate-criteria" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"stageGateId\":$GID,\"domain\":\"质量\",\"criterion\":\"核心安全场景通过\",\"isRedline\":1,\"ownerId\":1}" | jq_get "['id']")
echo "  项目 $CODE(id=$PID) 版本 V1.0 阶段 DCP2 红线条件已建"

step "4-5 建能力与需求（parent_of 追溯）"
CAP=$(curl -s -X POST "$API/work-items" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"type\":\"CAPABILITY\",\"title\":\"远程启动烹饪\"}" | jq_get "['id']")
REQ=$(curl -s -X POST "$API/work-items?parentId=$CAP" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"门未关闭时禁止远程启动\"}" | jq_get "['id']")
echo "  能力+需求已建并建立 parent_of"

step "6 需求进 Sprint（Ready 守卫）"
SPR=$(curl -s -X POST "$API/iterations" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"name\":\"Sprint-1\",\"goal\":\"首个增量\"}" | jq_get "['id']")
curl -s -X PUT "$API/work-items/$REQ" -H "$CT" -H "$AUTH" -d '{"acceptanceCriteria":"门开时启动被拒","ownerId":1,"estimate":"3"}' >/dev/null
curl -s -X POST "$API/iterations/$SPR/assign/$REQ" -H "$AUTH" >/dev/null
echo "  需求补齐 Ready 条件后拉入 Sprint-1"

step "7-9 开发→测试失败→自动生成缺陷"
curl -s -X POST "$API/work-items/$REQ/transition" -H "$CT" -H "$AUTH" -d '{"toStatus":"Ready"}' >/dev/null
curl -s -X POST "$API/work-items/$REQ/transition" -H "$CT" -H "$AUTH" -d '{"toStatus":"In Progress"}' >/dev/null
curl -s -X PUT "$API/work-items/$REQ" -H "$CT" -H "$AUTH" -d "{\"productVersionId\":$VID}" >/dev/null
curl -s -X POST "$API/work-items/$REQ/transition" -H "$CT" -H "$AUTH" -d '{"toStatus":"Verification"}' >/dev/null
TC=$(curl -s -X POST "$API/tests/cases?verifiesRequirementId=$REQ" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"title\":\"门未关禁止启动\"}" | jq_get "['id']")
DEF=$(curl -s -X POST "$API/tests/runs" -H "$CT" -H "$AUTH" -d "{\"testCaseId\":$TC,\"result\":\"FAIL\",\"actual\":\"门开仍启动\",\"runVersionId\":$VID}" | jq_get "['defectId']")
echo "  测试失败自动生成缺陷 id=$DEF"

step "10 缺陷修复→复测通过→关闭（守卫#2）"
for s in Analysing Fixing Retesting; do curl -s -X POST "$API/work-items/$DEF/transition" -H "$CT" -H "$AUTH" -d "{\"toStatus\":\"$s\"}" >/dev/null; done
curl -s -X POST "$API/tests/runs?autoCreateDefect=false" -H "$CT" -H "$AUTH" -d "{\"testCaseId\":$TC,\"result\":\"PASS\",\"runVersionId\":$VID,\"defectId\":$DEF}" >/dev/null
curl -s -X POST "$API/work-items/$DEF/transition" -H "$CT" -H "$AUTH" -d '{"toStatus":"Closed"}' >/dev/null
echo "  复测 PASS，缺陷已关闭"

step "11 能力验收（需求 Accepted）"
curl -s -X POST "$API/work-items/$REQ/transition" -H "$CT" -H "$AUTH" -d '{"toStatus":"Accepted"}' >/dev/null
echo "  需求已 Accepted（自动记录验收人/时间）"

step "12-13 安全需求变更→影响分析→审批"
CHG=$(curl -s -X POST "$API/work-items" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"type\":\"CHANGE\",\"title\":\"强化门锁联锁\"}" | jq_get "['id']")
curl -s -X POST "$API/traces" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"sourceType\":\"WORK_ITEM\",\"sourceId\":$CHG,\"targetType\":\"WORK_ITEM\",\"targetId\":$REQ,\"relation\":\"changes\"}" >/dev/null
curl -s -X POST "$API/changes/$CHG/analyze" -H "$AUTH" >/dev/null
curl -s -X POST "$API/work-items/$CHG/transition" -H "$CT" -H "$AUTH" -d '{"toStatus":"Impact Analysed"}' >/dev/null
curl -s -X POST "$API/changes/$CHG/decide" -H "$CT" -H "$AUTH" -d '{"approve":true,"reason":"安全强化必要"}' >/dev/null
echo "  变更完成影响分析并批准"

step "14-15 登记遗留风险 + 上传证据"
RSK=$(curl -s -X POST "$API/work-items" -H "$CT" -H "$AUTH" -d "{\"projectId\":$PID,\"type\":\"RISK\",\"title\":\"联锁件长周期\",\"ownerId\":1,\"extFields\":\"{\\\"mitigation\\\":\\\"锁定备选\\\",\\\"dueDate\\\":\\\"2026-09-30\\\"}\"}" | jq_get "['id']")
echo "演示测试报告" > /tmp/e2e_report.txt
curl -s -X POST "$API/evidence?projectId=$PID&linkType=GATE_CRITERION&linkId=$GC" -H "$AUTH" -F "file=@/tmp/e2e_report.txt" >/dev/null
echo "  风险已登记，证据已上传（关联红线条件）"

step "16-17 DCP 评审：红线满足后有条件通过"
curl -s -X PUT "$API/gate-criteria/$GC" -H "$CT" -H "$AUTH" -d '{"status":"MET"}' >/dev/null
curl -s -X POST "$API/dcp/gates/$GID/review" -H "$CT" -H "$AUTH" \
  -d "{\"conclusion\":\"CONDITIONAL\",\"reason\":\"核心通过,遗留供应风险\",\"linkedRiskId\":$RSK,\"commitmentDue\":\"2026-09-30\"}" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('  DCP 决策',d['data']['code'],'=',d['data']['conclusion']) if d['code']==0 else print('  失败:',d['message'])"

step "18 驾驶舱指标（自动计算）"
curl -s "$API/metrics/overview?projectId=$PID" -H "$AUTH" | python3 -c "
import sys,json
o=json.load(sys.stdin)['data']
print('  能力验收 %d/%d | 需求验收 %d/%d | 测试通过率 %d%% | 需求覆盖 %d%% | 缺陷 %d关/%d'%(
  o['value']['capabilityAccepted'],o['value']['capabilityTotal'],
  o['value']['requirementAccepted'],o['value']['requirementTotal'],
  o['quality']['testPassRate'],o['quality']['reqCoverage'],
  o['quality']['defectClosed'],o['quality']['defectTotal']))"

echo; echo "=== 剧本连续演示完成，项目 ${CODE} ==="
