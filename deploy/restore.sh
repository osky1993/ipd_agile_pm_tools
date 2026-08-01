#!/usr/bin/env bash
# 从备份目录恢复数据库 + 证据文件（T704）。用法：./restore.sh <备份目录>
set -euo pipefail

SRC="${1:?用法: ./restore.sh <备份目录，如 ./backups/ipd_backup_20260730_120000>}"
[ -f "${SRC}/ipd_toolbox.sql" ] || { echo "找不到 ${SRC}/ipd_toolbox.sql"; exit 1; }

echo "[1/2] 恢复数据库 ..."
docker exec -i ipd-mysql mysql -uroot -proot123456 --default-character-set=utf8mb4 \
  -e "DROP DATABASE IF EXISTS ipd_toolbox; CREATE DATABASE ipd_toolbox CHARACTER SET utf8mb4;"
docker exec -i ipd-mysql mysql -uroot -proot123456 --default-character-set=utf8mb4 ipd_toolbox < "${SRC}/ipd_toolbox.sql"

echo "[2/2] 恢复证据文件 ..."
EVIDENCE_DIR="${EVIDENCE_ROOT:-../backend/data/evidence}"
if [ -f "${SRC}/evidence.tar.gz" ]; then
  mkdir -p "$(dirname "${EVIDENCE_DIR}")"
  tar -xzf "${SRC}/evidence.tar.gz" -C "$(dirname "${EVIDENCE_DIR}")"
  echo "  证据已恢复到 ${EVIDENCE_DIR}"
else
  echo "  无证据备份，跳过"
fi

echo "恢复完成。重启后端以生效。"
