#!/usr/bin/env bash
# 初始化独立应用角色；仅用于新卷，不自动改变已有数据库身份。
# Initialize a separate application role for new volumes; never alter identities in existing databases.
set -euo pipefail
: "${LUMIRA_DB_PASSWORD:?Set the application database password}"
if [[ "$LUMIRA_DB_PASSWORD" == "${POSTGRES_PASSWORD:-}" ]]; then
  echo "Bootstrap and application database passwords must differ" >&2
  exit 1
fi
export LUMIRA_DB_APP_USER="${LUMIRA_DB_APP_USER:-lumira_next}"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 <<'SQL'
\getenv app_password LUMIRA_DB_PASSWORD
\getenv app_user LUMIRA_DB_APP_USER
\getenv app_database POSTGRES_DB
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO :"app_user";
GRANT CONNECT, TEMPORARY ON DATABASE :"app_database" TO :"app_user";
SQL
