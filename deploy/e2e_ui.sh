#!/usr/bin/env bash
# UI e2e 薄保护网一键运行（docs/07 §1 A1）。
#
# 起一套**完全独立**的环境跑三条动线，跑完清理：
#   独立库 ipd_toolbox_e2e（同一个 ipd-mysql 容器）+ 后端 18080 + 前端 15173 + 临时证据目录
# 你日常 dogfooding 的实例（ipd_toolbox / 8080 / 5173）全程不受影响，也绝不会被 kill。
#
# 用法：
#   ./deploy/e2e_ui.sh                 跑全部三条动线
#   ./deploy/e2e_ui.sh --keep          跑完保留环境（连续调试用例时用）
#   ./deploy/e2e_ui.sh --headed        开浏览器窗口看
#   ./deploy/e2e_ui.sh --grep 03       只跑动线 3
#   其余参数原样透传给 playwright test
#
# 参考耗时：MySQL 就绪 ~2s + 后端 ~20s + 前端 ~3s + 用例 ~60s。若哪天明显变长，本身就是个信号。
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

E2E_DB="${E2E_DB:-ipd_toolbox_e2e}"
E2E_API_PORT="${E2E_API_PORT:-18080}"
E2E_WEB_PORT="${E2E_WEB_PORT:-15173}"
MYSQL_CT="ipd-mysql"
MYSQL_ROOT_PW="root123456"
RUN_DIR="$ROOT/deploy/.e2e"
EVIDENCE_ROOT="$ROOT/data/e2e-evidence"
JAR="$ROOT/backend/target/ipd-toolbox.jar"
API="http://127.0.0.1:$E2E_API_PORT/api"

KEEP=0
FORCE_BUILD=0
SKIP_BUILD=0
PW_ARGS=""
for arg in "$@"; do
  case "$arg" in
    --keep)       KEEP=1 ;;
    --build)      FORCE_BUILD=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    *)            PW_ARGS="$PW_ARGS $arg" ;;
  esac
done

BE_PID=""
FE_PID=""

port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

wait_port_free() {
  p=$1; limit=$2; i=0
  while [ "$i" -lt "$limit" ]; do
    port_busy "$p" || return 0
    i=$((i + 1)); sleep 1
  done
  return 1
}

# ---------- §0 清理（最先注册，早于任何启动动作） ----------
cleanup() {
  rc=$?
  set +e
  if [ "$KEEP" = "1" ]; then
    echo
    echo "--keep：环境保留（后端 pid=$BE_PID 端口 $E2E_API_PORT，前端 pid=$FE_PID 端口 $E2E_WEB_PORT）"
    echo "  单独跑用例：cd $ROOT/frontend && E2E_BASE_URL=http://127.0.0.1:$E2E_WEB_PORT E2E_API=$API npx playwright test"
    echo "  收工请手动：kill $FE_PID $BE_PID"
    exit $rc
  fi
  echo
  echo "[cleanup] 停止前端与后端 ..."
  [ -n "$FE_PID" ] && kill "$FE_PID" 2>/dev/null
  [ -n "$BE_PID" ] && kill "$BE_PID" 2>/dev/null
  wait_port_free "$E2E_WEB_PORT" 10 || { [ -n "$FE_PID" ] && kill -9 "$FE_PID" 2>/dev/null; }
  wait_port_free "$E2E_API_PORT" 10 || { [ -n "$BE_PID" ] && kill -9 "$BE_PID" 2>/dev/null; }
  # 兜底只按 e2e 专属 JVM 标记杀。
  # 绝不能写 pkill -f ipd-toolbox.jar —— 那会连带杀掉你正在用的 dogfooding 后端。
  pkill -9 -f "ipd.e2e=1" 2>/dev/null
  rm -rf "$EVIDENCE_ROOT"
  if [ "$rc" != "0" ]; then
    echo
    echo "失败。后端日志：$RUN_DIR/backend.log"
    echo "      前端日志：$RUN_DIR/frontend.log"
    echo "      失败报告：cd $ROOT/frontend && npx playwright show-report"
    echo "      测试库 $E2E_DB 已保留，可直接查库核对（下次运行会自动重建）"
  fi
  exit $rc
}
trap cleanup EXIT INT TERM

