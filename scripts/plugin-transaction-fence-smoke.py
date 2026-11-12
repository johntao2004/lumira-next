"""Real two-JVM lost-node transaction/drain proof, only in an HTTP-bound disposable QA fixture."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid
import urllib.error
import urllib.request

from smoke_support import (call, client, config, config_path, login, psql_command,
                           psql_environment, sql, wait_for_readiness, write)
from smoke_support import authorization_context_headers, token
from plugin_lifecycle_support import preserve_plugin

actor = client()
assert login(actor, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
base = "/platform/modules/plugins"


def api(path, method="GET", payload=None, expected=200):
    status, body = call(actor, path) if method == "GET" else write(actor, path, method, payload or {})
    assert status == expected, (path, status, body)
    return json.loads(body) if body else None


if sys.argv[1:] == ["--prepare"]:
    installed = {value["id"]: value["installed"] for value in api(base)}
    # Explicit fixture preparation, never cascade deletion or automatic dependency detachment.
    for identifier in ("feedback", "sensitive-words", "profile-console"):
        if installed.get(identifier):
            preserve_plugin(actor, identifier, "uninstall")
    plan = api(base + "/dictionaries/uninstall-plan", "POST")
    assert not plan["blockers"], plan
    print("PASS: explicitly detached three disposable-fixture consumers; dictionary data and configuration retained")
    sys.exit(0)

if len(sys.argv) == 3 and sys.argv[1] == "--peer-operation":
    expected = sys.argv[2]
    assert expected in {"FAILED", "SUCCEEDED"}
    plan = api(base + "/dictionaries/uninstall-plan", "POST")
    assert not plan["blockers"], plan
    operation = api(base + "/dictionaries/uninstall", "POST", {"revision": plan["revision"], "dataMode": "PRESERVE"})
    deadline = time.monotonic() + 12
    while operation["status"] not in {"SUCCEEDED", "FAILED"}:
        assert time.monotonic() < deadline, operation
        time.sleep(.1)
        operation = api(base + "/operations/" + operation["id"])
    assert operation["status"] == expected, operation
    if expected == "FAILED":
        assert operation["errorCode"] == "pluginDrainTimeout", operation
    wait_for_readiness()
    assert api("/auth/me")["username"] == "admin"
    print("PASS: peer preserve uninstall " + expected + " with real runtime/transaction fencing")
    sys.exit(0)

if sys.argv[1:] == ["--replacement"]:
    assert api("/auth/me")["username"] == "admin"
    assert not next(value for value in api(base) if value["id"] == "dictionaries")["installed"]
    assert call(actor, "/platform/dictionaries")[0] != 200
    assert int(sql("SELECT count(*) FROM platform_dictionary WHERE code LIKE 'qa_fenced_write_%'")) == 1
    pool = api("/platform/monitoring/summary")["database"]
    assert isinstance(pool["activeConnections"], int) and isinstance(pool["maxConnections"], int), pool
    print("PASS: cold replacement restores the committed graph without reinstalling the removed provider; original dictionary write is retained")
    sys.exit(0)

assert not sys.argv[1:]
node = str(uuid.UUID(os.environ["LUMIRA_QA_FIRST_NODE_ID"]))
peer = str(uuid.UUID(os.environ["LUMIRA_QA_SECOND_NODE_ID"]))
assert node != peer
peer_path = Path(os.environ["LUMIRA_QA_SECOND_NODE_CONFIG"]).resolve()
assert peer_path.parent == config_path().resolve().parent and peer_path.name == "peer.json"
peer_config = json.loads(peer_path.read_text())
for key in ("LUMIRA_DB_URL", "LUMIRA_DB_USER", "LUMIRA_QA_RUN_TOKEN"):
    assert config[key] == peer_config[key]
peer_env = {**os.environ, "LUMIRA_SMOKE_CONFIG": str(peer_path), "BASE_URL": peer_config["LUMIRA_BACKEND_BASE_URL"],
            "LUMIRA_BACKEND_BASE_URL": peer_config["LUMIRA_BACKEND_BASE_URL"],
            "LUMIRA_SMOKE_BACKEND_PID": os.environ["LUMIRA_QA_SECOND_NODE_PID"]}
ready_deadline = time.monotonic() + 20
while sql(f"SELECT count(*) FROM platform_plugin_runtime_node WHERE node_id IN ('{node}','{peer}') AND ready AND heartbeat_at>clock_timestamp()-interval '30 seconds'") != "2":
    assert time.monotonic() < ready_deadline, "Both nodes must finish their prior recovery before injecting a new failure"
    time.sleep(.1)
trigger = "qa_fence_" + uuid.uuid4().hex
code = "qa_fenced_write_" + uuid.uuid4().hex
gate = 9_871_234_523
before = {table: sql(f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM {table} t")
          for table in ("platform_user", "platform_role", "platform_user_role", "platform_dictionary_item")}
blocker = None
post_headers = authorization_context_headers(actor, "/api/platform/dictionaries", {
    **token(actor), "Content-Type": "application/json"})


def blocked_write():
    request = urllib.request.Request(config["LUMIRA_BACKEND_BASE_URL"] + "/api/platform/dictionaries",
        data=json.dumps({"code": code, "name": "Retained fenced write", "enabled": True,
                         "structure": "FLAT", "remark": "QA"}).encode(),
        method="POST", headers=post_headers)
    try:
        with actor.open(request, timeout=45) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as response:
        return response.code, response.read()


def peer_operation(expected):
    result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--peer-operation", expected],
                            env=peer_env, timeout=18, check=False)
    assert result.returncode == 0, "Actual peer lifecycle operation failed"


def release_gate():
    global blocker
    if blocker is not None:
        blocker.terminate()
        try:
            blocker.wait(timeout=4)
        except subprocess.TimeoutExpired:
            blocker.kill()
            blocker.wait(timeout=4)
        blocker = None


try:
    sql(f"""CREATE FUNCTION {trigger}() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN IF NEW.code='{code}' THEN PERFORM pg_advisory_xact_lock({gate}); END IF; RETURN NEW; END $$;
        CREATE TRIGGER {trigger} BEFORE INSERT ON platform_dictionary FOR EACH ROW EXECUTE FUNCTION {trigger}();""")
    blocker = subprocess.Popen(psql_command(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, env=psql_environment())
    # Stay idle on the live stdin pipe. A sleeping server query need not notice a lost
    # client until the sleep ends; an idle backend releases this fixture lock on EOF.
    blocker.stdin.write(f"SET application_name='{trigger}_keeper';\nSELECT pg_advisory_lock({gate});\n\\echo QA_GATE_READY\n")
    blocker.stdin.flush()
    while blocker.stdout.readline().strip() != "QA_GATE_READY":
        assert blocker.poll() is None, "Private gate did not start"
    with ThreadPoolExecutor(max_workers=1) as workers:
        pending = workers.submit(blocked_write)
        try:
            deadline = time.monotonic() + 8
            while sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory' AND query LIKE '%INSERT INTO platform_dictionary%'") == "0":
                assert time.monotonic() < deadline, "Real business insert never reached its database gate"
                time.sleep(.05)
            sql(f"""BEGIN;
                UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp()-interval '31 seconds' WHERE node_id='{node}';
                CREATE FUNCTION {trigger}_node() RETURNS trigger LANGUAGE plpgsql AS $$
                  BEGIN NEW.heartbeat_at:=OLD.heartbeat_at; RETURN NEW; END $$;
                CREATE TRIGGER {trigger}_node BEFORE UPDATE ON platform_plugin_runtime_node
                  FOR EACH ROW WHEN (OLD.node_id='{node}'::uuid) EXECUTE FUNCTION {trigger}_node();
                COMMIT;""")
            peer_operation("FAILED")
            assert not pending.done(), "The blocked old-node transaction unexpectedly ended"
            assert sql("SELECT installed::text FROM platform_plugin_installation WHERE module_id='dictionaries'") == "true"
        finally:
            release_gate()
        status, body = pending.result(timeout=8)
        assert status == 201, (status, body)
        record = json.loads(body)
    retained = sql(f"SELECT to_jsonb(t)::text FROM platform_dictionary t WHERE id='{record['id']}'")
    assert retained
    peer_operation("SUCCEEDED")
    assert sql(f"SELECT to_jsonb(t)::text FROM platform_dictionary t WHERE id='{record['id']}'") == retained
    for table, value in before.items():
        assert sql(f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM {table} t") == value
    assert call(actor, "/auth/me")[0] == 503, "Old node served a new request after losing its generation/lease"
    print("PASS: actual old-node write holds database fence after heartbeat expiry; peer unload rolls back; completed write is preserved through explicit retry; unrelated business rows unchanged")
finally:
    release_gate()
    sql(f"""DROP TRIGGER IF EXISTS {trigger}_node ON platform_plugin_runtime_node;
        DROP FUNCTION IF EXISTS {trigger}_node();
        DROP TRIGGER IF EXISTS {trigger} ON platform_dictionary;
        DROP FUNCTION IF EXISTS {trigger}();""")
