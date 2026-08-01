#!/usr/bin/env bash
# 效能指标演示数据：在 5 个现有项目上构建"有时间纵深"的历史，充分体现效能指标体系。
# 三步：① API 补业务数据（守卫/追溯真实）② SQL 将时间戳回移铺开到过去 6 周
#       ③ API 设指标目标值（有达标有偏离）+ 建改进项（OPEN/DOING/VERIFIED 三态）
# 幂等保护：检测到 ROBO-REQ-006 已存在则直接退出。
set -euo pipefail

API="${API:-http://localhost:8080/api}"
CT="Content-Type: application/json"
MYSQL="docker exec -i ipd-mysql mysql -uipd -pipd123456 ipd_toolbox"

get_id()  { python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d['message'];print(d['data']['id'])"; }
must_ok() { python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d['message']" ; }
post()    { curl -s -X POST "$API$1" -H "$CT" -H "$AUTH" -d "$2"; }
put()     { curl -s -X PUT  "$API$1" -H "$CT" -H "$AUTH" -d "$2"; }
t()       { post "/work-items/$1/transition" "{\"toStatus\":\"$2\"}" | must_ok; }
step()    { echo "  · $1"; }

JWT=$(curl -s -X POST "$API/auth/login" -H "$CT" -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
AUTH="Authorization: Bearer $JWT"

pid_of() { curl -s "$API/projects" -H "$AUTH" | python3 -c "
import sys,json
print([p for p in json.load(sys.stdin)['data'] if p['code']=='$1'][0]['id'])"; }

ROBO=$(pid_of ROBO); PURE=$(pid_of PURE)
RVID=$($MYSQL -N -e "SELECT id FROM product_version WHERE project_id=$ROBO LIMIT 1" | tail -1)
CAP1=$($MYSQL -N -e "SELECT id FROM work_item WHERE project_id=$ROBO AND type='CAPABILITY' ORDER BY id LIMIT 1" | tail -1)
CAP2=$($MYSQL -N -e "SELECT id FROM work_item WHERE project_id=$ROBO AND type='CAPABILITY' ORDER BY id DESC LIMIT 1" | tail -1)
S1=$($MYSQL -N -e "SELECT id FROM iteration WHERE project_id=$ROBO AND name='Sprint-1'" | tail -1)
S2=$($MYSQL -N -e "SELECT id FROM iteration WHERE project_id=$ROBO AND name='Sprint-2'" | tail -1)
R4=$($MYSQL -N -e "SELECT id FROM work_item WHERE code='ROBO-REQ-004'" | tail -1)

echo "▶ ① API 补业务数据（ROBO 三条新需求全流程闭环）"
finish() { # $1 parent $2 title $3 estimate $4 sprint $5 tcTitle $6 defect|clean → echo REQ id
  local RID TCID DID
  RID=$(post "/work-items?parentId=$1" "{\"projectId\":$ROBO,\"type\":\"REQUIREMENT\",\"title\":\"$2\",\"priority\":\"P1\"}" | get_id)
  put "/work-items/$RID" "{\"acceptanceCriteria\":\"实测达标\",\"ownerId\":1,\"estimate\":\"$3\"}" | must_ok
  post "/iterations/$4/assign/$RID" "" | must_ok
  t "$RID" "Ready"; t "$RID" "In Progress"
  put "/work-items/$RID" "{\"productVersionId\":$RVID}" | must_ok
  t "$RID" "Verification"
  TCID=$(post "/tests/cases?verifiesRequirementId=$RID" "{\"projectId\":$ROBO,\"title\":\"$5\"}" | get_id)
  if [ "$6" = "defect" ]; then
    DID=$(post "/tests/runs" "{\"testCaseId\":$TCID,\"result\":\"FAIL\",\"actual\":\"边界场景失败\",\"runVersionId\":$RVID}" \
      | python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d['message'];print(d['data']['defectId'])")
    for s in Analysing Fixing Retesting; do t "$DID" "$s"; done
    post "/tests/runs?autoCreateDefect=false" "{\"testCaseId\":$TCID,\"result\":\"PASS\",\"runVersionId\":$RVID,\"defectId\":$DID}" | must_ok
    t "$DID" "Closed"
  else
    post "/tests/runs" "{\"testCaseId\":$TCID,\"result\":\"PASS\",\"runVersionId\":$RVID}" | must_ok
  fi
  t "$RID" "Accepted"
  echo "$RID"
}
HAS_R6=$($MYSQL -N -e "SELECT COUNT(*) FROM work_item WHERE code='ROBO-REQ-006'" | tail -1)
if [ "$HAS_R6" = "0" ]; then
  finish "$CAP1" "回充对准成功率≥99%" 8 "$S1" "回充对准率实测" clean > /dev/null
  finish "$CAP1" "断点续扫：中断后从断点继续" 8 "$S1" "断点续扫场景测试" defect > /dev/null
  finish "$CAP2" "滤网寿命到期提醒" 5 "$S2" "滤网提醒链路测试" clean > /dev/null
  step "R6/R7/R8 闭环完成（R7 带缺陷闭环）"
else
  step "R6/R7/R8 已存在，跳过"
fi
# R4（Ready 未完成）改挂已结束的 Sprint-1 → 体现"承诺了未完成"，承诺完成率 3/4（重复执行无害）
post "/iterations/$S1/assign/$R4" "" | must_ok
step "R4 计入 Sprint-1 未兑现承诺"

echo "▶ ② SQL 时间回移（铺开到过去 6 周，制造真实的周期与停滞）"
$MYSQL <<'SQL'
-- ========== ROBO：需求链逐状态拉开 ==========
-- R1 覆盖率(已完成)：42天前创建，29天前验收（Lead 13 / Cycle 9）
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 42 DAY), accepted_at=DATE_SUB(NOW(),INTERVAL 29 DAY) WHERE code='ROBO-REQ-001';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 42 WHEN 'Ready' THEN 40 WHEN 'In Progress' THEN 38 WHEN 'Verification' THEN 31 WHEN 'Accepted' THEN 29 END DAY)
WHERE w.code='ROBO-REQ-001';
-- R6：26天前创建，18天前验收
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 26 DAY), accepted_at=DATE_SUB(NOW(),INTERVAL 18 DAY) WHERE code='ROBO-REQ-006';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 26 WHEN 'Ready' THEN 24 WHEN 'In Progress' THEN 23 WHEN 'Verification' THEN 19 WHEN 'Accepted' THEN 18 END DAY)
WHERE w.code='ROBO-REQ-006';
-- R7：19天前创建，12天前验收
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 19 DAY), accepted_at=DATE_SUB(NOW(),INTERVAL 12 DAY) WHERE code='ROBO-REQ-007';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 19 WHEN 'Ready' THEN 18 WHEN 'In Progress' THEN 17 WHEN 'Verification' THEN 13 WHEN 'Accepted' THEN 12 END DAY)
WHERE w.code='ROBO-REQ-007';
-- R8：12天前创建，5天前验收
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 12 DAY), accepted_at=DATE_SUB(NOW(),INTERVAL 5 DAY) WHERE code='ROBO-REQ-008';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 12 WHEN 'Ready' THEN 11 WHEN 'In Progress' THEN 10 WHEN 'Verification' THEN 6 WHEN 'Accepted' THEN 5 END DAY)
WHERE w.code='ROBO-REQ-008';
-- R2（验证中）：20天前创建，5天前进入 Verification
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 20 DAY) WHERE code='ROBO-REQ-002';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 20 WHEN 'Ready' THEN 18 WHEN 'In Progress' THEN 16 WHEN 'Verification' THEN 5 END DAY)
WHERE w.code='ROBO-REQ-002';
-- R3（开发中）：25天前创建，12天前进入 In Progress → WIP 停滞 12 天
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 25 DAY) WHERE code='ROBO-REQ-003';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 25 WHEN 'Ready' THEN 23 WHEN 'In Progress' THEN 12 END DAY)
WHERE w.code='ROBO-REQ-003';
-- R4（Ready）：10天前创建，9天前 Ready → 停滞 9 天
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 10 DAY) WHERE code='ROBO-REQ-004';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 10 WHEN 'Ready' THEN 9 END DAY)
WHERE w.code='ROBO-REQ-004';
-- R5（Backlog 未排期）：6天前创建
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 6 DAY) WHERE code='ROBO-REQ-005';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL 6 DAY) WHERE w.code='ROBO-REQ-005';
-- 缺陷1（R1 覆盖率，已关）：31天前建，28天前关（修复3天）
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 31 DAY) WHERE code='ROBO-DEF-001';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Open' THEN 31 WHEN 'Analysing' THEN 31 WHEN 'Fixing' THEN 30 WHEN 'Retesting' THEN 29 WHEN 'Closed' THEN 28 END DAY)
WHERE w.code='ROBO-DEF-001';
-- 缺陷2（R2 避障，仍打开）：16天前建 → 缺陷账龄告警
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 16 DAY) WHERE code='ROBO-DEF-002';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Open' THEN 16 WHEN 'Analysing' THEN 15 END DAY)
WHERE w.code='ROBO-DEF-002';
-- 缺陷3（R7 断点续扫，已关）：13天前建，11天前关（修复2天）
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 13 DAY) WHERE code='ROBO-DEF-003';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Open' THEN 13 WHEN 'Analysing' THEN 13 WHEN 'Fixing' THEN 12 WHEN 'Retesting' THEN 12 WHEN 'Closed' THEN 11 END DAY)
WHERE w.code='ROBO-DEF-003';
-- 变更（待审批）：5天前提交
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 5 DAY) WHERE code='ROBO-CHG-001';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Submitted' THEN 5 WHEN 'Impact Analysed' THEN 4 END DAY)
WHERE w.code='ROBO-CHG-001';
-- 测试执行时间对齐各自验证期
UPDATE test_run tr JOIN test_case tc ON tc.id=tr.test_case_id SET tr.run_at=DATE_SUB(NOW(),INTERVAL CASE tr.result WHEN 'FAIL' THEN 31 ELSE 29 END DAY) WHERE tc.code='ROBO-TC-001';
UPDATE test_run tr JOIN test_case tc ON tc.id=tr.test_case_id SET tr.run_at=DATE_SUB(NOW(),INTERVAL 16 DAY) WHERE tc.code='ROBO-TC-002';
UPDATE test_run tr JOIN test_case tc ON tc.id=tr.test_case_id SET tr.run_at=DATE_SUB(NOW(),INTERVAL 19 DAY) WHERE tc.code='ROBO-TC-003';
UPDATE test_run tr JOIN test_case tc ON tc.id=tr.test_case_id SET tr.run_at=DATE_SUB(NOW(),INTERVAL CASE tr.result WHEN 'FAIL' THEN 13 ELSE 12 END DAY) WHERE tc.code='ROBO-TC-004';
UPDATE test_run tr JOIN test_case tc ON tc.id=tr.test_case_id SET tr.run_at=DATE_SUB(NOW(),INTERVAL 6 DAY) WHERE tc.code='ROBO-TC-005';
-- Sprint 日期对齐
UPDATE iteration SET start_date=DATE_SUB(CURDATE(),INTERVAL 42 DAY), end_date=DATE_SUB(CURDATE(),INTERVAL 15 DAY) WHERE code='ROBO-SPR-001';
UPDATE iteration SET start_date=DATE_SUB(CURDATE(),INTERVAL 14 DAY), end_date=DATE_ADD(CURDATE(),INTERVAL 0 DAY) WHERE code='ROBO-SPR-002';

