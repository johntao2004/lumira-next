#!/usr/bin/env bash
# 只在一次性容器内测试降权、默认启动、快停重启和逻辑恢复。
# Verify privilege drop, default startup, fast restart and logical restore in a disposable container only.
set -euo pipefail
image=${1:-lumira-database:ci}
[[ $image == lumira-database:ci ]]
test "$(docker image inspect "$image" --format '{{json .Config.Entrypoint}}')" = '["docker-entrypoint.sh"]'
test "$(docker image inspect "$image" --format '{{json .Config.Cmd}}')" = '["postgres"]'
docker run --rm -i --entrypoint bash "$image" -euc '
  test ! -e /etc/ssl/private/ssl-cert-snakeoil.key
  test ! -e /etc/ssl/certs/ssl-cert-snakeoil.pem
  test "$(gosu postgres id -u)" = 999
  test "$(gosu postgres id -g)" = 999
  exec gosu postgres bash -se
' <<'INNER'
set -euo pipefail
work=$(mktemp -d)
trap 'pg_ctl -D "$work/data" stop -m fast >/dev/null 2>&1 || true; rm -rf "$work"' EXIT
initdb -D "$work/data" >/dev/null
test "$(postgres -D "$work/data" -C ssl)" = off
pg_ctl -D "$work/data" -l "$work/log" -o "-k $work -h ''" -w start
export PGHOST="$work" PGUSER=postgres PGDATABASE=postgres
psql -X -v ON_ERROR_STOP=1 -c 'CREATE TABLE image_probe (id int PRIMARY KEY); INSERT INTO image_probe VALUES (1);' >/dev/null
pg_dump -Fc -f "$work/backup.dump"
pg_ctl -D "$work/data" stop -m fast
pg_ctl -D "$work/data" -l "$work/log" -o "-k $work -h ''" -w start
test "$(psql -XAt -c 'SELECT count(*) FROM image_probe')" = 1
createdb restored
pg_restore --exit-on-error -d restored "$work/backup.dump"
test "$(psql -XAt -d restored -c 'SELECT count(*) FROM image_probe')" = 1
printf '%s\n' 'PASS: non-root PostgreSQL 18.6 startup, SIGINT restart and logical restore'
INNER
