#!/usr/bin/env bash
# 数据库 + 证据文件一键备份（T704）。
#
# 用法：
#   ./backup.sh                 备份到 deploy/backups/
#   ./backup.sh <输出目录>       备份到指定目录
#
# 路径一律基于脚本自身位置解析，因此**从任何工作目录调用都成立**（launchd 无固定 cwd）。
# 跑完自动按保留策略滚动清理（近 14 天全留 + 更早的每周留 1 份）。
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

OUT_DIR="${1:-$SCRIPT_DIR/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
TS="$(date +%Y%m%d_%H%M%S)"
DEST="${OUT_DIR}/ipd_backup_${TS}"
mkdir -p "${DEST}"

echo "[1/3] 导出数据库 ipd_toolbox ..."
docker exec ipd-mysql mysqldump -uroot -proot123456 \
  --default-character-set=utf8mb4 --single-transaction --routines --triggers \
  ipd_toolbox > "${DEST}/ipd_toolbox.sql"

echo "[2/3] 打包证据文件目录 ..."
EVIDENCE_DIR="${EVIDENCE_ROOT:-$ROOT/backend/data/evidence}"
if [ -d "${EVIDENCE_DIR}" ]; then
  tar -czf "${DEST}/evidence.tar.gz" -C "$(dirname "${EVIDENCE_DIR}")" "$(basename "${EVIDENCE_DIR}")"
else
  echo "  证据目录 ${EVIDENCE_DIR} 不存在，跳过"
fi

# ---------- 3. 保留策略：近 KEEP_DAYS 天全留；更早的每个自然周只留最新一份 ----------
echo "[3/3] 滚动清理旧备份（近 ${KEEP_DAYS} 天全留 + 更早每周留 1 份）..."
NOW_S=$(date +%s)
KEPT_WEEKS=""
REMOVED=0

# 倒序遍历（新 → 旧），保证每周留下的是那一周里最新的一份
for dir in $(ls -1d "${OUT_DIR}"/ipd_backup_* 2>/dev/null | sort -r); do
  [ -d "$dir" ] || continue
  base=$(basename "$dir")
  day=$(echo "$base" | sed -n 's/^ipd_backup_\([0-9]\{8\}\)_.*/\1/p')
  [ -n "$day" ] || continue   # 名字不合规的目录不碰

  # macOS date：-j 不改系统时间，-f 指定输入格式
  ts=$(date -j -f "%Y%m%d" "$day" +%s 2>/dev/null) || continue
  age_days=$(( (NOW_S - ts) / 86400 ))

  if [ "$age_days" -le "$KEEP_DAYS" ]; then
    continue
  fi

  week=$(date -j -f "%Y%m%d" "$day" +%G-W%V 2>/dev/null) || continue
  case " $KEPT_WEEKS " in
    *" $week "*)
      rm -rf "$dir"
      REMOVED=$((REMOVED + 1))
      ;;
    *)
      KEPT_WEEKS="$KEPT_WEEKS $week"
      ;;
  esac
done
echo "  清理了 ${REMOVED} 份过期备份"

echo
echo "备份完成：${DEST}"
ls -lh "${DEST}"