-- ========== PURE：4 条需求完成分布在 3~5 周前 ==========
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 39 DAY), accepted_at=DATE_SUB(NOW(),INTERVAL 31 DAY) WHERE code='PURE-REQ-001';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 39 WHEN 'Ready' THEN 37 WHEN 'In Progress' THEN 36 WHEN 'Verification' THEN 33 WHEN 'Accepted' THEN 31 END DAY)
WHERE w.code='PURE-REQ-001';
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 36 DAY), accepted_at=DATE_SUB(NOW(),INTERVAL 28 DAY) WHERE code='PURE-REQ-002';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 36 WHEN 'Ready' THEN 34 WHEN 'In Progress' THEN 33 WHEN 'Verification' THEN 30 WHEN 'Accepted' THEN 28 END DAY)
WHERE w.code='PURE-REQ-002';
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 31 DAY), accepted_at=DATE_SUB(NOW(),INTERVAL 24 DAY) WHERE code='PURE-REQ-003';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 31 WHEN 'Ready' THEN 29 WHEN 'In Progress' THEN 28 WHEN 'Verification' THEN 26 WHEN 'Accepted' THEN 24 END DAY)
WHERE w.code='PURE-REQ-003';
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 28 DAY), accepted_at=DATE_SUB(NOW(),INTERVAL 22 DAY) WHERE code='PURE-REQ-004';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 28 WHEN 'Ready' THEN 27 WHEN 'In Progress' THEN 26 WHEN 'Verification' THEN 24 WHEN 'Accepted' THEN 22 END DAY)
WHERE w.code='PURE-REQ-004';
-- PURE 缺陷（噪音，已关）：33天前建，30天前关
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 33 DAY) WHERE code='PURE-DEF-001';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Open' THEN 33 WHEN 'Analysing' THEN 33 WHEN 'Fixing' THEN 32 WHEN 'Retesting' THEN 31 WHEN 'Closed' THEN 30 END DAY)
WHERE w.code='PURE-DEF-001';
-- PURE 变更（已验证）：10天前提交，3天前验证 → 变更周期 7 天
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 10 DAY) WHERE code='PURE-CHG-001';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Submitted' THEN 10 WHEN 'Impact Analysed' THEN 9 WHEN 'Approved' THEN 8 WHEN 'Implemented' THEN 5 WHEN 'Verified' THEN 3 END DAY)
WHERE w.code='PURE-CHG-001';
UPDATE test_run tr JOIN test_case tc ON tc.id=tr.test_case_id JOIN project p ON p.id=tc.project_id
  SET tr.run_at=DATE_SUB(tr.run_at,INTERVAL 30 DAY) WHERE p.code='PURE';
