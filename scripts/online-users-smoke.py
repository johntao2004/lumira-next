"""Real loopback check for the global online-session console."""
from smoke_support import *
import json, uuid

username = "qa_online_" + uuid.uuid4().hex
password = config["LUMIRA_BOOTSTRAP_PASSWORD"]
admin = client()
created = None
try:
    assert login(admin, "admin", password) == 200
    status, body = write(admin, "/platform/users", "POST", {"username": username, "displayName": "Online QA", "password": password})
    assert status == 201, (status, body)
    created = json.loads(body)
    actor = client()
    assert login(actor, username, password) == 200

    status, body = call(admin, "/platform/online-users?" + urllib.parse.urlencode({"search": username}))
    assert status == 200, (status, body)
    page = json.loads(body)
    assert page["total"] == 1 and len(page["items"]) == 1
    row = page["items"][0]
    assert set(row) == {"id", "userId", "username", "displayName", "enabled", "createdAt", "lastAccessAt", "expiresAt"}
    assert row["userId"] == created["id"] and row["username"] == username

    assert call(actor, "/platform/online-users")[0] == 403
    assert write(admin, "/platform/online-users/" + row["id"], "DELETE", {})[0] == 204
    assert call(actor, "/auth/me")[0] == 401
    assert write(admin, "/platform/online-users/" + row["id"], "DELETE", {})[0] == 404
    print("PASS: global online-session listing, search, safe projection, global read denial and admin revoke")
finally:
    if created:
        sql("DELETE FROM platform_audit WHERE target_id='" + created["id"] + "'; DELETE FROM spring_session WHERE principal_name='" + username + "'; DELETE FROM platform_user WHERE id='" + created["id"] + "'")
