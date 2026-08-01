#!/usr/bin/env bash
# 造 3 个典型示例项目（全部走真实 API：守卫/状态机/追溯/审计/决策快照均真实生效），
# 覆盖产品生命周期三个典型阶段，帮助使用人快速了解业务流程与工具能力：
#   EBK  电动自行车智能控制器V1 —— 立项早期：需求树/DCP1已通过/干净的规划形态
#   ROBO 智能扫地机器人V2       —— 开发中期：看板流动/进行中缺陷/待决策变更/超期风险/红线拦截
#   PURE 智能空气净化器V3       —— 收尾阶段：全量验收/变更闭环/豁免/决策修订链/隐藏历史Sprint
# 用法：./seed_demo_projects.sh   幂等：项目代码已存在则跳过该项目。
set -euo pipefail

API="${API:-http://localhost:8080/api}"
CT="Content-Type: application/json"

get_id()   { python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d['message'];print(d['data']['id'])"; }
must_ok()  { python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d['message']" ; }
post()     { curl -s -X POST "$API$1" -H "$CT" -H "$AUTH" -d "$2"; }
put()      { curl -s -X PUT  "$API$1" -H "$CT" -H "$AUTH" -d "$2"; }
t()        { post "/work-items/$1/transition" "{\"toStatus\":\"$2\"}" | must_ok; }   # 状态流转
ready3()   { put "/work-items/$1" "{\"acceptanceCriteria\":\"$2\",\"ownerId\":1,\"estimate\":\"$3\"}" | must_ok; } # 补 Ready 三要素
step()     { echo "  · $1"; }

JWT=$(curl -s -X POST "$API/auth/login" -H "$CT" -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
AUTH="Authorization: Bearer $JWT"

exists() { # 项目代码已存在？
  curl -s "$API/projects" -H "$AUTH" | python3 -c "
import sys,json
codes=[p['code'] for p in json.load(sys.stdin)['data']]
sys.exit(0 if '$1' in codes else 1)"
}

# ============================================================================
# EBK 电动自行车智能控制器V1 —— 立项早期
# 看点：需求树（能力→需求 parent_of）、DCP1 概念决策已 PASS（证据+决策历史）、
#       Sprint 处于 PLANNING、测试用例未执行（覆盖矩阵可见缺口）
# ============================================================================
seed_ebk() {
  echo "▶ EBK 电动自行车智能控制器V1（立项早期）"
  PID=$(post "/projects" '{"code":"EBK","name":"电动自行车智能控制器V1","goal":"以智能助力与防盗定位切入中高端电助力市场"}' | get_id)
  post "/product-versions" "{\"projectId\":$PID,\"versionNo\":\"V1.0\"}" | must_ok
  VID=$(curl -s "$API/product-versions?projectId=$PID" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['id'])")

  # DCP1 概念决策（已通过）+ DCP2 计划决策（条件刚建立）
  G1=$(post "/stage-gates" "{\"projectId\":$PID,\"stageName\":\"概念\",\"gateName\":\"DCP1 概念决策\",\"seq\":1}" | get_id)
  G2=$(post "/stage-gates" "{\"projectId\":$PID,\"stageName\":\"计划\",\"gateName\":\"DCP2 计划决策\",\"seq\":2}" | get_id)
  C1=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$G1,\"domain\":\"市场\",\"criterion\":\"目标细分市场容量与竞品分析完成\",\"isRedline\":1,\"ownerId\":1,\"evidenceReq\":\"市场分析报告\"}" | get_id)
  C2=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$G1,\"domain\":\"技术\",\"criterion\":\"力矩传感方案可行性验证通过\",\"isRedline\":0,\"ownerId\":1,\"evidenceReq\":\"原理样机测试记录\"}" | get_id)
  post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$G2,\"domain\":\"研发\",\"criterion\":\"系统架构设计评审通过\",\"isRedline\":1,\"ownerId\":1}" | must_ok
  post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$G2,\"domain\":\"供应\",\"criterion\":\"关键器件供应商初选完成\",\"isRedline\":0,\"ownerId\":1}" | must_ok
  step "阶段 DCP1/DCP2 与准入条件已建"

  # 能力→需求树
  CAP1=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"CAPABILITY\",\"title\":\"智能助力\",\"description\":\"按骑行工况自适应输出助力\"}" | get_id)
  CAP2=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"CAPABILITY\",\"title\":\"防盗与定位\"}" | get_id)
  CAP3=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"CAPABILITY\",\"title\":\"OTA 远程升级\"}" | get_id)
  R1=$(post "/work-items?parentId=$CAP1" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"踏频+力矩双信号融合助力，响应延迟≤80ms\",\"priority\":\"P0\"}" | get_id)
  R2=$(post "/work-items?parentId=$CAP1" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"坡道起步自动增扭，坡度≥8%时提升30%\",\"priority\":\"P1\"}" | get_id)
  post "/work-items?parentId=$CAP2" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"异动报警：车辆被移动时 App 推送\",\"priority\":\"P1\"}" | must_ok
  post "/work-items?parentId=$CAP2" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"GPS+基站混合定位，误差≤15m\",\"priority\":\"P2\"}" | must_ok
  post "/work-items?parentId=$CAP3" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"固件差分升级，失败自动回滚\",\"priority\":\"P1\"}" | must_ok
  post "/work-items?parentId=$CAP3" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"升级过程骑行安全锁定\",\"priority\":\"P0\"}" | must_ok
  step "3 个能力 + 6 条需求（parent_of 需求树）"

  # Sprint-1 规划中，首条需求补齐 Ready 三要素后承诺进 Sprint
  SPR=$(post "/iterations" "{\"projectId\":$PID,\"name\":\"Sprint-1\",\"goal\":\"打通助力控制最小闭环\",\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-23\"}" | get_id)
  ready3 "$R1" "台架实测延迟≤80ms，三种工况曲线平滑无顿挫" "8"
  post "/iterations/$SPR/assign/$R1" "" | must_ok
  t "$R1" "Ready"
  step "Sprint-1（PLANNING）已建，1 条需求达到 Ready 并承诺"

  # 测试用例已写但未执行（追溯矩阵显示"未执行"缺口）
  post "/tests/cases?verifiesRequirementId=$R1" "{\"projectId\":$PID,\"title\":\"助力响应延迟台架测试\",\"steps\":\"台架模拟踏频30-90rpm阶跃\",\"expected\":\"延迟≤80ms\"}" | must_ok

  # DCP1 评审：条件满足+上传证据 → 决策 PASS
  echo "EBK 市场分析：中高端电助力市场年增23%，目标份额5%。竞品对比见附表。" > /tmp/ebk_market.txt
  echo "EBK 原理样机测试：力矩传感方案在台架上响应65ms，可行。" > /tmp/ebk_poc.txt
  curl -s -X POST "$API/evidence?projectId=$PID&linkType=GATE_CRITERION&linkId=$C1" -H "$AUTH" -F "file=@/tmp/ebk_market.txt" | must_ok
  curl -s -X POST "$API/evidence?projectId=$PID&linkType=GATE_CRITERION&linkId=$C2" -H "$AUTH" -F "file=@/tmp/ebk_poc.txt" | must_ok
  put "/gate-criteria/$C1" '{"status":"MET"}' | must_ok
  put "/gate-criteria/$C2" '{"status":"MET"}' | must_ok
  post "/dcp/gates/$G1/review" '{"conclusion":"PASS","reason":"市场与技术可行性均有证据支撑，进入计划阶段"}' | must_ok
  step "DCP1 概念决策 PASS（证据齐备，快照固化）"

  # 前期风险
  post "/work-items" "{\"projectId\":$PID,\"type\":\"RISK\",\"title\":\"中置电机供应商单一来源\",\"ownerId\":1,\"extFields\":\"{\\\"mitigation\\\":\\\"Q3完成第二供应商送样认证\\\",\\\"dueDate\\\":\\\"2026-10-31\\\"}\"}" | must_ok
  step "完成 ✔"
}

