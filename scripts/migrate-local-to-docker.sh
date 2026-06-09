#!/usr/bin/env bash
# 本机 MySQL (3306) → Docker MySQL (3307) 数据迁移
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_HOST="${LOCAL_MYSQL_HOST:-127.0.0.1}"
LOCAL_PORT="${LOCAL_MYSQL_PORT:-3306}"
DOCKER_HOST="${DOCKER_MYSQL_HOST:-127.0.0.1}"
DOCKER_PORT="${DOCKER_MYSQL_PORT:-3307}"
DOCKER_PASS="${DOCKER_MYSQL_PASSWORD:-starshield}"
DUMP="/tmp/starshield_migrate.sql"
TABLES=(chat_message_log moderation_audit_log daily_report_cache)

echo "==> [1/4] Docker MySQL 补全 daily_report_cache 表结构"
mysql -u root -p"${DOCKER_PASS}" -h "${DOCKER_HOST}" -P "${DOCKER_PORT}" starshield \
  < "${ROOT}/starshield-backend/src/main/resources/migrate_daily_report.sql"

if [[ -z "${MYSQL_LOCAL_PASSWORD:-}" ]]; then
  read -rsp "本机 MySQL root 密码: " MYSQL_LOCAL_PASSWORD
  echo
fi

echo "==> [2/4] 从本机导出: ${TABLES[*]}"
export MYSQL_PWD="${MYSQL_LOCAL_PASSWORD}"
mysqldump -u root -h "${LOCAL_HOST}" -P "${LOCAL_PORT}" \
  --single-transaction \
  --set-gtid-purged=OFF \
  --no-tablespaces \
  starshield "${TABLES[@]}" > "${DUMP}"
unset MYSQL_PWD

LINES=$(wc -l < "${DUMP}" | tr -d ' ')
INSERTS=$(grep -c "INSERT INTO" "${DUMP}" || true)
echo "    dump: ${LINES} 行, ${INSERTS} 条 INSERT"

if [[ "${INSERTS}" -eq 0 ]]; then
  echo "警告: 导出文件无 INSERT，本机表可能为空。"
fi

echo "==> [3/4] 导入 Docker MySQL (${DOCKER_HOST}:${DOCKER_PORT})"
mysql -u root -p"${DOCKER_PASS}" -h "${DOCKER_HOST}" -P "${DOCKER_PORT}" starshield < "${DUMP}"

echo "==> [4/4] 验证行数"
mysql -u root -p"${DOCKER_PASS}" -h "${DOCKER_HOST}" -P "${DOCKER_PORT}" starshield -e "
  SELECT 'chat_message_log' AS t, COUNT(*) AS n FROM chat_message_log
  UNION ALL SELECT 'moderation_audit_log', COUNT(*) FROM moderation_audit_log
  UNION ALL SELECT 'daily_report_cache', COUNT(*) FROM daily_report_cache
  UNION ALL SELECT 'crawl_task', COUNT(*) FROM crawl_task;
"

echo "迁移完成: ${DUMP}"
