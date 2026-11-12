"""Real optional retention administration; only bound disposable QA data is used."""
import json
import time
import uuid
from smoke_support import client, call, write, token, sql, login, config
from plugin_lifecycle_support import preserve_plugin

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
path = "/platform/settings/file-retention"
def api(route, method="GET", payload=None, expected=200):
    status, body = call(admin, route) if method == "GET" else write(admin, route, method, payload or {})
    assert status == expected, (route, status, body.decode())
    return json.loads(body) if body else None

def upload(name, content):
    status, body = call(admin, "/account/files?filename=" + name, "POST", content,
                        {**token(admin), "Content-Type": "application/octet-stream"})
    assert status == 201, (status, body.decode())
    return json.loads(body)

def snapshot(where):
    return {table: sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text) FROM " + table + " t " + predicate)
        for table,predicate in [("platform_setting", "WHERE section='file-retention'"),
            ("platform_setting_revision", "WHERE setting_id=(SELECT id FROM platform_setting WHERE section='file-retention')"),
            ("platform_file", where), ("platform_file_blob_chunk", where.replace("id=", "file_id="))]}

baseline = None
try:
    preserve_plugin(admin, "file-retention", "install")
    baseline = api(path)
    manual = api(path, "PUT", {"version": baseline["version"], "data": {"mode": "MANUAL", "retentionDays": 1}})
    api(path, "PUT", {"version": baseline["version"], "data": baseline["data"]}, 409)
    assert api(path + "/history")["items"][0]["version"] == manual["version"]
    revision = api(path + "/history/" + str(manual["version"]))
    assert revision["data"] == manual["data"]
    content = b"QA retained file during retention plugin unload"
    retained = upload("qa-retention-preserve-" + uuid.uuid4().hex + ".txt", content)
    before = snapshot("WHERE id='" + retained["id"] + "'")
    preserve_plugin(admin, "file-retention", "uninstall")
    for route in [path, path + "/history", path + "/history/" + str(manual["version"])]:
        assert call(admin, route)[0] != 200, route
    assert write(admin, path, "PUT", {"version": manual["version"], "data": baseline["data"]})[0] != 200
    assert write(admin, path + "/rollback", "POST", {"version": manual["version"], "sourceVersion": manual["version"]})[0] != 200
    assert snapshot("WHERE id='" + retained["id"] + "'") == before
    assert call(admin, "/account/files/" + retained["id"] + "/content") == (200, content)
    preserve_plugin(admin, "file-retention", "install")
    assert api(path) == manual
    assert api(path + "/history/" + str(manual["version"])) == revision
    assert snapshot("WHERE id='" + retained["id"] + "'") == before
    automatic = api(path, "PUT", {"version": manual["version"], "data": {"mode": "AUTOMATIC", "retentionDays": 1}})
    # Backdate only our own newly-created QA trash after unloading. The persisted
    # policy is unchanged; only the real scheduled core sweep can now enqueue it.
    expired = upload("qa-retention-expire-" + uuid.uuid4().hex + ".txt", b"QA expired trash")
    api("/account/files/" + expired["id"] + "?version=" + str(expired["version"]), "DELETE", expected=204)
    preserve_plugin(admin, "file-retention", "uninstall")
    before_policy = snapshot("WHERE id='" + retained["id"] + "'")
    sql("UPDATE platform_file SET trashed_at=clock_timestamp()-interval '3 days' WHERE id='" + expired["id"] + "' AND state='TRASHED'")
    deadline = time.monotonic() + 80
    while time.monotonic() < deadline:
        state = sql("SELECT state FROM platform_file WHERE id='" + expired["id"] + "'")
        if state in ("", "DELETING"):
            break
        time.sleep(.25)
    assert state in ("", "DELETING"), "Core cleanup stopped with the optional admin plugin"
    assert snapshot("WHERE id='" + retained["id"] + "'") == before_policy
    assert call(admin, "/account/files/" + retained["id"] + "/content") == (200, content)
    preserve_plugin(admin, "file-retention", "install")
    assert api(path) == automatic
    rolled = api(path + "/rollback", "POST", {"version": automatic["version"], "sourceVersion": manual["version"]})
    assert rolled["data"] == manual["data"] and rolled["version"] == automatic["version"] + 1
    assert api(path + "/history/" + str(rolled["version"]))["sourceVersion"] == manual["version"]
    api(path, "PUT", {"version": rolled["version"], "data": baseline["data"]})
    print("PASS: optional retention policy read/write/history/rollback; stale versions rejected; exact policy+history+file bytes retained across unload/reinstall; persisted automatic storage cleanup continues while administration is absent")
finally:
    write(admin, "/auth/logout", "POST", {})
