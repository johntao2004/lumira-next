"""Inject graph-commit failure through actual plugin APIs in a bound disposable QA fixture."""
import json
import time
import uuid

from smoke_support import call, client, config, login, sql, wait_for_readiness, write
from plugin_lifecycle_support import preserve_plugin


actor = client()  # Enforces HTTP/SQL origin, fresh QA database and fixture-token binding.
assert login(actor, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
base = "/platform/modules/plugins"
identifier = "monitoring"
probe = "qa_commit_" + uuid.uuid4().hex


def snapshot():
    return {table: sql(f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM {table} t")
            for table in ["platform_plugin_artifact", "platform_plugin_installation",
                          "platform_module_state", "platform_plugin_graph_approval",
                          "platform_user", "platform_role", "platform_user_role"]}


def inject():
    sql(f"""CREATE FUNCTION {probe}() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'QA graph commit rejected'; END $$;
        CREATE TRIGGER {probe} BEFORE UPDATE ON platform_plugin_graph_approval
        FOR EACH ROW EXECUTE FUNCTION {probe}();""")


def clear():
    sql(f"DROP TRIGGER IF EXISTS {probe} ON platform_plugin_graph_approval; DROP FUNCTION IF EXISTS {probe}();")


def failed_operation(action):
    status, body = write(actor, f"{base}/{identifier}/{action}-plan", "POST", {})
    assert status == 200, (status, body)
    plan = json.loads(body)
    assert not plan["blockers"], plan
    status, body = write(actor, f"{base}/{identifier}/{action}", "POST", {
        "revision": plan["revision"], "dataMode": "PRESERVE"})
    assert status == 200, (status, body)
    operation = json.loads(body)
    deadline = time.monotonic() + 45
    while operation["status"] not in {"FAILED", "SUCCEEDED", "CANCELLED"}:
        assert time.monotonic() < deadline, operation
        time.sleep(.1)
        status, body = call(actor, base + "/operations/" + operation["id"])
        assert status == 200, (status, body)
        operation = json.loads(body)
    assert operation["status"] == "FAILED", operation
    wait_for_readiness()


try:
    preserve_plugin(actor, identifier, "install")
    assert call(actor, "/platform/monitoring/summary")[0] == 200
    before = snapshot()
    inject()
    status, body = call(actor, "/platform/modules")
    assert status == 200, (status, body)
    module = next(value for value in json.loads(body) if value["id"] == identifier)
    status, body = write(actor, f"/platform/modules/{identifier}/state", "PUT", {
        "enabled": False, "version": module["stateVersion"], "reason": "QA rejected commit"})
    assert status != 200, "Injected graph commit failure was silently accepted"
    wait_for_readiness()
    assert snapshot() == before, "Failed disable changed flags, versions, approval or retained data"
    assert call(actor, "/platform/monitoring/summary")[0] == 200, "Failed disable did not restore actual resources"

    failed_operation("uninstall")
    assert snapshot() == before, "Failed preserve uninstall published a partial graph"
    assert call(actor, "/platform/monitoring/summary")[0] == 200, "Failed uninstall did not restore the actual plugin"
    clear()

    preserve_plugin(actor, identifier, "uninstall")
    uninstalled = snapshot()
    inject()
    failed_operation("install")
    assert snapshot() == uninstalled, "Failed install published flags or installation versions"
    assert call(actor, "/platform/monitoring/summary")[0] != 200
    clear()
    preserve_plugin(actor, identifier, "install")
    assert call(actor, "/platform/monitoring/summary")[0] == 200
    print("PASS: actual disable, preserve uninstall and install roll back graph-commit failure; resources recover, exact flags/versions/data persist, retry succeeds")
finally:
    clear()
    write(actor, "/auth/logout", "POST", {})
