#!/usr/bin/env bash
# 数据库 + 证据文件一键备份（T704）。用法：./backup.sh [输出目录]
set -euo pipefail

OUT_DIR="${1:-./backups}"
TS="$(date +%Y%m%d_%H%M%S)"
DEST="${OUT_DIR}/ipd_backup_${TS}"
mkdir -p "${DEST}"

echo "[1/2] 导出数据库 ipd_toolbox ..."
docker exec ipd-mysql mysqldump -uroot -proot123456 \
  --default-character-set=utf8mb4 --single-transaction --routines --triggers \
  ipd_toolbox > "${DEST}/ipd_toolbox.sql"

echo "[2/2] 打包证据文件目录 ..."
EVIDENCE_DIR="${EVIDENCE_ROOT:-../backend/data/evidence}"
if [ -d "${EVIDENCE_DIR}" ]; then
  tar -czf "${DEST}/evidence.tar.gz" -C "$(dirname "${EVIDENCE_DIR}")" "$(basename "${EVIDENCE_DIR}")"
else
  echo "  证据目录 ${EVIDENCE_DIR} 不存在，跳过"
fi

echo "备份完成：${DEST}"
ls -lh "${DEST}"
