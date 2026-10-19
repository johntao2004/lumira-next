#!/usr/bin/env bash
# 一次性 CI 部署；只清理当前 run 的 compose 项目。Disposable CI, no saved configurations.
set -euo pipefail
[[ ${GITHUB_ACTIONS:-} == true && -n ${GITHUB_RUN_ID:-} && -n ${GITHUB_RUN_ATTEMPT:-} ]]
root=$(pwd)
mkdir -p .runtime/browser-ci/evidence
umask 077
export LUMIRA_BROWSER_CI_CONFIG="$root/.runtime/browser-ci/private.json"
python3 - <<'PY'
import os,json,secrets,base64
from pathlib import Path
root=Path('.runtime/browser-ci')
password=secrets.token_urlsafe(32)
env={'LUMIRA_DB_BOOTSTRAP_PASSWORD':secrets.token_urlsafe(32),'LUMIRA_DB_PASSWORD':secrets.token_urlsafe(32),'LUMIRA_DB_MIGRATION_PASSWORD':secrets.token_urlsafe(32),'LUMIRA_BOOTSTRAP_PASSWORD':password,'LUMIRA_SECRET_KEYS':'primary='+base64.b64encode(secrets.token_bytes(32)).decode(),'LUMIRA_SECRET_ACTIVE_KEY':'primary','LUMIRA_OFFICE_WORKER_TOKEN':secrets.token_urlsafe(48),'LUMIRA_COOKIE_SECURE':'false'}
(root/'private.env').write_text('\n'.join(f'{k}={v}' for k,v in env.items())+'\n')
(root/'private.json').write_text(json.dumps({'runId':os.environ['GITHUB_RUN_ID'],'bootstrapPassword':password}))
PY
cat > .runtime/browser-ci/compose.override.yml <<'YAML'
services:
  server:
    image: lumira-server:ci
  office-worker:
    image: lumira-office-worker:ci
  web:
    image: lumira-web:ci
  edge:
    image: lumira-edge:ci
YAML
project="lumira-browser-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
compose=(docker compose --project-name "$project" --env-file "$root/.runtime/browser-ci/private.env" -f deploy/compose.yml -f "$root/.runtime/browser-ci/compose.override.yml")
cleanup() {
  # 日志和原始 trace 可能含合成凭据，不自动上传。
  # Logs and raw traces may include synthetic secrets; never upload them automatically.
  "${compose[@]}" logs --no-color > .runtime/browser-ci/private-services.log 2>&1 || true
  "${compose[@]}" down --volumes --remove-orphans || true
}
trap cleanup EXIT
"${compose[@]}" up --no-build --detach --wait --wait-timeout 240
# 验证真实启动后的对象所有权及历史权限，不输出任何凭据。
# Verify ownership and history permissions after real startup without printing credentials.
"${compose[@]}" exec -T database psql -U lumira_bootstrap -d lumira_next -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF has_schema_privilege('lumira_next','public','CREATE') OR pg_has_role('lumira_next','lumira_migrator','MEMBER') THEN
    RAISE EXCEPTION 'runtime role has migration privileges';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public' AND tablename='flyway_schema_history') THEN
    RAISE EXCEPTION 'global migration history missing';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public' AND (tableowner <> 'lumira_migrator'
      OR (tablename LIKE '%schema_history' AND has_table_privilege('lumira_next',quote_ident(schemaname)||'.'||quote_ident(tablename),'INSERT,UPDATE,DELETE')))) THEN
    RAISE EXCEPTION 'application ownership or migration history grants incorrect';
  END IF;
END $$;
SQL
node scripts/ci-browser-acceptance.mjs