# ---------- §1 前置检查 ----------
echo "[1/7] 前置检查 ..."
command -v docker >/dev/null 2>&1 || { echo "  缺少 docker"; exit 1; }
command -v node >/dev/null 2>&1 || { echo "  缺少 node"; exit 1; }

# 本机默认 java 是 1.8，后端需要 JDK 21
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
fi
[ -x "$JAVA_HOME/bin/java" ] || { echo "  找不到 JDK 21：$JAVA_HOME"; exit 1; }
export JAVA_HOME

if port_busy "$E2E_API_PORT"; then
  echo "  端口 $E2E_API_PORT 已被占用。请先释放，或用 E2E_API_PORT=xxxxx 换端口。"
  echo "  （注意：本脚本刻意不会自动 kill 占用进程，以免误杀你正在用的实例）"
  exit 1
fi
if port_busy "$E2E_WEB_PORT"; then
  echo "  端口 $E2E_WEB_PORT 已被占用。请先释放，或用 E2E_WEB_PORT=xxxxx 换端口。"
  exit 1
fi

# vite 解析配置时 .js 优先于 .ts：一个陈旧的 vite.config.js 会静默顶掉 vite.config.ts，
# 导致代理目标仍指向 8080（实测踩过，表现为前端起得来但所有接口 ECONNREFUSED）
if [ -f "$ROOT/frontend/vite.config.js" ]; then
  echo "  发现陈旧的 frontend/vite.config.js，它会顶掉 vite.config.ts。已删除。"
  rm -f "$ROOT/frontend/vite.config.js" "$ROOT/frontend/vite.config.d.ts"
fi

[ -d "$ROOT/frontend/node_modules/@playwright/test" ] || {
  echo "  安装 @playwright/test ..."
  npm i -D @playwright/test --prefix "$ROOT/frontend"
}
( cd "$ROOT/frontend" && npx playwright install chromium >/dev/null 2>&1 ) || true

mkdir -p "$RUN_DIR"
rm -rf "$EVIDENCE_ROOT"

# ---------- §2 起 / 等 MySQL ----------
echo "[2/7] 等待 MySQL 容器 $MYSQL_CT ..."
running=$(docker ps --filter "name=^${MYSQL_CT}$" --format '{{.Names}}')
if [ -z "$running" ]; then
  docker compose -f "$ROOT/deploy/docker-compose.yml" up -d mysql
fi
st=""
i=0
while [ "$i" -lt 120 ]; do
  st=$(docker inspect -f '{{.State.Health.Status}}' "$MYSQL_CT" 2>/dev/null || echo "")
  [ "$st" = "healthy" ] && break
  i=$((i + 1)); sleep 1
done
[ "$st" = "healthy" ] || { echo "  MySQL 未就绪（当前状态：$st）"; exit 1; }

# ---------- §3 重建测试库 ----------
# 保险 1：库名必须以 _e2e 结尾，杜绝把主库 drop 掉的可能
case "$E2E_DB" in
  *_e2e) : ;;
  *) echo "拒绝执行：e2e 库名必须以 _e2e 结尾（当前：$E2E_DB）"; exit 1 ;;
esac
# 保险 2：只 DROP 这一个库，绝不 compose down -v、不碰命名卷、不删容器
# 保险 3：所有 DB 操作一律 docker exec 按容器名走，永不经宿主机端口
#         （本机另有无关容器 mysql845 占 23306，这样也一并挡掉了连错的可能）
echo "[3/7] 重建测试库 $E2E_DB ..."
SQL="DROP DATABASE IF EXISTS $E2E_DB;"
SQL="$SQL CREATE DATABASE $E2E_DB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
SQL="$SQL GRANT ALL PRIVILEGES ON $E2E_DB.* TO 'ipd'@'%';"
SQL="$SQL FLUSH PRIVILEGES;"
docker exec -i "$MYSQL_CT" mysql -uroot -p"$MYSQL_ROOT_PW" -e "$SQL"

