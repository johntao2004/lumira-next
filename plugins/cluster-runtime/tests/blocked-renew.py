"""Exercise real competing PostgreSQL sessions in an explicitly private journal database."""
import os
import re
import selectors
import subprocess
import time
import uuid

assert re.fullmatch(r"lumira_qa_journal_[a-f0-9]{12}", os.environ["PGDATABASE"])
assert os.environ["PGHOST"] == "127.0.0.1"
command = [os.environ["PSQL_BIN"], "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1"]


def sql(statement):
    return subprocess.check_output(command + ["-c", statement], text=True).strip()


def race(function, expected):
    assert function in {"plugin_cluster_runtime_renew", "qa_legacy_renew"}
    operation, owner, token = (str(uuid.uuid4()) for _ in range(3))
    sql(f"""INSERT INTO plugin_cluster_runtime_operation
      (id,owner_node_id,owner_token,lease_expires_at,phase,base_revision,target)
      VALUES('{operation}','{owner}','{token}',clock_timestamp()+interval '2 seconds',
      'PREPARING',repeat('a',64),'{{}}')""")
    locker = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, bufsize=1)
    renew = None
    try:
        locker.stdin.write(f"BEGIN; SELECT id FROM plugin_cluster_runtime_operation WHERE id='{operation}' FOR UPDATE; SELECT 'LOCKED';\n")
        locker.stdin.flush()
        with selectors.DefaultSelector() as selector:
            selector.register(locker.stdout, selectors.EVENT_READ)
            assert selector.select(3), "Lock holder did not answer"
            assert locker.stdout.readline().strip() == operation
            assert locker.stdout.readline().strip() == "LOCKED"
        name = "qa-renew-" + uuid.uuid4().hex
        renew = subprocess.Popen(command + ["-c", f"SELECT {function}('{operation}','{owner}','{token}',1)"],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                 env={**os.environ, "PGAPPNAME": name})
        deadline = time.monotonic() + 4
        while sql(f"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND application_name='{name}' AND wait_event_type='Lock'") != "1":
            assert renew.poll() is None and time.monotonic() < deadline
            time.sleep(.01)
        while sql(f"SELECT lease_expires_at<=clock_timestamp() FROM plugin_cluster_runtime_operation WHERE id='{operation}'") != "t":
            assert time.monotonic() < deadline
            time.sleep(.02)
        locker.stdin.write("COMMIT;\n\\q\n"); locker.stdin.flush()
        locker.wait(timeout=3)
        output, error = renew.communicate(timeout=3)
        assert renew.returncode == 0, error
        assert output.strip() == expected, (function, output)
        if expected == "f":
            assert sql(f"SELECT lease_expires_at<=clock_timestamp() FROM plugin_cluster_runtime_operation WHERE id='{operation}'") == "t"
        sql(f"UPDATE plugin_cluster_runtime_operation SET phase='ABORTED' WHERE id='{operation}'")
    finally:
        for process in [renew, locker]:
            if process is not None and process.poll() is None:
                process.kill(); process.wait(timeout=3)


# Negative control: the old single UPDATE can pass its lease predicate before blocking.
sql("""CREATE FUNCTION qa_legacy_renew(op uuid, owner uuid, token uuid, epoch bigint)
RETURNS boolean LANGUAGE sql AS $$ WITH r AS (
 UPDATE plugin_cluster_runtime_operation SET lease_expires_at=clock_timestamp()+interval '45 seconds'
 WHERE id=op AND owner_node_id=owner AND owner_token=token AND owner_epoch=epoch
 AND lease_expires_at>clock_timestamp() RETURNING id) SELECT EXISTS(SELECT 1 FROM r) $$""")
race("qa_legacy_renew", "t")
race("plugin_cluster_runtime_renew", "f")
print("PASS: old renew revives an expired blocked lease; current renew rejects it after acquiring the row lock")
