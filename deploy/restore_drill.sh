#!/usr/bin/env bash
# 每月一次的恢复演练（docs/07 §1 A2）。
#
# 备份跑得再勤，没验过就不算有兜底。本脚本把**最新一份备份**恢复到临时库
# ipd_toolbox_drill（绝不覆盖主库、绝不动证据目录），核对关键表计数与 SQL 视图
# 是否都在，然后把临时库丢掉。
#
# 用法：
#   ./restore_drill.sh                 演练最新一份备份
#   ./restore_drill.sh <备份目录>       演练指定备份
#   KEEP_DRILL_DB=1 ./restore_drill.sh 演练后保留临时库（想手工查库时用）
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
DRILL_DB="ipd_toolbox_drill"
MAIN_DB="ipd_toolbox"
MYSQL="docker exec -i ipd-mysql mysql -uroot -proot123456 -N -B"

# 保险：临时库名必须以 _drill 结尾，杜绝写错库名把主库 drop 掉
case "$DRILL_DB" in
  *_drill) : ;;
  *) echo "拒绝执行：演练库名必须以 _drill 结尾（当前：$DRILL_DB）"; exit 1 ;;
esac

SRC="${1:-}"
if [ -z "$SRC" ]; then
  SRC=$(ls -1d "$SCRIPT_DIR"/backups/ipd_backup_* 2>/dev/null | sort | tail -1)
fi
[ -n "$SRC" ] && [ -d "$SRC" ] || { echo "找不到任何备份，请先跑 ./backup.sh"; exit 1; }

echo "=== 恢复演练：$(basename "$SRC") → ${DRILL_DB} ==="
echo

cleanup() {
  if [ "${KEEP_DRILL_DB:-0}" = "1" ]; then
    echo
    echo "KEEP_DRILL_DB=1：临时库 ${DRILL_DB} 已保留，手工查完请自行 DROP"
    return
  fi
  echo
  echo "[4/4] 丢弃临时库 ${DRILL_DB} ..."
  $MYSQL -e "DROP DATABASE IF EXISTS ${DRILL_DB};" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[1/4] 恢复到临时库（不覆盖主库、不动证据目录）..."
"$SCRIPT_DIR/restore.sh" "$SRC" "$DRILL_DB" >/dev/null

echo "[2/4] 核对关键表计数（主库 vs 恢复库）..."
FAIL=0
for t in project work_item decision baseline evidence trace_link audit_event improvement; do
  a=$($MYSQL -e "SELECT COUNT(*) FROM ${MAIN_DB}.${t};" 2>/dev/null || echo "ERR")
  b=$($MYSQL -e "SELECT COUNT(*) FROM ${DRILL_DB}.${t};" 2>/dev/null || echo "ERR")
  if [ "$a" = "$b" ]; then
    printf '  ✓ %-14s %s\n' "$t" "$b"
  else
    printf '  ✗ %-14s 主库=%s 恢复库=%s\n' "$t" "$a" "$b"
    FAIL=1
  fi
done
echo "  （主库在备份之后又有写入时计数会不一致，属正常——看的是"恢复库不为空且量级吻合"）"

echo "[3/4] 核对 SQL 视图是否随 dump 一并还原 ..."
for v in v_project_metrics v_requirement_coverage v_testcase_latest; do
  n=$($MYSQL -e "SELECT COUNT(*) FROM information_schema.views WHERE table_schema='${DRILL_DB}' AND table_name='${v}';")
  if [ "$n" = "1" ]; then
    # 视图存在还不够，得能查得动
    if $MYSQL -e "SELECT COUNT(*) FROM ${DRILL_DB}.${v};" >/dev/null 2>&1; then
      printf '  ✓ %s\n' "$v"
    else
      printf '  ✗ %s 存在但查询失败\n' "$v"
      FAIL=1
    fi
  else
    printf '  ✗ %s 缺失\n' "$v"
    FAIL=1
  fi
done

echo
if [ "$FAIL" = "0" ]; then
  echo "演练通过 ✅  备份 $(basename "$SRC") 可用。"
  echo "请把本次演练日期记入 TOOLBOX 项目（docs/07 §1 A2 约定每月一次）。"
else
  echo "演练发现问题 ⚠️  上面标 ✗ 的项需要人工确认。"
fi
exit $FAIL
