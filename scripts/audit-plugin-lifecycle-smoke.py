"""Real audit-query package lifecycle; requires an explicitly bound disposable QA database."""
import json
import time
import uuid
from pathlib import Path
from smoke_support import client, call, login, write, token, sql, config, wait_for_readiness

admin = client()
probe = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
prefix = "/platform/modules/plugins"
plugin = "audit-console"


def request(path, payload=None):
    status, body = call(admin, path) if payload is None else write(admin, path, "POST", payload)
    assert status == 200, (path, status, body.decode())
    return json.loads(body)


def operate(kind):
    plan = request(f"{prefix}/{plugin}/{kind}-plan", {"dataMode": "PRESERVE"})
    assert not plan["blockers"], plan
    if kind == "uninstall":
        assert not plan.get("data"), "Audit query must not own kernel audit data"
    operation = request(f"{prefix}/{plugin}/{kind}", {
        "revision": plan["revision"], "dataMode": "PRESERVE"
    })
    for _ in range(300):
        operation = request(prefix + "/operations/" + operation["id"])
        if operation["status"] in ("SUCCEEDED", "FAILED"):
            break
        time.sleep(.1)
    assert operation["status"] == "SUCCEEDED", operation
    wait_for_readiness()


def enable():
    row = next(row for row in request("/platform/modules") if row["id"] == plugin)
    if not row["enabled"]:
        status, body = write(admin, "/platform/modules/audit-console/state", "PUT", {
            "enabled": True, "version": row["stateVersion"], "reason": "Isolated audit plugin lifecycle acceptance"
        })
        assert status == 200, (status, body.decode())


def verify_scopes():
    contract = json.loads((Path(__file__).resolve().parents[1] / "plugins/audit-console/backend/src/test/http-cases.json").read_text())[0]
    assert contract["accessScope"] == "GLOBAL"
    for scope, permission, expected in (
        ("ALL", contract["accessPermission"], 200),
        ("SELF", contract["accessPermission"], 403),
        ("ALL", "platform:audit:read", 403),
        ("ALL", None, 403),
    ):
        user_id, role_id = str(uuid.uuid4()), str(uuid.uuid4())
        username = "qa_audit_" + uuid.uuid4().hex
        session = client()
        try:
            sql(f"INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '{user_id}','{username}','Audit QA',password_hash FROM platform_user WHERE username='admin'; "
                f"INSERT INTO platform_role(id,code,name,data_scope) VALUES('{role_id}','{username}','Audit QA','{scope}'); "
                f"INSERT INTO platform_user_role(user_id,role_id) VALUES('{user_id}','{role_id}')")
            if permission:
                sql(f"INSERT INTO platform_role_authority(role_id,authority) VALUES('{role_id}','{permission}')")
            assert login(session, username, config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
            status, _ = call(session, contract["path"])
            assert status == expected, (scope, permission, status)
        finally:
            sql(f"DELETE FROM spring_session WHERE principal_name='{username}'; DELETE FROM platform_user WHERE id='{user_id}'; DELETE FROM platform_role WHERE id='{role_id}'")
    print("PASS: global audit access allowed; self scope, retired read permission and missing grant denied")


marker = "qa-audit-plugin-" + uuid.uuid4().hex
record_id = str(uuid.uuid4())
try:
    archive = Path(__file__).resolve().parents[1] / "plugins/audit-console/dist/audit-console-1.0.0.lumira-plugin.zip"
    status, body = call(admin, prefix + "/import", "POST", archive.read_bytes(), {
        **token(admin), "Content-Type": "application/octet-stream"
    })
    assert status == 200, (status, body.decode())
    row = next(row for row in request(prefix) if row["id"] == plugin)
    if not row["installed"]:
        operate("install")
    enable()
    verify_scopes()
    sql(f"INSERT INTO platform_audit(id,actor,action,target_id) VALUES('{record_id}','admin','{marker}','{record_id}')")
    original = sql(f"SELECT row_to_json(t)::text FROM platform_audit t WHERE id='{record_id}'")
    result = request("/platform/audit?search=" + marker)
    assert any(row["id"] == record_id for row in result["items"]), result
    grants = sql("SELECT coalesce(string_agg(role_id::text,',' ORDER BY role_id),'') FROM platform_role_authority WHERE authority='platform:audit:access'")
    operate("uninstall")
    assert not next(row for row in request(prefix) if row["id"] == plugin)["installed"]
    assert call(admin, "/platform/audit")[0] != 200
    assert sql(f"SELECT row_to_json(t)::text FROM platform_audit t WHERE id='{record_id}'") == original
    assert sql("SELECT coalesce(string_agg(role_id::text,',' ORDER BY role_id),'') FROM platform_role_authority WHERE authority='platform:audit:access'") == grants
    before = int(sql("SELECT count(*) FROM platform_audit WHERE actor='admin' AND action='account.loginSucceeded'"))
    assert login(probe, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    assert int(sql("SELECT count(*) FROM platform_audit WHERE actor='admin' AND action='account.loginSucceeded'")) > before
    assert call(probe, "/account/security-events")[0] == 200
    operate("install")
    enable()
    result = request("/platform/audit?search=" + marker)
    assert any(row["id"] == record_id for row in result["items"]), result
    assert sql(f"SELECT row_to_json(t)::text FROM platform_audit t WHERE id='{record_id}'") == original
    print("PASS: independent audit package, PRESERVE uninstall, continued kernel logging, reinstall and identical retained record")
finally:
    # Keep audit records as acceptance evidence in the disposable QA database.
    for session in (admin, probe):
        try:
            write(session, "/auth/logout", "POST", {})
        except Exception:
            pass