# ============================================================================
# ROBO 智能扫地机器人V2 —— 开发中期
# 看点：看板五列都有卡片、测试失败自动生成缺陷（一开一闭）、变更停在待审批、
#       超期风险高亮、DCP2 红线未满足（评审 PASS 会被拦截）、准备度"局部完成整机未就绪"
# ============================================================================
seed_robo() {
  echo "▶ ROBO 智能扫地机器人V2（开发中期）"
  PID=$(post "/projects" '{"code":"ROBO","name":"智能扫地机器人V2","goal":"以避障与自动集尘升级抢占换代需求"}' | get_id)
  post "/product-versions" "{\"projectId\":$PID,\"versionNo\":\"V2.0-beta\"}" | must_ok
  VID=$(curl -s "$API/product-versions?projectId=$PID" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['id'])")

  GID=$(post "/stage-gates" "{\"projectId\":$PID,\"stageName\":\"开发验证\",\"gateName\":\"DCP2 开发验证决策\",\"seq\":2}" | get_id)
  CM=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"domain\":\"质量\",\"criterion\":\"沿墙覆盖率实测≥95%\",\"isRedline\":0,\"ownerId\":1,\"evidenceReq\":\"覆盖率实测报告\"}" | get_id)
  post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"domain\":\"质量\",\"criterion\":\"避障误撞率≤2次/100㎡（红线）\",\"isRedline\":1,\"ownerId\":1,\"evidenceReq\":\"避障测试报告\"}" | must_ok
  post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"domain\":\"研发\",\"criterion\":\"集尘座风道噪音≤78dB\",\"isRedline\":0,\"ownerId\":1}" | must_ok
  # 注意：macOS 自带 bash 3.2 解析「双引号内嵌套 $(…) 再嵌转义引号」有 bug，须先赋值再使用
  CP=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"domain\":\"研发\",\"criterion\":\"整机连续运行100h无故障\",\"isRedline\":0,\"ownerId\":1}" | get_id)
  put "/gate-criteria/$CP" '{"status":"PARTIAL"}' | must_ok
  step "DCP2 条件：1 红线未满足（评审 PASS 将被拦截）+ 1 部分满足"

  # 跨职能准备度（技术/质量/供应/制造/上市）——技术就绪但整机未就绪
  RD=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"isReadiness\":1,\"domain\":\"技术\",\"criterion\":\"核心算法冻结\",\"ownerId\":1}" | get_id)
  put "/gate-criteria/$RD" '{"status":"MET"}' | must_ok
  RD=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"isReadiness\":1,\"domain\":\"质量\",\"criterion\":\"可靠性测试完成\",\"ownerId\":1}" | get_id)
  put "/gate-criteria/$RD" '{"status":"PARTIAL"}' | must_ok
  post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"isReadiness\":1,\"domain\":\"供应\",\"criterion\":\"激光雷达二供认证（红线）\",\"isRedline\":1,\"ownerId\":1}" | must_ok
  post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"isReadiness\":1,\"domain\":\"制造\",\"criterion\":\"试产线工装到位\",\"ownerId\":1}" | must_ok
  post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"isReadiness\":1,\"domain\":\"上市\",\"criterion\":\"上市物料与定价方案\",\"ownerId\":1}" | must_ok
  step "五领域准备度：技术已就绪，供应红线未就绪 → 局部完成但整机未就绪"

  CAP1=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"CAPABILITY\",\"title\":\"智能路径规划与避障\"}" | get_id)
  CAP2=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"CAPABILITY\",\"title\":\"自动集尘\"}" | get_id)
  R1=$(post "/work-items?parentId=$CAP1" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"沿墙清扫覆盖率≥95%\",\"priority\":\"P0\"}" | get_id)
  R2=$(post "/work-items?parentId=$CAP1" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"识别并绕开宠物粪便等高危障碍\",\"priority\":\"P0\"}" | get_id)
  R3=$(post "/work-items?parentId=$CAP2" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"尘盒满检测并自动返回集尘座\",\"priority\":\"P1\"}" | get_id)
  R4=$(post "/work-items?parentId=$CAP1" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"App 实时清扫地图与禁区设置\",\"priority\":\"P1\"}" | get_id)
  post "/work-items?parentId=$CAP1" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"语音控制启动/暂停/回充\",\"priority\":\"P2\"}" | must_ok
  step "2 能力 + 5 需求（1 条留在产品 Backlog 未排期）"

  # Sprint-1（已完成）与 Sprint-2（进行中）——看板时间倒序展示
  S1=$(post "/iterations" "{\"projectId\":$PID,\"name\":\"Sprint-1\",\"goal\":\"覆盖率达标\",\"startDate\":\"2026-07-06\",\"endDate\":\"2026-07-19\"}" | get_id)
  S2=$(post "/iterations" "{\"projectId\":$PID,\"name\":\"Sprint-2\",\"goal\":\"避障与集尘闭环\",\"startDate\":\"2026-07-20\",\"endDate\":\"2026-08-02\"}" | get_id)
  put "/iterations/$S1" '{"status":"DONE"}' | must_ok
  put "/iterations/$S2" '{"status":"ACTIVE"}' | must_ok

  # R1 完整闭环：Sprint-1 → 测试FAIL→缺陷→复测→关闭 → 需求 Accepted
  ready3 "$R1" "标准测试间实测覆盖率≥95%，三次取均值" "13"
  post "/iterations/$S1/assign/$R1" "" | must_ok
  t "$R1" "Ready"; t "$R1" "In Progress"
  put "/work-items/$R1" "{\"productVersionId\":$VID}" | must_ok
  t "$R1" "Verification"
  TC1=$(post "/tests/cases?verifiesRequirementId=$R1" "{\"projectId\":$PID,\"title\":\"沿墙覆盖率实测\",\"steps\":\"60㎡标准间三次清扫\",\"expected\":\"覆盖率≥95%\"}" | get_id)
  D1=$(post "/tests/runs" "{\"testCaseId\":$TC1,\"result\":\"FAIL\",\"actual\":\"墙角残留，实测91%\",\"runVersionId\":$VID}" | python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d['message'];print(d['data']['defectId'])")
  for s in Analysing Fixing Retesting; do t "$D1" "$s"; done
  post "/tests/runs?autoCreateDefect=false" "{\"testCaseId\":$TC1,\"result\":\"PASS\",\"actual\":\"墙角补扫策略生效，实测96.4%\",\"runVersionId\":$VID,\"defectId\":$D1}" | must_ok
  t "$D1" "Closed"
  t "$R1" "Accepted"
  step "R1 走完全流程：测试FAIL→缺陷修复→复测PASS→关闭→需求验收"

  # R2 在验证中，测试失败缺陷仍打开（进行中形态）
  ready3 "$R2" "20种障碍样本识别率≥98%，零误吸" "13"
  post "/iterations/$S2/assign/$R2" "" | must_ok
  t "$R2" "Ready"; t "$R2" "In Progress"
  put "/work-items/$R2" "{\"productVersionId\":$VID}" | must_ok
  t "$R2" "Verification"
  TC2=$(post "/tests/cases?verifiesRequirementId=$R2" "{\"projectId\":$PID,\"title\":\"高危障碍识别与绕行\",\"steps\":\"布置仿真障碍20种\",\"expected\":\"识别≥98%且零接触\"}" | get_id)
  D2=$(post "/tests/runs" "{\"testCaseId\":$TC2,\"result\":\"FAIL\",\"actual\":\"深色地毯上识别率降至89%\",\"runVersionId\":$VID}" | python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d['message'];print(d['data']['defectId'])")
  t "$D2" "Analysing"
  step "R2 验证中：缺陷 DEF 处于 Analysing（看板/缺陷页可见进行中问题）"

  # R3 开发中、R4 Ready、R5 留在 Backlog
  ready3 "$R3" "尘盒满误报率≤1%，回座成功率≥99%" "8"
  post "/iterations/$S2/assign/$R3" "" | must_ok
  t "$R3" "Ready"; t "$R3" "In Progress"
  ready3 "$R4" "地图延迟≤2s，禁区生效率100%" "5"
  post "/iterations/$S2/assign/$R4" "" | must_ok
  t "$R4" "Ready"
  step "看板：Backlog/Ready/In Progress/Verification/Accepted 五列均有卡片"

  # 变更：停在 Impact Analysed 待审批（驾驶舱"待决策变更"计数）
  CHG=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"CHANGE\",\"title\":\"集尘座风道重新设计以降低噪音\",\"description\":\"现风道实测82dB超标\"}" | get_id)
  post "/traces" "{\"projectId\":$PID,\"sourceType\":\"WORK_ITEM\",\"sourceId\":$CHG,\"targetType\":\"WORK_ITEM\",\"targetId\":$R3,\"relation\":\"changes\"}" | must_ok
  curl -s -X POST "$API/changes/$CHG/analyze" -H "$AUTH" | must_ok
  t "$CHG" "Impact Analysed"
  step "变更完成影响分析，停在待审批（可在测试缺陷页演示审批）"

  # 风险：一条已超期（红色高亮），一条正常
  post "/work-items" "{\"projectId\":$PID,\"type\":\"RISK\",\"title\":\"激光雷达交付周期延长至16周\",\"ownerId\":1,\"extFields\":\"{\\\"mitigation\\\":\\\"启动二供认证并锁定安全库存\\\",\\\"dueDate\\\":\\\"2026-07-20\\\"}\"}" | must_ok
  post "/work-items" "{\"projectId\":$PID,\"type\":\"RISK\",\"title\":\"电池包新国标认证排期紧张\",\"ownerId\":1,\"extFields\":\"{\\\"mitigation\\\":\\\"预约认证机构加急通道\\\",\\\"dueDate\\\":\\\"2026-09-15\\\"}\"}" | must_ok

  # 覆盖率条件满足并挂证据
  echo "ROBO 覆盖率实测报告：三次清扫 96.4%/95.8%/96.1%，均值 96.1% ≥ 95%，判定通过。" > /tmp/robo_coverage.txt
  curl -s -X POST "$API/evidence?projectId=$PID&linkType=GATE_CRITERION&linkId=$CM" -H "$AUTH" -F "file=@/tmp/robo_coverage.txt" | must_ok
  put "/gate-criteria/$CM" '{"status":"MET"}' | must_ok
  step "完成 ✔（DCP 页可演示：红线未满足时评审判 PASS 被拦截）"
}