UPDATE iteration SET start_date=DATE_SUB(CURDATE(),INTERVAL 40 DAY), end_date=DATE_SUB(CURDATE(),INTERVAL 27 DAY) WHERE code='PURE-SPR-001';
UPDATE iteration SET start_date=DATE_SUB(CURDATE(),INTERVAL 26 DAY), end_date=DATE_SUB(CURDATE(),INTERVAL 13 DAY) WHERE code='PURE-SPR-002';

-- ========== OVN1：最早的项目，整体在 5~6 周前 ==========
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 44 DAY), accepted_at=DATE_SUB(NOW(),INTERVAL 36 DAY) WHERE code='OVN1-REQ-001';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Backlog' THEN 44 WHEN 'Ready' THEN 42 WHEN 'In Progress' THEN 41 WHEN 'Verification' THEN 38 WHEN 'Accepted' THEN 36 END DAY)
WHERE w.code='OVN1-REQ-001';
UPDATE work_item SET created_at=DATE_SUB(NOW(),INTERVAL 40 DAY) WHERE code='OVN1-DEF-001';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id SET l.at=DATE_SUB(NOW(),INTERVAL CASE l.to_status
  WHEN 'Open' THEN 40 WHEN 'Analysing' THEN 40 WHEN 'Fixing' THEN 39 WHEN 'Retesting' THEN 38 WHEN 'Closed' THEN 37 END DAY)
