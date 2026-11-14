"""以同一数据库快照验证备份恢复及逐表内容摘要；不代表密钥和文件卷恢复。
Verify backup restore and per-table content digests from one database snapshot, not key or file-volume recovery.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import tempfile
import time
import uuid


TIMEOUT = int(os.environ.get("LUMIRA_BACKUP_TIMEOUT_SECONDS", "900"))
if not 1 <= TIMEOUT <= 3600:
    raise ValueError("Backup stage timeout must be between 1 and 3600 seconds")

def tool(name):
    return str(Path(os.environ["PG_BIN"]) / name) if os.environ.get("PG_BIN") else name


def psql(database):
    return [tool("psql"), "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-d", database]


def execute(database, sql):
    return subprocess.run(psql(database) + ["-c", sql], check=True, capture_output=True, text=True, timeout=TIMEOUT).stdout.strip()


def identifier(value):
    return '"' + value.replace('"', '""') + '"'


def digest(database, table, snapshot=None):
    prelude = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
    if snapshot:
        prelude += "SET TRANSACTION SNAPSHOT '" + snapshot + "';\n"
    # COPY 转义换行；按 C 排序后流式散列，不在客户端积累整表。
    # COPY escapes newlines; C-ordering permits a streaming digest without buffering the table in the client.
    query = prelude + "COPY (SELECT row_to_json(t)::text FROM " + table + ' t ORDER BY row_to_json(t)::text COLLATE "C") TO STDOUT;\nCOMMIT;\n'
    with tempfile.TemporaryFile() as error:
        process = subprocess.Popen(psql(database), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=error)
        try:
            process.stdin.write(query.encode())
            process.stdin.close()
            result = hashlib.sha256()
            rows = 0
            for block in bounded_output(process, TIMEOUT):
                result.update(block)
                rows += block.count(b"\n")
            if process.wait(timeout=10) != 0:
                raise RuntimeError("Table digest query failed")
            return rows, result.hexdigest()
        finally:
            stop(process)


def stop(process):
    if process.poll() is None:
        process.kill()
    process.wait(timeout=10)
    if process.stdin and not process.stdin.closed:
        process.stdin.close()
    if process.stdout:
        process.stdout.close()


# 即使服务端或客户端管道卡住，独立墙钟也能终止查询进程。
# An independent wall-clock deadline stops the process even when the server or output pipe stalls.
def bounded_output(process, timeout):
    deadline = time.monotonic() + timeout
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not selector.select(remaining):
                raise TimeoutError("Database output deadline exceeded")
            block = os.read(process.stdout.fileno(), 65536)
            if not block:
                return
            yield block


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else "lumira_next"
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_-]*", source):
        raise ValueError("Use a simple database name without path or SQL punctuation")
    directory = Path(sys.argv[2] if len(sys.argv) > 2 else os.environ.get("LUMIRA_BACKUP_DIR", ".runtime/backups"))
    os.umask(0o077)
    directory.mkdir(parents=True, exist_ok=True)
    dump = directory / (source + "-" + time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:8] + ".dump")
    scratch = "lumira_restore_drill_" + uuid.uuid4().hex
    # 数据库语句和连接均有界；快照持有期间发生故障必须终止，不能继续比较残缺输出。
    # Bound database statements and connections; failure while holding a snapshot aborts rather than comparing partial output.
    os.environ.setdefault("PGCONNECT_TIMEOUT", "10")
    os.environ["PGOPTIONS"] = os.environ.get("PGOPTIONS", "") + " -c statement_timeout=600000 -c lock_timeout=5000 -c timezone=UTC"
    with tempfile.TemporaryFile() as snapshot_error:
        holder = subprocess.Popen(psql(source), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=snapshot_error)
        try:
            holder.stdin.write(b"BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT pg_export_snapshot();\n")
            holder.stdin.flush()
            exported = b""
            for block in bounded_output(holder, min(TIMEOUT, 30)):
                exported += block
                if b"\n" in exported:
                    break
                if len(exported) > 128:
                    raise RuntimeError("Invalid snapshot response")
            snapshot = exported.decode().strip()
            if not re.fullmatch(r"[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9]+", snapshot):
                raise RuntimeError("Snapshot export failed")
            print("[1/4] dumping one consistent snapshot", flush=True)
            subprocess.run([tool("pg_dump"), "-Fc", "--no-owner", "--no-privileges", "--snapshot=" + snapshot, "-f", str(dump), source], check=True, timeout=TIMEOUT)
            if not dump.is_file() or dump.stat().st_size == 0:
                raise RuntimeError("Dump is empty")
            execute("postgres", "CREATE DATABASE " + identifier(scratch))
            print("[2/4] restoring into disposable database", flush=True)
            subprocess.run([tool("pg_restore"), "--no-owner", "--no-privileges", "--exit-on-error", "-d", scratch, str(dump)], check=True, timeout=TIMEOUT)
            catalog = "SELECT coalesce(json_agg(json_build_array(schemaname,tablename) ORDER BY schemaname,tablename),'[]') FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema')"
            tables = json.loads(execute(source, "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '" + snapshot + "'; " + catalog + "; COMMIT;"))
            restored_tables = json.loads(execute(scratch, catalog))
            if not tables or tables != restored_tables:
                raise RuntimeError("Empty or mismatched table inventory")
            print("[3/4] comparing snapshot row counts and SHA-256 content digests", flush=True)
            for schema, name in tables:
                table = identifier(schema) + "." + identifier(name)
                if digest(source, table, snapshot) != digest(scratch, table):
                    raise RuntimeError("Restored table content mismatch")
        finally:
            try:
                stop(holder)
            finally:
                # 随机命名库属于本次演练；即使创建响应丢失，也尝试清理并传播失败。
                # The random database belongs to this drill; clean up even if the creation response was lost, and propagate failure.
                execute("postgres", "DROP DATABASE IF EXISTS " + identifier(scratch) + " WITH (FORCE)")
    print(f"[4/4] database restore drill passed: {len(tables)} tables match; backup kept at {dump}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Database restore drill FAILED: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
