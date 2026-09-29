#!/bin/sh
# Backup + restore drill for the platform database.
#
# Takes a custom-format dump of the source database, restores it into a throwaway database on the
# same server, and compares per-table row counts. Exits non-zero on any mismatch, so it can run
# unattended (cron/CI) as proof that backups are actually restorable.
#
# Connection comes from the standard libpq variables (PGHOST, PGPORT, PGUSER, PGPASSWORD).
# Usage: PGHOST=... PGUSER=... PGPASSWORD=... scripts/backup-restore-drill.sh [source_db] [backup_dir]
set -eu

SOURCE_DB="${1:-lumira_next}"
BACKUP_DIR="${2:-${LUMIRA_BACKUP_DIR:-.runtime/backups}}"
PG_BIN="${PG_BIN:-}"
DRILL_DB="${SOURCE_DB}_restore_drill_$$"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP="$BACKUP_DIR/${SOURCE_DB}-$STAMP.dump"

bin() { if [ -n "$PG_BIN" ]; then echo "$PG_BIN/$1"; else echo "$1"; fi; }
PSQL="$(bin psql)"; PGDUMP="$(bin pg_dump)"; PGRESTORE="$(bin pg_restore)"

umask 077
mkdir -p "$BACKUP_DIR"
cleanup() { "$PSQL" -X -q -d postgres -c "DROP DATABASE IF EXISTS \"$DRILL_DB\"" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

# Row counts of every user table, computed exactly (not from statistics).
counts() {
  "$PSQL" -X -At -d "$1" -c "
    SELECT 'SELECT ' || quote_literal(format('%I.%I', schemaname, tablename)) ||
           ', count(*) FROM ' || format('%I.%I', schemaname, tablename) || ';'
    FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema')
    ORDER BY 1" | "$PSQL" -X -At -v ON_ERROR_STOP=1 -d "$1" -F ' ' -f - | sort
}

echo "[1/4] dumping $SOURCE_DB -> $DUMP"
"$PGDUMP" -Fc --no-owner --no-privileges -f "$DUMP" "$SOURCE_DB"
[ -s "$DUMP" ] || { echo "dump is empty" >&2; exit 1; }

echo "[2/4] restoring into scratch database $DRILL_DB"
"$PSQL" -X -q -d postgres -c "CREATE DATABASE \"$DRILL_DB\""
"$PGRESTORE" --no-owner --no-privileges --exit-on-error -d "$DRILL_DB" "$DUMP"

echo "[3/4] comparing row counts"
counts "$SOURCE_DB" > "$DUMP.source.counts"
counts "$DRILL_DB" > "$DUMP.restored.counts"
if ! diff -u "$DUMP.source.counts" "$DUMP.restored.counts"; then
  echo "restore drill FAILED: row counts differ" >&2
  exit 1
fi
TABLES="$(wc -l < "$DUMP.source.counts" | tr -d ' ')"
# An empty comparison proves nothing; never report success on it.
[ "$TABLES" -gt 0 ] || { echo "restore drill FAILED: no tables compared" >&2; exit 1; }
rm -f "$DUMP.source.counts" "$DUMP.restored.counts"

echo "[4/4] restore drill passed: $TABLES tables identical, backup kept at $DUMP"