WHERE w.code='OVN1-DEF-001';
UPDATE test_run tr JOIN test_case tc ON tc.id=tr.test_case_id JOIN project p ON p.id=tc.project_id
  SET tr.run_at=DATE_SUB(tr.run_at,INTERVAL 38 DAY) WHERE p.code='OVN1';

-- ========== EBK：立项早期，一周的历史感 ==========
UPDATE work_item w JOIN project p ON p.id=w.project_id SET w.created_at=DATE_SUB(w.created_at,INTERVAL 7 DAY) WHERE p.code='EBK';
UPDATE work_item_status_log l JOIN work_item w ON w.id=l.work_item_id JOIN project p ON p.id=w.project_id
  SET l.at=DATE_SUB(l.at,INTERVAL 7 DAY) WHERE p.code='EBK';
SQL
step "时间回移完成"

echo "▶ ③ 指标目标值（有达标有偏离）+ 改进项三态"
tgt() { put "/perf/target" "{\"projectId\":$1,\"metricKey\":\"$2\",\"targetValue\":$3}" | must_ok; }
# ROBO：暴露质量与流动短板
tgt "$ROBO" "quality.firstPassRate" 80      # 实际40 → 偏离
tgt "$ROBO" "quality.latestPassRate" 75     # 实际80 → 达标
tgt "$ROBO" "quality.reqCoverage" 80        # 实际62.5 → 偏离
tgt "$ROBO" "cycle.leadP85" 14              # 实际13 → 达标
tgt "$ROBO" "cycle.defectFixAvg" 5          # 实际≈2.7 → 达标
tgt "$ROBO" "flow.staleCount" 0             # 实际2 → 偏离
tgt "$ROBO" "gov.redlineRate" 100           # 实际0 → 偏离
tgt "$ROBO" "delivery.throughput4w" 3       # 实际3 → 达标
# PURE：收尾项目基本全绿，唯证据完备率偏离
tgt "$PURE" "quality.latestPassRate" 95
tgt "$PURE" "cycle.cycleP85" 10
tgt "$PURE" "gov.redlineRate" 100
tgt "$PURE" "gov.metEvidenceRate" 80        # 实际28.6 → 偏离
step "目标值已设定"

