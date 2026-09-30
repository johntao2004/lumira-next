#!/usr/bin/env bash
# Stop before publishing so the database filesystem snapshot is consistent.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
source scripts/cloud-env.sh
node scripts/stop.mjs
if [[ ${LUMIRA_CLOUD_PG_MODE:-system} == user ]]; then
  data="$root/.runtime/cloud-postgres/data"
  socket="$root/.runtime/cloud-postgres/socket"
  if "$LUMIRA_CLOUD_PG_HOME/bin/pg_isready" -h "$socket" -p 5432 >/dev/null; then
    actual=$("$PSQL_BIN" -X -h "$socket" -p 5432 -U postgres -d postgres -Atc 'SHOW data_directory')
    [[ $actual == "$data" ]]
    "$LUMIRA_CLOUD_PG_HOME/bin/pg_ctl" -D "$data" -m fast -w stop
  fi
fi

# Proxy credentials are regenerated from the next task's environment, not saved
# in the published filesystem. Dependency caches remain reusable.
if [[ -n ${LUMIRA_CLOUD_MAVEN_SETTINGS:-} ]]; then
  rm -f -- "$LUMIRA_CLOUD_MAVEN_SETTINGS"
fi
