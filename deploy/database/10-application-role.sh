#!/usr/bin/env bash
# 新卷分别建立迁移和业务角色；已有数据库必须按运维流程显式升级。
# Create separate migration and runtime roles for new volumes; existing databases require an explicit upgrade.
set -euo pipefail
: "${LUMIRA_DB_PASSWORD:?Set the runtime database password}"
: "${LUMIRA_DB_MIGRATION_PASSWORD:?Set the migration database password}"
if [[ "$LUMIRA_DB_PASSWORD" == "${POSTGRES_PASSWORD:-}" || "$LUMIRA_DB_MIGRATION_PASSWORD" == "${POSTGRES_PASSWORD:-}" || "$LUMIRA_DB_PASSWORD" == "$LUMIRA_DB_MIGRATION_PASSWORD" ]]; then
  echo "Bootstrap, migration and runtime database passwords must differ" >&2
  exit 1
fi
export LUMIRA_DB_APP_USER="${LUMIRA_DB_APP_USER:-lumira_next}"
export LUMIRA_DB_MIGRATION_USER="${LUMIRA_DB_MIGRATION_USER:-lumira_migrator}"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 <<'SQL'
\getenv app_password LUMIRA_DB_PASSWORD
\getenv app_user LUMIRA_DB_APP_USER
\getenv migration_password LUMIRA_DB_MIGRATION_PASSWORD
\getenv migration_user LUMIRA_DB_MIGRATION_USER
\getenv app_database POSTGRES_DB
CREATE ROLE :"migration_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'migration_password';
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO :"migration_user";
GRANT USAGE ON SCHEMA public TO :"app_user";
GRANT CONNECT, TEMPORARY ON DATABASE :"app_database" TO :"app_user", :"migration_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_user" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_user" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";
SQL
