"""Dependency-aware real business plugin acceptance in a bound disposable QA database."""
import json
import time
import uuid
from smoke_support import client, call, login, write, sql, config, wait_for_readiness

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
base = "/platform/modules/plugins"


def request(path, method="GET", payload=None, expected=200, connection=None):
    connection = connection or admin
    status, body = call(connection, path) if method == "GET" else write(connection, path, method, payload or {})
    assert status == expected, (path, status, body.decode())
    return json.loads(body) if body else None


def wait_for(check, message):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.2)
    raise AssertionError(message)


def operate(plugin, kind):
    plan = request(f"{base}/{plugin}/{kind}-plan", "POST")
    assert not plan["blockers"], plan
    operation = request(f"{base}/{plugin}/{kind}", "POST", {"revision": plan["revision"], "dataMode": "PRESERVE"})
    def finished():
        nonlocal operation
        operation = request(base + "/operations/" + operation["id"])
        return operation["status"] in ("SUCCEEDED", "FAILED")
    wait_for(finished, "Plugin lifecycle timed out")
    assert operation["status"] == "SUCCEEDED", operation
    wait_for_readiness()


try:
    operate("event-console", "install")
    assert request("/platform/event-outbox")["items"] is not None
    owner = sql("SELECT id FROM platform_user WHERE username='admin'")
    def broadcast():
        published = request("/platform/notifications", "POST", {"scope":"USERS", "userIds":[owner],
            "title":"QA event console", "body":"Core queue continues while console is unloaded"}, 201)
        event_id = published["eventIds"][0]
        wait_for(lambda: sql(f"SELECT status FROM platform_event_outbox WHERE id='{event_id}'") == "DELIVERED", "Core queue delivery failed")
        return event_id
    original = broadcast()
    replay_id, broadcast_id = str(uuid.uuid4()), str(uuid.uuid4())
    sql(f"INSERT INTO platform_event_outbox(id,source_type,event_type,event_key,payload,status) SELECT '{replay_id}',source_type,event_type,'broadcast:{broadcast_id}:0',jsonb_set(payload,'{{broadcastId}}',to_jsonb('{broadcast_id}'::text)),'DEAD_LETTER' FROM platform_event_outbox WHERE id='{original}'")
    replay = request(f"/platform/event-outbox/{replay_id}/replay", "POST", {"reason":"QA independent console recovery"})
    assert replay["generation"] == 1
    wait_for(lambda: sql(f"SELECT status FROM platform_event_outbox WHERE id='{replay_id}'") == "DELIVERED", "Replay failed")
    assert sql(f"SELECT count(*) FROM platform_notification WHERE event_id='{replay_id}'") == "1"
    snapshot_query = f"SELECT row_to_json(t)::text FROM platform_event_outbox t WHERE id='{replay_id}'"
    retained = sql(snapshot_query)
    approvals = sql(f"SELECT row_to_json(t)::text FROM platform_event_replay_approval t WHERE event_id='{replay_id}'")
    assert approvals
    operate("event-console", "uninstall")
    assert call(admin, "/platform/event-outbox")[0] != 200
    broadcast()
    assert sql(snapshot_query) == retained
    assert sql(f"SELECT row_to_json(t)::text FROM platform_event_replay_approval t WHERE event_id='{replay_id}'") == approvals
    operate("event-console", "install")
    assert request(f"/platform/event-outbox/{replay_id}/trace")["event"]
    assert sql(snapshot_query) == retained
    print("PASS: independent event console replay; preserve unload/reinstall; core queue delivery while console absent; original event and replay approvals retained")
finally:
    write(admin, "/auth/logout", "POST", {})