# ---------- §4 打包后端（源码比 jar 新才打） ----------
NEED_BUILD=0
[ -f "$JAR" ] || NEED_BUILD=1
if [ -f "$JAR" ]; then
  newer=$(find "$ROOT/backend/src" "$ROOT/backend/pom.xml" -newer "$JAR" -type f 2>/dev/null | head -1)
  [ -n "$newer" ] && NEED_BUILD=1
fi
[ "$FORCE_BUILD" = "1" ] && NEED_BUILD=1
[ "$SKIP_BUILD" = "1" ] && NEED_BUILD=0
if [ "$NEED_BUILD" = "1" ]; then
  echo "[4/7] 源码比 jar 新，重新打包后端（改了后端不重打会跑到旧代码）..."
  ( cd "$ROOT/backend" && mvn -q -DskipTests package )
else
  echo "[4/7] jar 已是最新，跳过打包"
fi

# ---------- §5 起后端 ----------
echo "[5/7] 启动后端（:$E2E_API_PORT，库 $E2E_DB）..."
DS_URL="jdbc:mysql://127.0.0.1:3306/${E2E_DB}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
SPRING_DATASOURCE_URL="$DS_URL" \
DB_USER=ipd DB_PASSWORD=ipd123456 \
SERVER_PORT="$E2E_API_PORT" \
EVIDENCE_ROOT="$EVIDENCE_ROOT" \
nohup "$JAVA_HOME/bin/java" -Dipd.e2e=1 -jar "$JAR" > "$RUN_DIR/backend.log" 2>&1 &
BE_PID=$!
disown %% 2>/dev/null || true   # 免得 kill 时 shell 打一行 "Killed: 9" 噪音

# 等到真的能登录为止：Web 容器在 DataInitializer（建 admin）之前就监听端口了，
# 那个窗口里 login 会返回 4010，只探端口会误判为就绪。
i=0
ok=0
while [ "$i" -lt 90 ]; do
  if ! kill -0 "$BE_PID" 2>/dev/null; then
    echo "  后端进程已退出："
    tail -40 "$RUN_DIR/backend.log"
    exit 1
  fi
  body=$(curl -s -X POST "$API/auth/login" -H 'Content-Type: application/json' \
         -d '{"username":"admin","password":"admin123"}' 2>/dev/null || true)
  case "$body" in
    *'"token"'*) ok=1; break ;;
  esac
  i=$((i + 1)); sleep 1
done
[ "$ok" = "1" ] || { echo "  后端 90s 未就绪："; tail -60 "$RUN_DIR/backend.log"; exit 1; }
echo "  后端就绪（Flyway 已在空库上跑完 V1~V13，admin 账号已建）"

# ---------- §6 起前端 dev server ----------
echo "[6/7] 启动前端（:$E2E_WEB_PORT → 代理到 :$E2E_API_PORT）..."
# 直接调 node_modules/.bin/vite 而不是 npm run dev：
# npm run 会多包一层 shell，$FE_PID 指向 npm，kill 后 vite 会变孤儿继续占端口。
cd "$ROOT/frontend"
# --host 127.0.0.1：vite 默认绑 localhost，在本机会只监听 IPv6(::1)，
# 导致 curl/Playwright 走 127.0.0.1 连不上。显式绑 IPv4。
VITE_API_TARGET="http://127.0.0.1:$E2E_API_PORT" \
nohup ./node_modules/.bin/vite --host 127.0.0.1 --port "$E2E_WEB_PORT" --strictPort \
  > "$RUN_DIR/frontend.log" 2>&1 &
FE_PID=$!
disown %% 2>/dev/null || true

i=0
ok=0
while [ "$i" -lt 60 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$E2E_WEB_PORT/" 2>/dev/null || true)
  [ "$code" = "200" ] && { ok=1; break; }
  i=$((i + 1)); sleep 1
done
[ "$ok" = "1" ] || { echo "  前端 60s 未就绪："; tail -40 "$RUN_DIR/frontend.log"; exit 1; }

# ---------- §7 跑用例 ----------
echo "[7/7] 运行 Playwright ..."
echo
set +e
E2E_BASE_URL="http://127.0.0.1:$E2E_WEB_PORT" \
E2E_API="$API" \
npx playwright test $PW_ARGS
RC=$?
set -e
exit $RC
