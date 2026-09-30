#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
source scripts/cloud-env.sh
[[ $EUID != 0 && ${LUMIRA_CLOUD_PG_MODE:-} == user && -x $LUMIRA_CLOUD_PG_HOME/bin/initdb ]]
pg="$LUMIRA_CLOUD_PG_HOME/bin"
data="$root/.runtime/cloud-postgres/data"
socket="$root/.runtime/cloud-postgres/socket"
mkdir -p "$socket"
chmod 700 "$root/.runtime/cloud-postgres" "$socket"
if [[ ! -f $data/PG_VERSION ]]; then
  "$pg/initdb" -D "$data" -U postgres --auth-local=trust --auth-host=scram-sha-256 --encoding=UTF8 --locale=C
fi
[[ $(cat "$data/PG_VERSION") == 18 ]] || { echo 'Cloud database must be PostgreSQL 18.' >&2; exit 1; }
if ! "$pg/pg_isready" -h "$socket" -p 5432 >/dev/null; then
  # Require a free TCP port before starting this private cluster. A stale PID
  # copied by publication must never be used to stop an unrelated process.
  node --input-type=module <<'JS'
import net from 'node:net';
const server = net.createServer();
server.once('error', error => { console.error('Cloud database port 5432 is occupied:', error.code); process.exit(1); });
server.listen(5432, '127.0.0.1', () => server.close());
JS
  if [[ -f $data/postmaster.pid ]]; then
    pid=$(head -n 1 "$data/postmaster.pid")
    if [[ $pid =~ ^[0-9]+$ && -r /proc/$pid/cmdline ]] && tr '\0' '\n' < "/proc/$pid/cmdline" | grep -Fxq "$data"; then
      echo 'Owned PostgreSQL process exists but is not ready; inspect .runtime/cloud-postgres/server.log.' >&2; exit 1
    fi
    rm "$data/postmaster.pid"
  fi
  "$pg/pg_ctl" -D "$data" -l "$root/.runtime/cloud-postgres/server.log" -o "-h 127.0.0.1 -p 5432 -k $socket" -w start
fi
# Verify the socket belongs to this checkout's actual PostgreSQL 18 cluster.
actual=$(PGPASSWORD= "$pg/psql" -X -h "$socket" -p 5432 -U postgres -d postgres -Atc "SELECT current_setting('data_directory') || '|' || current_setting('server_version_num')")
[[ $actual == "$data|18"* ]] || { echo 'Unexpected cloud database owner or version.' >&2; exit 1; }
printf '#!/usr/bin/env bash\nexec env PGHOST=%q PGPORT=5432 PGUSER=postgres PGPASSWORD= %q "$@"\n' "$socket" "$pg/psql" > .runtime/cloud-psql-admin.sh
chmod 700 .runtime/cloud-psql-admin.sh
PSQL_BIN="$root/.runtime/cloud-psql-admin.sh" node scripts/init-local.mjs