imp() { post "/perf/improvements" "$1" | get_id; }
HAS_IMP=$($MYSQL -N -e "SELECT COUNT(*) FROM improvement WHERE code LIKE 'ROBO-IMP-%' AND deleted=0" | tail -1)
if [ "$HAS_IMP" != "0" ]; then
  step "改进项已存在，跳过"
else
# ROBO 改进1：已完成闭环（基线9→目标5→实际2.7，VERIFIED）
I1=$(imp "{\"projectId\":$ROBO,\"metricKey\":\"cycle.defectFixAvg\",\"title\":\"缺陷当日分派、限期三天修复\",\"measure\":\"缺陷创建当日指派责任人；修复超三天升级到站会跟踪\",\"baselineValue\":9,\"targetValue\":5,\"dueDate\":\"$(date -v-3d +%Y-%m-%d 2>/dev/null || date -d '3 days ago' +%Y-%m-%d)\"}")
post "/perf/improvements/$I1/transition" '{"toStatus":"DOING"}' | must_ok
post "/perf/improvements/$I1/transition" '{"toStatus":"DONE"}' | must_ok
post "/perf/improvements/$I1/transition" '{"toStatus":"VERIFIED","resultValue":2.7,"conclusion":"平均修复周期 9→2.7 天，超额达标；保留站会升级机制"}' | must_ok
# ROBO 改进2：进行中（首过率基线自动固化）
I2=$(imp "{\"projectId\":$ROBO,\"metricKey\":\"quality.firstPassRate\",\"title\":\"测试用例先评审再执行，降低首跑失败\",\"measure\":\"新用例须经用例评审；执行前核对前置条件清单\",\"targetValue\":80,\"dueDate\":\"$(date -v+21d +%Y-%m-%d 2>/dev/null || date -d '21 days' +%Y-%m-%d)\"}")
post "/perf/improvements/$I2/transition" '{"toStatus":"DOING"}' | must_ok
# PURE 改进3：刚发起
imp "{\"projectId\":$PURE,\"metricKey\":\"gov.metEvidenceRate\",\"title\":\"准备度检查项补挂证据\",\"measure\":\"五领域就绪检查逐项上传支撑材料并建立证据链\",\"targetValue\":80,\"dueDate\":\"$(date -v+14d +%Y-%m-%d 2>/dev/null || date -d '14 days' +%Y-%m-%d)\"}" >/dev/null
step "改进项：VERIFIED（修复提速 9→2.7）/ DOING（首过率）/ OPEN（证据补挂）"
fi

echo
echo "=== 效能演示数据就绪。建议动线 ==="
echo "  效能改进页选 ROBO：指标有绿有黄、周吞吐 6 周分布、阶段停留条形图、"
echo "  停滞 TOP（R3 开发 12 天 / R4 就绪 9 天）；改进项 Tab 三种状态并存，"
echo "  VERIFIED 的一条展示 基线9 → 目标5 → 实际2.7 完整闭环。"
echo "  驾驶舱选 ROBO：预警新增 WIP停滞 与 缺陷账龄；PURE 对比看全绿收尾形态。"