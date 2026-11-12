"""Exercise live-node fencing and recovery in an explicitly marked two-JVM QA fixture."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

from smoke_support import call, client, config, config_path, login, sql, write


actor = client()  # Binds this HTTP origin to the marked database before any mutation.
assert login(actor, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200

if sys.argv[1:] == ["--peer"]:
    try:
        status, body = call(actor, "/auth/me")
        assert status == 200 and json.loads(body)["username"] == "admin"
        print("PASS: independent peer serves a real authenticated session while the first node is fenced")
    finally:
        assert write(actor, "/auth/logout", "POST", {})[0] in (200, 204)
    sys.exit(0)

assert not sys.argv[1:]
node = str(uuid.UUID(os.environ["LUMIRA_QA_FIRST_NODE_ID"]))
peer = str(uuid.UUID(os.environ["LUMIRA_QA_SECOND_NODE_ID"]))
assert node != peer
peer_config_path = Path(os.environ["LUMIRA_QA_SECOND_NODE_CONFIG"]).resolve()
assert peer_config_path.parent == config_path().resolve().parent
assert peer_config_path.name == "peer.json"
peer_config = json.loads(peer_config_path.read_text())
for key in ["LUMIRA_DB_URL", "LUMIRA_DB_USER", "LUMIRA_QA_RUN_TOKEN"]:
    assert peer_config[key] == config[key], "Peer must use the same marked fixture"
assert peer_config["LUMIRA_BACKEND_BASE_URL"] == "http://127.0.0.1:18137"
assert config["LUMIRA_BACKEND_BASE_URL"] == "http://127.0.0.1:18134"
assert sql(f"SELECT count(*) FROM platform_plugin_runtime_node WHERE node_id IN ('{node}','{peer}') AND ready AND heartbeat_at>clock_timestamp()-interval '30 seconds'") == "2"


def business_snapshot():
    # Deliberately omit sessions, audit and node heartbeats, which this real login exercise changes.
    values = {}
    for table in ["platform_user", "platform_role", "platform_user_role",
                  "platform_module_state", "platform_plugin_artifact",
                  "platform_plugin_installation", "platform_plugin_graph_approval"]:
        values[table] = sql(f"SELECT md5(coalesce(string_agg(row_to_json(t)::text, E'\\n' ORDER BY row_to_json(t)::text),'')) FROM {table} t")
    return values


before = business_snapshot()
try:
    previous = sql(f"SELECT heartbeat_at::text FROM platform_plugin_runtime_node WHERE node_id='{node}'")
    deadline = time.monotonic() + 15
    while sql(f"SELECT heartbeat_at::text FROM platform_plugin_runtime_node WHERE node_id='{node}'") == previous:
        assert time.monotonic() < deadline, "Expected a live heartbeat before injecting expiry"
        time.sleep(.1)
    # Inject only after a fresh tick, giving the HTTP checks a full heartbeat interval.
    sql(f"UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp()-interval '31 seconds' WHERE node_id='{node}'")
    status, body = call(actor, "/auth/me")
    assert status == 503 and json.loads(body)["code"] == "pluginRuntimeNotReady", (status, body)

    peer_environment = {**os.environ, "LUMIRA_SMOKE_CONFIG": str(peer_config_path),
                        "BASE_URL": peer_config["LUMIRA_BACKEND_BASE_URL"],
                        "LUMIRA_BACKEND_BASE_URL": peer_config["LUMIRA_BACKEND_BASE_URL"],
                        "LUMIRA_SMOKE_BACKEND_PID": os.environ["LUMIRA_QA_SECOND_NODE_PID"]}
    result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--peer"],
                            env=peer_environment, timeout=20, check=False)
    assert result.returncode == 0, "Peer failed while the expired node was fenced"

    deadline = time.monotonic() + 40
    while True:
        status, body = call(actor, "/auth/me")
        if status == 200:
            assert json.loads(body)["username"] == "admin"
            break
        assert status == 503, (status, body)
        assert time.monotonic() < deadline, "Node failed to recover after its own successful heartbeat"
        time.sleep(.2)
    assert business_snapshot() == before, "Node fencing or recovery changed retained business state"
    print("PASS: real two-JVM heartbeat expiry fences only the expired node; automatic heartbeat restores admission; retained users, roles, plugin catalog and graph remain unchanged")
finally:
    # Never fake a healthy heartbeat in cleanup. The owning JVM must renew its own node row.
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        if call(actor, "/auth/me")[0] == 200:
            assert write(actor, "/auth/logout", "POST", {})[0] in (200, 204)
            break
        time.sleep(.2)