# ============================================================================
# PURE 智能空气净化器V3 —— 收尾阶段
# 看点：需求全部验收、测试全绿、变更走完整闭环（→Verified）、条件豁免（理由+期限）、
#       DCP3 决策修订链（REJECT→补证据→CONDITIONAL绑风险）、历史 Sprint 隐藏
# ============================================================================
seed_pure() {
  echo "▶ PURE 智能空气净化器V3（收尾阶段）"
  PID=$(post "/projects" '{"code":"PURE","name":"智能空气净化器V3","goal":"以数字化滤芯管理与全屋联动完成旗舰迭代"}' | get_id)
  post "/product-versions" "{\"projectId\":$PID,\"versionNo\":\"V3.0-RC\"}" | must_ok
  VID=$(curl -s "$API/product-versions?projectId=$PID" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['id'])")

  GID=$(post "/stage-gates" "{\"projectId\":$PID,\"stageName\":\"发布\",\"gateName\":\"DCP3 发布决策\",\"seq\":3}" | get_id)
  C1=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"domain\":\"质量\",\"criterion\":\"CADR 实测≥600m³/h（红线）\",\"isRedline\":1,\"ownerId\":1,\"evidenceReq\":\"第三方检测报告\"}" | get_id)
  C2=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"domain\":\"质量\",\"criterion\":\"整机1000h寿命试验通过\",\"isRedline\":0,\"ownerId\":1,\"evidenceReq\":\"寿命试验记录\"}" | get_id)
  C3=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"domain\":\"上市\",\"criterion\":\"欧盟 CE 认证取证\",\"isRedline\":0,\"ownerId\":1}" | get_id)
  step "DCP3 发布决策条件已建"

  # 五领域准备度全就绪（整机就绪示例）
  local RD
  for d in 技术 质量 供应 制造 上市; do
    RD=$(post "/gate-criteria" "{\"projectId\":$PID,\"stageGateId\":$GID,\"isReadiness\":1,\"domain\":\"$d\",\"criterion\":\"${d}领域发布就绪检查\",\"ownerId\":1}" | get_id)
    put "/gate-criteria/$RD" '{"status":"MET"}' | must_ok
  done
  step "五领域准备度全 MET → 整机就绪"

  CAP1=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"CAPABILITY\",\"title\":\"高效净化\"}" | get_id)
  CAP2=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"CAPABILITY\",\"title\":\"智能滤芯管理\"}" | get_id)

  S1=$(post "/iterations" "{\"projectId\":$PID,\"name\":\"Sprint-1\",\"goal\":\"净化性能达标\",\"startDate\":\"2026-06-01\",\"endDate\":\"2026-06-14\"}" | get_id)
  S2=$(post "/iterations" "{\"projectId\":$PID,\"name\":\"Sprint-2\",\"goal\":\"滤芯管理与联动收尾\",\"startDate\":\"2026-06-15\",\"endDate\":\"2026-06-28\"}" | get_id)
  put "/iterations/$S1" '{"status":"DONE"}' | must_ok
  put "/iterations/$S2" '{"status":"DONE"}' | must_ok

  # 4 条需求全部走完（其中 R1 带缺陷闭环），测试最新结果全 PASS
  finish_req() { # $1=父能力 $2=标题 $3=AC $4=Sprint $5=用例标题 $6=是否带缺陷闭环
    local RID TCID DID
    RID=$(post "/work-items?parentId=$1" "{\"projectId\":$PID,\"type\":\"REQUIREMENT\",\"title\":\"$2\",\"priority\":\"P0\"}" | get_id)
    ready3 "$RID" "$3" "8"
    post "/iterations/$4/assign/$RID" "" | must_ok
    t "$RID" "Ready"; t "$RID" "In Progress"
    put "/work-items/$RID" "{\"productVersionId\":$VID}" | must_ok
    t "$RID" "Verification"
    TCID=$(post "/tests/cases?verifiesRequirementId=$RID" "{\"projectId\":$PID,\"title\":\"$5\"}" | get_id)
    if [ "$6" = "defect" ]; then
      DID=$(post "/tests/runs" "{\"testCaseId\":$TCID,\"result\":\"FAIL\",\"actual\":\"高档位噪音超标2dB\",\"runVersionId\":$VID}" \
        | python3 -c "import sys,json;d=json.load(sys.stdin);assert d['code']==0,d['message'];print(d['data']['defectId'])")
      for s in Analysing Fixing Retesting; do t "$DID" "$s"; done
      post "/tests/runs?autoCreateDefect=false" "{\"testCaseId\":$TCID,\"result\":\"PASS\",\"runVersionId\":$VID,\"defectId\":$DID}" | must_ok
      t "$DID" "Closed"
    else
      post "/tests/runs" "{\"testCaseId\":$TCID,\"result\":\"PASS\",\"runVersionId\":$VID}" | must_ok
    fi
    t "$RID" "Accepted"
    echo "$RID"
  }
  RQ1=$(finish_req "$CAP1" "CADR≥600m³/h 且最高档噪音≤66dB" "第三方实验室实测达标" "$S1" "CADR与噪音第三方检测" "defect" | tail -1)
  finish_req "$CAP1" "PM2.5 传感器精度±10%" "对标标准仪器偏差≤10%" "$S1" "传感器精度对标" "clean" >/dev/null
  finish_req "$CAP2" "滤芯剩余寿命按实际风量累计计算" "寿命预估误差≤5%" "$S2" "滤芯寿命算法验证" "clean" >/dev/null
  finish_req "$CAP2" "滤芯到期 App 提醒并一键下单" "提醒触达率100%" "$S2" "到期提醒链路测试" "clean" >/dev/null
  step "4 条需求全部 Accepted，测试最新结果全 PASS，缺陷闭环 1 例"

  # 变更完整闭环：分析→批准→实施→验证
  CHG=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"CHANGE\",\"title\":\"滤芯寿命算法由时间累计改为风量累计\"}" | get_id)
  post "/traces" "{\"projectId\":$PID,\"sourceType\":\"WORK_ITEM\",\"sourceId\":$CHG,\"targetType\":\"WORK_ITEM\",\"targetId\":$RQ1,\"relation\":\"affects\"}" | must_ok
  curl -s -X POST "$API/changes/$CHG/analyze" -H "$AUTH" | must_ok
  t "$CHG" "Impact Analysed"
  post "/changes/$CHG/decide" '{"approve":true,"reason":"精度收益明确，影响面已评估"}' | must_ok
  t "$CHG" "Implemented"; t "$CHG" "Verified"
  step "变更走完 提交→影响分析→批准→实施→验证 全链路"

  # 证据 + 条件判定：2 MET，CE 认证豁免（理由+期限，授权人自动记录）
  echo "PURE 第三方检测报告：CADR 实测 618m³/h，最高档噪音 65.2dB，判定通过。" > /tmp/pure_cadr.txt
  echo "PURE 寿命试验记录：1000h 连续运行完成，性能衰减 3.1%，无故障。" > /tmp/pure_life.txt
  curl -s -X POST "$API/evidence?projectId=$PID&linkType=GATE_CRITERION&linkId=$C1" -H "$AUTH" -F "file=@/tmp/pure_cadr.txt" | must_ok
  put "/gate-criteria/$C1" '{"status":"MET"}' | must_ok
  put "/gate-criteria/$C3" '{"status":"WAIVED","waiverReason":"首发仅限国内市场，CE 认证移至海外版立项","waiverDue":"2026-12-31"}' | must_ok

  # 决策修订链：第一次证据不足被 REJECT → 补证据后 CONDITIONAL（绑遗留风险+期限）
  put "/gate-criteria/$C2" '{"status":"MET"}' | must_ok
  post "/dcp/gates/$GID/review" '{"conclusion":"REJECT","reason":"寿命试验已判满足但未挂试验记录，证据缺失不予通过"}' | must_ok
  curl -s -X POST "$API/evidence?projectId=$PID&linkType=GATE_CRITERION&linkId=$C2" -H "$AUTH" -F "file=@/tmp/pure_life.txt" | must_ok
  RSK=$(post "/work-items" "{\"projectId\":$PID,\"type\":\"RISK\",\"title\":\"海外版 CE 认证周期不确定\",\"ownerId\":1,\"extFields\":\"{\\\"mitigation\\\":\\\"提前与认证机构预审\\\",\\\"dueDate\\\":\\\"2026-12-31\\\"}\"}" | get_id)
  t "$RSK" "Mitigating"
  post "/dcp/gates/$GID/review" "{\"conclusion\":\"CONDITIONAL\",\"reason\":\"发布条件满足，遗留海外认证承诺\",\"linkedRiskId\":$RSK,\"commitmentDue\":\"2026-12-31\"}" | must_ok
  step "决策历史：REJECT（证据缺失）→ 补证据 → CONDITIONAL 绑风险+期限（只增不改）"

  # 历史 Sprint-1 隐藏（演示看板隐藏能力）
  put "/iterations/$S1" '{"hidden":1}' | must_ok
  step "完成 ✔（Sprint-1 已隐藏，看板勾选「显示已隐藏」可见）"
}

for p in EBK ROBO PURE; do
  if exists "$p"; then echo "⏭  项目 $p 已存在，跳过"; continue; fi
  case "$p" in
    EBK)  seed_ebk ;;
    ROBO) seed_robo ;;
    PURE) seed_pure ;;
  esac
done

echo
echo "=== 示例项目就绪。建议演示动线 ==="
echo "  ① 项目页看三个项目定位 → ② EBK 看需求树与 DCP1 决策"
echo "  ③ ROBO 看 Sprint 看板流动/进行中缺陷/待审批变更/超期风险，"
echo "     并在 DCP 页尝试判 PASS（会被红线拦截）"
echo "  ④ PURE 看全量验收/变更闭环/豁免/决策修订链/隐藏 Sprint → ⑤ 驾驶舱对比三项目指标"
