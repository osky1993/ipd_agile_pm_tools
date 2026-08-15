#!/usr/bin/env bash
# 从备份目录恢复数据库 + 证据文件（T704）。
#
# 用法：
#   ./restore.sh <备份目录>                    恢复到主库 ipd_toolbox（含证据文件）
#   ./restore.sh <备份目录> <目标库名>          恢复到指定库（**不动证据文件**，供演练用）
#
# 恢复到非主库时刻意跳过证据解包：演练不该覆盖你正在用的证据目录。
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

SRC="${1:?用法: ./restore.sh <备份目录，如 ./backups/ipd_backup_20260730_120000> [目标库名]}"
TARGET_DB="${2:-ipd_toolbox}"
[ -f "${SRC}/ipd_toolbox.sql" ] || { echo "找不到 ${SRC}/ipd_toolbox.sql"; exit 1; }

echo "[1/2] 恢复数据库 → ${TARGET_DB} ..."
docker exec -i ipd-mysql mysql -uroot -proot123456 --default-character-set=utf8mb4 \
  -e "DROP DATABASE IF EXISTS ${TARGET_DB}; CREATE DATABASE ${TARGET_DB} CHARACTER SET utf8mb4;"
docker exec -i ipd-mysql mysql -uroot -proot123456 --default-character-set=utf8mb4 "${TARGET_DB}" < "${SRC}/ipd_toolbox.sql"

# V3 的三个 SQL 视图是 DEFINER=`ipd`@`%` + SQL SECURITY DEFINER，而 ipd 账号默认只对
# ipd_toolbox 有权限。恢复到别的库名时，视图建得出来但一查就报 ERROR 1356
# （definer 无权限）。恢复回主库不受影响，但演练必须补这个授权才验得到视图。
if [ "${TARGET_DB}" != "ipd_toolbox" ]; then
  docker exec -i ipd-mysql mysql -uroot -proot123456 \
    -e "GRANT ALL PRIVILEGES ON ${TARGET_DB}.* TO 'ipd'@'%'; FLUSH PRIVILEGES;"
fi

echo "[2/2] 恢复证据文件 ..."
if [ "${TARGET_DB}" != "ipd_toolbox" ]; then
  echo "  目标库非主库（${TARGET_DB}），跳过证据解包——演练不覆盖在用的证据目录"
elif [ -f "${SRC}/evidence.tar.gz" ]; then
  EVIDENCE_DIR="${EVIDENCE_ROOT:-$ROOT/backend/data/evidence}"
  mkdir -p "$(dirname "${EVIDENCE_DIR}")"
  tar -xzf "${SRC}/evidence.tar.gz" -C "$(dirname "${EVIDENCE_DIR}")"
  echo "  证据已恢复到 ${EVIDENCE_DIR}"
else
  echo "  无证据备份，跳过"
fi

echo "恢复完成。重启后端以生效。"
