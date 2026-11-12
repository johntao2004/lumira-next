"""Preserve legacy global delegation rules after extraction; bound disposable QA only."""
import json
import uuid
from pathlib import Path
from smoke_support import client, call, write, login, sql, config

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200


def api(path, method="GET", payload=None, expected=200, actor=None):
    actor = actor or admin
    status, raw = call(actor, path) if method == "GET" else write(actor, path, method, payload or {})
    assert status == expected, (path, status, raw.decode())
    return json.loads(raw) if raw else None


suffix = uuid.uuid4().hex[:12]
username = "qa_legacy_installer_" + suffix
password = "Qa!8" + uuid.uuid4().hex + uuid.uuid4().hex
installer = client()
legacy = ["platform:profile-fields:access", "platform:profile-fields:update", "platform:profile-fields:read-dictionaries",
          "platform:smtp:access", "platform:smtp:update", "platform:smtp:test",
          "platform:sms:access", "platform:sms:update", "platform:sms:test"]
legacy.extend(json.loads(Path("plugins/authentication-console/plugin.json").read_text())["permissions"])
try:
    role = api("/platform/roles", "POST", {
        "code": "qa_legacy_installer_" + suffix, "name": "QA bounded plugin installer", "enabled": True, "dataScope": "ALL",
        "authorities": ["platform:roles:access", "platform:roles:create", "platform:modules:access", "platform:modules:install"],
    }, 201)
    user = api("/platform/users", "POST", {"username": username, "displayName": "QA bounded installer", "password": password}, 201)
    api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": user["version"], "roleIds": [role["id"]]})
    assert login(installer, username, password) == 200
    catalog = api("/platform/permission-catalog", actor=installer)
    entries = {item["code"]: item for group in catalog for item in group["permissions"]}
    for index, permission in enumerate(legacy):
        assert permission in entries and entries[permission]["editable"] is False, permission
        failure = api("/platform/roles", "POST", {
            "code": "qa_legacy_deny_" + suffix + "_" + str(index), "name": "QA rejected legacy delegation", "enabled": True,
            "dataScope": "ALL", "authorities": [permission],
        }, 403, actor=installer)
        assert failure["detail"] == "rolePermissionDelegationDenied", failure
    assert sql("SELECT count(*) FROM platform_role WHERE starts_with(code,'qa_legacy_deny_" + suffix + "_')") == "0"
    plugins = api("/platform/modules/plugins")
    feedback = next(plugin for plugin in plugins if plugin["id"] == "feedback" and plugin["installed"])
    ordinary = next(permission for permission in feedback["manifest"]["permissions"] if permission.startswith("platform:feedback:"))
    positive = api("/platform/roles", "POST", {
        "code": "qa_legacy_positive_" + suffix, "name": "QA legitimate plugin bootstrap", "enabled": True,
        "dataScope": "SELF", "authorities": [ordinary],
    }, 201, actor=installer)
    assert sql("SELECT count(*) FROM platform_role_authority WHERE role_id='" + positive["id"] + "' AND authority='" + ordinary + "'") == "1"
    assert sql("SELECT count(*) FROM platform_audit WHERE actor='" + username + "' AND action='role.pluginPermission.bootstrap:" + ordinary + "'") == "1"
    print("PASS: actual installed profile/delivery/authentication catalog retains " + str(len(legacy)) + " nondelegable legacy grants for an installer lacking global possession; all attempted grants rejected without partial roles; genuinely new plugin authority still bootstraps with its own audit")
finally:
    write(installer, "/auth/logout", "POST", {})
    write(admin, "/auth/logout", "POST", {})
