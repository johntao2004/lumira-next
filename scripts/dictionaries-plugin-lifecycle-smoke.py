"""Real optional dictionary provider acceptance; requires the bound disposable QA fixture guard."""
import copy
import json
import time
import uuid
from smoke_support import client, call, login, write, sql, config, wait_for_readiness

session = client()
assert login(session, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
plugins = "/platform/modules/plugins"


def api(path, method="GET", data=None, expected=200):
    status, raw = call(session, path) if method == "GET" else write(session, path, method, data or {})
    assert status == expected, (method, path, status, raw.decode())
    return json.loads(raw) if raw else None


def lifecycle(identifier, action):
    proposal = api(f"{plugins}/{identifier}/{action}-plan", "POST")
    assert not proposal["blockers"], proposal
    operation = api(f"{plugins}/{identifier}/{action}", "POST", {
        "revision": proposal["revision"], "dataMode": "PRESERVE"})
    deadline = time.monotonic() + 45
    while operation["status"] not in {"SUCCEEDED", "FAILED"} and time.monotonic() < deadline:
        time.sleep(.2)
        operation = api(plugins + "/operations/" + operation["id"])
    assert operation["status"] == "SUCCEEDED", operation
    wait_for_readiness()


def fingerprint(table):
    assert table in {"platform_dictionary", "platform_dictionary_item", "platform_user"}
    if table == "platform_user":
        return sql("SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'values',profile_values) ORDER BY id),'[]'::jsonb)::text FROM platform_user")
    return sql(f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM {table} t")


try:
    for identifier in ("dictionaries", "feedback", "sensitive-words"):
        lifecycle(identifier, "install")
    code = "qa_dictionary_plugin_" + uuid.uuid4().hex
    dictionary = api("/platform/dictionaries", "POST", {
        "code": code, "name": "Retained dictionary", "enabled": True, "structure": "TREE", "remark": "QA"}, 201)
    item_path = f'/platform/dictionaries/{dictionary["id"]}/items'
    parent = api(item_path, "POST", {
        "value": "root", "label": "Root", "sortOrder": 0, "enabled": True, "remark": "QA root"}, 201)
    child = api(item_path, "POST", {
        "parentId": parent["id"], "value": "child", "label": "Child", "sortOrder": 1,
        "enabled": True, "remark": "QA child"}, 201)
    lookup_path = f'/dictionaries/{code}/items/{child["id"]}'
    assert api(lookup_path) == {"id": child["id"], "parentId": parent["id"], "value": "child", "label": "Child", "sortOrder": 1}
    assert api(f'/dictionaries/{code}/items/{parent["id"]}')["parentId"] is None
    plan = api(plugins + "/dictionaries/uninstall-plan", "POST")
    assert any("feedback" in blocker for blocker in plan["blockers"]), plan
    assert any("sensitive-words" in blocker for blocker in plan["blockers"]), plan
    api(plugins + "/dictionaries/uninstall", "POST", {"revision": plan["revision"]}, 409)
    module = next(row for row in api("/platform/modules") if row["id"] == "dictionaries")
    assert not module["core"], module
    api("/platform/modules/dictionaries/state", "PUT", {
        "enabled": False, "version": module["stateVersion"], "reason": "QA dependent provider"}, 409)
    # Remove each consumer explicitly. The provider must never cascade-remove business modules.
    lifecycle("feedback", "uninstall")
    lifecycle("sensitive-words", "uninstall")
    fields_path = "/platform/settings/profile-fields"
    original = api(fields_path)
    configured = copy.deepcopy(original["data"])
    configured["fields"].append({
        "key": "qaDictionary", "label": "QA dictionary", "description": "", "type": "SELECT",
        "visible": True, "required": False, "weight": 5, "placeholder": "", "groupKey": "custom",
        "groupLabel": "Custom", "sortNo": 100, "custom": True, "options": [], "dictionaryCode": code,
        "publicRegistrationAllowed": False,
        "placements": [{"page": "account-profile", "tab": "custom", "sortNo": 100}]})
    configured_view = api(fields_path, "PUT", {
        "version": original["version"], "data": configured, "changeReason": "QA reference guard"})
    profile = api("/account/profile-values")
    stored = api("/account/profile-values", "PUT", {
        "version": profile["version"], "schemaVersion": configured_view["version"],
        "values": {**profile["values"], "qaDictionary": child["id"]}})
    assert stored["values"]["qaDictionary"] == child["id"]
    plan = api(plugins + "/dictionaries/uninstall-plan", "POST")
    assert "dictionaryReferenced:settings" in plan["blockers"], plan
    api(plugins + "/dictionaries/uninstall", "POST", {"revision": plan["revision"]}, 409)
    module = next(row for row in api("/platform/modules") if row["id"] == "dictionaries")
    api("/platform/modules/dictionaries/state", "PUT", {
        "enabled": False, "version": module["stateVersion"], "reason": "QA persisted reference"}, 409)
    api(fields_path, "PUT", {
        "version": configured_view["version"], "data": original["data"], "changeReason": "QA manual detach"})
    # The schema editor is a declared consumer; remove it explicitly after detaching stored references.
    lifecycle("profile-console", "uninstall")
    saved = {table: fingerprint(table) for table in ("platform_dictionary", "platform_dictionary_item", "platform_user")}
    module = next(row for row in api("/platform/modules") if row["id"] == "dictionaries")
    disabled = api("/platform/modules/dictionaries/state", "PUT", {
        "enabled": False, "version": module["stateVersion"], "reason": "QA detached disable"})
    assert call(session, lookup_path)[0] != 200
    assert api("/auth/me")["username"] == "admin"
    api("/platform/modules/dictionaries/state", "PUT", {
        "enabled": True, "version": disabled["version"], "reason": "QA detached enable"})
    assert api(lookup_path)["label"] == "Child"
    lifecycle("dictionaries", "uninstall")
    for path in ("/platform/dictionaries", lookup_path, fields_path + "/dictionaries"):
        assert call(session, path)[0] != 200, path
    assert api("/auth/me")["username"] == "admin"
    assert api("/account/profile-fields")["data"]["fields"]
    assert login(client(), "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    for table, retained in saved.items():
        assert fingerprint(table) == retained, table
    lifecycle("dictionaries", "install")
    lifecycle("profile-console", "install")
    for table, retained in saved.items():
        assert fingerprint(table) == retained, table
    assert api(lookup_path)["label"] == "Child"
    exported = api(f'/platform/dictionaries/{dictionary["id"]}/export')
    assert exported["code"] == code
    assert next(row for row in exported["items"] if row["value"] == "child")["parentValue"] == "root"
    detached = api(fields_path)
    api(fields_path, "PUT", {
        "version": detached["version"], "data": configured, "changeReason": "QA restore binding"})
    restored_profile = api("/account/profile-values")
    assert restored_profile["values"]["qaDictionary"] == child["id"]
    assert restored_profile["dictionarySelections"]["qaDictionary"]["label"] == "Child"
    rebound = api(fields_path)
    api(fields_path, "PUT", {
        "version": rebound["version"], "data": original["data"], "changeReason": "QA restore original schema"})
    for identifier in ("feedback", "sensitive-words"):
        lifecycle(identifier, "install")
    assert api("/feedback/options")
    assert api("/platform/sensitive-words/options")
    print("PASS: optional dictionary package; reverse dependencies and persisted-field references block removal; explicit detachment; all dictionary/item/profile rows preserved; login works without provider; reinstall restores hierarchy and consumers")
finally:
    write(session, "/auth/logout", "POST", {})
