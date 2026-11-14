"""创建独立 QA 库，在导出后修改源数据，验证恢复仍与导出快照一致。
Create an isolated QA database, mutate it after export and verify recovery against the exported snapshot.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid

root = Path(__file__).resolve().parents[1]
database = "lumira_backup_qa_" + uuid.uuid4().hex
psql = shutil.which("psql")
subprocess.run([psql, "-X", "-v", "ON_ERROR_STOP=1", "-d", "postgres", "-c", 'CREATE DATABASE "' + database + '"'], check=True, capture_output=True)
before = set(subprocess.check_output([psql, "-XAt", "-d", "postgres", "-c", "SELECT datname FROM pg_database WHERE datname LIKE 'lumira_restore_drill_%'"], text=True).splitlines())
try:
    subprocess.run([psql, "-X", "-v", "ON_ERROR_STOP=1", "-d", database, "-c", "CREATE TABLE sample(id int PRIMARY KEY, value text); INSERT INTO sample VALUES(1,E'line\\n中文'),(2,'second'); CREATE TABLE empty_sample(id int)"], check=True, capture_output=True)
    with tempfile.TemporaryDirectory(prefix="lumira-backup-check-", dir="/tmp") as temporary:
        directory = Path(temporary)
        bin_path = directory / "bin"
        bin_path.mkdir()
        for name in ("psql", "pg_restore"):
            (bin_path / name).symlink_to(shutil.which(name))
        wrapper = bin_path / "pg_dump"
        wrapper.write_text('''#!/usr/bin/env bash
set -euo pipefail
"$REAL_PGDUMP" "$@"
"$REAL_PSQL" -X -v ON_ERROR_STOP=1 -d "$QA_DATABASE" -c "UPDATE sample SET value='changed after dump'; INSERT INTO sample VALUES(3,'new')" >/dev/null
''')
        wrapper.chmod(0o700)
        env = {**os.environ, "PG_BIN": str(bin_path), "REAL_PGDUMP": shutil.which("pg_dump"), "REAL_PSQL": psql, "QA_DATABASE": database}
        subprocess.run(["bash", str(root / "scripts/backup-restore-drill.sh"), database, str(directory / "backups")], env=env, check=True, timeout=300)
        count = subprocess.check_output([psql, "-XAt", "-d", database, "-c", "SELECT count(*) FROM sample"], text=True).strip()
        assert count == "3"
        after = set(subprocess.check_output([psql, "-XAt", "-d", "postgres", "-c", "SELECT datname FROM pg_database WHERE datname LIKE 'lumira_restore_drill_%'"], text=True).splitlines())
        assert after == before, "Restore scratch database was not cleaned up"
        print("PASS: real dump/restore, row-content digest, empty table, concurrent post-export mutation, scratch cleanup")
finally:
    subprocess.run([psql, "-X", "-v", "ON_ERROR_STOP=1", "-d", "postgres", "-c", 'DROP DATABASE "' + database + '" WITH (FORCE)'], check=True, capture_output=True)
