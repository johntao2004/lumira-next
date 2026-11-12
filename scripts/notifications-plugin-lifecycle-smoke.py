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
    for plugin in ("notifications", "feedback", "monitoring"):
        operate(plugin, "install")
    assert request("/platform/monitoring/summary")["database"]["status"]
    assert request("/platform/api-docs")["openapi"]
    plan = request(base + "/notifications/uninstall-plan", "POST")
    assert plan["blockers"], "Installed consumers must block notification removal"
    status, _ = write(admin, base + "/notifications/uninstall", "POST", {"revision": plan["revision"]})
    assert status == 409, status
    module = next(row for row in request("/platform/modules") if row["id"] == "notifications")
    status, body = write(admin, "/platform/modules/notifications/state", "PUT", {
        "enabled": False, "version": module["stateVersion"], "reason": "QA dependent disable rejection"})
    assert status == 409 and json.loads(body)["detail"] == "moduleDependencyConflict", (status, body)
    assert request("/platform/monitoring/summary")["database"]["status"]
    owner = sql("SELECT id FROM platform_user WHERE username='admin'")
    title = "qa-notification-plugin-" + uuid.uuid4().hex
    published = request("/platform/notifications", "POST", {
        "scope": "USERS", "userIds": [owner], "title": title, "body": "Retained plugin message"
    }, 201)
    event_id = published["eventIds"][0]
    wait_for(lambda: sql(f"SELECT status FROM platform_event_outbox WHERE id='{event_id}'") == "DELIVERED", "Notification consumer did not deliver")
    notification_id = sql(f"SELECT id FROM platform_notification WHERE event_id='{event_id}'")
    assert notification_id
    request(f"/account/notifications/{notification_id}/read", "POST", expected=204)
    retained = sql(f"SELECT row_to_json(t)::text FROM platform_notification t WHERE id='{notification_id}'")
    username = "qa_plugin_" + uuid.uuid4().hex
    user = request("/platform/users", "POST", {"username": username,
        "displayName": "Plugin lifecycle QA", "password": config["LUMIRA_BOOTSTRAP_PASSWORD"]}, 201)
    role_id = sql("SELECT id FROM platform_role WHERE code='user'")
    request("/platform/users/" + user["id"] + "/roles", "PUT",
        {"version": user["version"], "roleIds": [role_id]})
    member = client()
    assert login(member, username, config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    assert call(member, "/platform/monitoring/summary")[0] == 403
    assert call(member, "/platform/notifications")[0] == 403
    options = request("/feedback/options", connection=member)
    feedback = request("/feedback", "POST", {"title": title, "detailHtml": "<p>Plugin dependency integration</p>",
        "priority": options["priorities"][0]["value"], "category": options["categories"][0]["value"]}, 201, connection=member)
    feedback_id = feedback["id"]
    wait_for(lambda: sql(f"SELECT count(*) FROM platform_notification WHERE dedupe_key='feedback:{feedback_id}:created'") != "0", "Cross-plugin feedback notification missing")
    for plugin in ("monitoring", "feedback", "notifications"):
        operate(plugin, "uninstall")
    assert call(admin, "/account/notifications")[0] != 200
    assert call(admin, "/platform/monitoring/summary")[0] != 200
    assert call(admin, "/platform/api-docs")[0] != 200
    assert sql(f"SELECT row_to_json(t)::text FROM platform_notification t WHERE id='{notification_id}'") == retained
    legacy_menu_id = "00000000-0000-0000-0000-000000000051"
    sql(f"UPDATE platform_navigation_menu SET module_id=NULL,label_default='QA custom notification label',sort_order=73,enabled=false WHERE id='{legacy_menu_id}'")
    menu_projection = f"SELECT (to_jsonb(t)-'module_id'-'version'-'updated_at')::text FROM platform_navigation_menu t WHERE id='{legacy_menu_id}'"
    retained_menu = sql(menu_projection)
    menu_version = int(sql(f"SELECT version FROM platform_navigation_menu WHERE id='{legacy_menu_id}'"))
    pending_id, broadcast_id = str(uuid.uuid4()), str(uuid.uuid4())
    sql(f"INSERT INTO platform_event_outbox(id,source_type,event_type,event_key,payload) SELECT '{pending_id}',source_type,event_type,'broadcast:{broadcast_id}:0',jsonb_set(payload,'{{broadcastId}}',to_jsonb('{broadcast_id}'::text)) FROM platform_event_outbox WHERE id='{event_id}'")
    wait_for(lambda: sql(f"SELECT last_error FROM platform_event_outbox WHERE id='{pending_id}'") == "NO_CONSUMER", "Unloaded consumer remained active")
    for plugin in ("notifications", "feedback", "monitoring"):
        operate(plugin, "install")
    assert sql(menu_projection) == retained_menu, "Legacy menu adoption overwrote customization"
    assert sql(f"SELECT module_id FROM platform_navigation_menu WHERE id='{legacy_menu_id}'") == "notifications"
    assert int(sql(f"SELECT version FROM platform_navigation_menu WHERE id='{legacy_menu_id}'")) == menu_version + 1
    sql(f"UPDATE platform_event_outbox SET available_at=now() WHERE id='{pending_id}'")
    wait_for(lambda: sql(f"SELECT status FROM platform_event_outbox WHERE id='{pending_id}'") == "DELIVERED", "Retained event failed to recover")
    assert sql(f"SELECT count(*) FROM platform_notification WHERE event_id='{pending_id}'") == "1"
    assert sql(f"SELECT row_to_json(t)::text FROM platform_notification t WHERE id='{notification_id}'") == retained
    assert request("/feedback/" + feedback_id, connection=member)["id"] == feedback_id
    assert request("/platform/monitoring/summary")["database"]["status"]
    assert request("/platform/api-docs")["openapi"]
    print("PASS: dependent uninstall blocked; explicit reverse order unload; notification read state and feedback retained; queued event recovered exactly once; monitoring and API docs restored")
finally:
    write(admin, "/auth/logout", "POST", {})
