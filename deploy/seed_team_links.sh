#!/usr/bin/env bash
# 团队协作屏演示依赖链（走真实 API，按编号取 id 不写死）。
# ROBO 三条链讲三个故事：
#   R3 depends_on R2 —— 回座导航复用避障绕行能力（R3 In Progress → DEP_BLOCKED）
#   R2 blocks R4     —— 障碍物图层数据结构由避障模型输出定义（R4 Ready → DEP_BLOCKED）
#   R5 depends_on R4 —— 语音指令走 App 通道（R5 挂入活跃迭代保持 Backlog → DEP_UPCOMING）
# EBK/PURE 不建链：分别演示"空态引导"与"无阻塞全绿"。幂等：链已存在则 API 报重复，忽略。
set -uo pipefail

API="${API:-http://localhost:8080/api}"
CT="Content-Type: application/json"

JWT=$(curl -s -X POST "$API/auth/login" -H "$CT" -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
AUTH="Authorization: Bearer $JWT"

PID=$(curl -s "$API/projects" -H "$AUTH" | python3 -c "
import sys,json
print([p for p in json.load(sys.stdin)['data'] if p['code']=='ROBO'][0]['id'])")

id_of() { # 按编号取工作项 id
  curl -s "$API/work-items?projectId=$PID" -H "$AUTH" | python3 -c "
import sys,json
print([w for w in json.load(sys.stdin)['data'] if w['code']=='$1'][0]['id'])"
}

R2=$(id_of ROBO-REQ-002); R3=$(id_of ROBO-REQ-003); R4=$(id_of ROBO-REQ-004); R5=$(id_of ROBO-REQ-005)
S2=$(curl -s "$API/iterations?projectId=$PID" -H "$AUTH" | python3 -c "
import sys,json
print([i for i in json.load(sys.stdin)['data'] if i['name']=='Sprint-2'][0]['id'])")

mklink() { # source target relation（重复建链报错则忽略）
  curl -s -X POST "$API/traces" -H "$CT" -H "$AUTH" \
    -d "{\"projectId\":$PID,\"sourceType\":\"WORK_ITEM\",\"sourceId\":$1,\"targetType\":\"WORK_ITEM\",\"targetId\":$2,\"relation\":\"$3\"}" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('  ✓' if d['code']==0 else '  ⏭ '+d['message'][:36])"
}

echo "▶ ROBO 依赖链"
echo "R3 depends_on R2（回座复用避障能力）"; mklink "$R3" "$R2" depends_on
echo "R2 blocks R4（障碍图层定义地图数据结构）"; mklink "$R2" "$R4" blocks
echo "R5 depends_on R4（语音指令走 App 通道）"; mklink "$R5" "$R4" depends_on

# R5 需先满足 Ready 三要素才能挂入迭代（保持 Backlog 状态 → DEP_UPCOMING）
curl -s -X PUT "$API/work-items/$R5" -H "$CT" -H "$AUTH" \
  -d '{"acceptanceCriteria":"语音识别率≥95%，指令响应≤1s","ownerId":1,"estimate":"5"}' > /dev/null
curl -s -X POST "$API/iterations/$S2/assign/$R5" -H "$AUTH" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('R5 已挂入 Sprint-2（保持 Backlog）' if d['code']==0 else '  ⏭ '+d['message'][:40])"
echo "完成 ✔"
