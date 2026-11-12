"""Actual optional schema editor and retained account projections in explicitly bound disposable QA."""
import copy
import json
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from smoke_support import client, call, write, sql, login, config
from plugin_lifecycle_support import preserve_plugin

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
path = "/platform/settings/profile-fields"


def api(route, method="GET", payload=None, expected=200, actor=None):
    actor = actor or admin
    status, raw = call(actor, route) if method == "GET" else write(actor, route, method, payload or {})
    assert status == expected, (route, status, raw.decode())
    return json.loads(raw) if raw else None


def snapshot(user_id, dictionary_id):
    predicates = {
        "platform_setting": "section='profile-fields'",
        "platform_setting_revision": "setting_id IN (SELECT id FROM platform_setting WHERE section='profile-fields')",
        "platform_navigation_menu": "id='00000000-0000-0000-0000-000000000029'",
        "platform_dictionary": "id='" + dictionary_id + "'",
        "platform_dictionary_item": "dictionary_id='" + dictionary_id + "'",
        "platform_profile_dictionary_selection": "user_id='" + user_id + "'",
    }
    result = {table: sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text) FROM " + table + " t WHERE " + predicate)
              for table, predicate in predicates.items()}
    result["profile_values"] = sql("SELECT md5(jsonb_build_object('id',id,'version',version,'values',profile_values)::text) FROM platform_user WHERE id='" + user_id + "'")
    return result


try:
    preserve_plugin(admin, "profile-console", "install")
    initial = api(path)
    suffix = uuid.uuid4().hex[:12]
    text_key, choice_key = "qaProfile" + suffix, "qaChoice" + suffix
    username = "qa_profile_" + suffix
    password = "Qa!8" + uuid.uuid4().hex + uuid.uuid4().hex
    user = api("/platform/users", "POST", {"username": username, "displayName": "QA profile actor", "password": password}, 201)
    scoped = api("/platform/roles", "POST", {"code": "qa_profile_scope_" + suffix, "name": "QA scoped profile", "enabled": True, "dataScope": "SELF", "authorities": ["platform:profile-fields:access", "platform:profile-fields:update", "platform:profile-fields:read-dictionaries"]}, 201)
    assignment = api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": user["version"], "roleIds": [scoped["id"]]})
    actor = client()
    assert login(actor, username, password) == 200
    api(path, expected=403, actor=actor)
    api(path + "/dictionaries", expected=403, actor=actor)
    readonly = api("/platform/roles", "POST", {"code": "qa_profile_read_" + suffix, "name": "QA readonly profile", "enabled": True, "dataScope": "ALL", "authorities": ["platform:profile-fields:access"]}, 201)
    api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": assignment["version"], "roleIds": [readonly["id"]]})
    assert login(actor, username, password) == 200
    assert api(path, actor=actor) == initial
    api(path + "/history", actor=actor)
    api(path, "PUT", {"version": initial["version"], "data": initial["data"]}, 403, actor=actor)
    api(path + "/rollback", "POST", {"version": 1, "sourceVersion": 1}, 403, actor=actor)
    api(path + "/dictionaries", expected=403, actor=actor)
    self_service = api("/platform/roles", "POST", {"code": "qa_profile_self_" + suffix, "name": "QA profile self service", "enabled": True, "dataScope": "SELF", "authorities": ["platform:users:access"]}, 201)
    target = api("/platform/users/" + user["id"])
    api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": target["version"], "roleIds": [self_service["id"]]})
    assert login(actor, username, password) == 200
    api(path, expected=403, actor=actor)
    code = "qa_profile_" + suffix
    dictionary = api("/platform/dictionaries", "POST", {"code": code, "name": "QA retained profile choices", "enabled": True, "structure": "FLAT", "remark": "QA profile console"}, 201)
    item = api("/platform/dictionaries/" + dictionary["id"] + "/items", "POST", {"value": "chosen", "label": "Retained profile choice " + suffix, "sortOrder": 0, "enabled": True, "remark": "QA profile console"}, 201)
    data = copy.deepcopy(initial["data"])
    text = {"key": text_key, "label": "Retained profile text " + suffix, "description": "", "type": "TEXT", "visible": True, "required": False, "weight": 5, "placeholder": "", "groupKey": "custom", "groupLabel": "QA retained profile", "sortNo": 100, "custom": True, "options": [], "publicRegistrationAllowed": True, "placements": [{"page": "account-profile", "tab": "custom", "sortNo": 100}, {"page": "admin-user-editor", "tab": "profile", "sortNo": 100}, {"page": "public-registration", "tab": "additional", "sortNo": 100}]}
    choice = {**text, "key": choice_key, "label": "Retained profile choice " + suffix, "type": "SELECT", "dictionaryCode": code, "sortNo": 101}
    data["fields"].extend([text, choice])
    saved = api(path, "PUT", {"version": initial["version"], "data": data})
    api(path, "PUT", {"version": initial["version"], "data": data}, 409)
    second_data = copy.deepcopy(data)
    next(field for field in second_data["fields"] if field["key"] == text_key)["label"] += " second"
    second = api(path, "PUT", {"version": saved["version"], "data": second_data})
    restored = api(path + "/rollback", "POST", {"version": second["version"], "sourceVersion": saved["version"], "changeReason": "QA profile rollback"})
    assert restored["data"] == saved["data"]
    assert api(path + "/history/" + str(saved["version"]))["data"] == saved["data"]
    assert api(path + "/history")["total"] >= 3
    current = api(path)
    # The referenced dictionary may grow after publication; both entry points must apply the current bound.
    sql("INSERT INTO platform_dictionary_item(id,dictionary_id,value,label,enabled,remark) SELECT gen_random_uuid(),'" + dictionary["id"] + "','qa_capacity_'||i,'QA capacity '||i,true,'QA capacity fixture' FROM generate_series(1,200) i")
    try:
        total = api(path + "/history")["total"]
        api(path, "PUT", {"version": current["version"], "data": current["data"]}, 400)
        api(path + "/rollback", "POST", {"version": current["version"], "sourceVersion": saved["version"]}, 400)
        assert api(path) == current and api(path + "/history")["total"] == total
    finally:
        sql("DELETE FROM platform_dictionary_item WHERE dictionary_id='" + dictionary["id"] + "' AND starts_with(value,'qa_capacity_') AND remark='QA capacity fixture'")
    contenders = [client(), client()]
    for contender in contenders:
        assert login(contender, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda contender: write(contender, path, "PUT", {"version": current["version"], "data": current["data"]})[0], contenders))
    assert sorted(results) == [200, 409], results
    current = api(path)
    profile = api("/account/profile-values", actor=actor)
    retained_value = "Retained personal profile " + suffix
    values = api("/account/profile-values", "PUT", {"version": profile["version"], "schemaVersion": current["version"], "values": {**profile["values"], text_key: retained_value, choice_key: item["id"]}}, actor=actor)
    assert values["values"][text_key] == retained_value and values["dictionarySelections"][choice_key]["itemId"] == item["id"]
    owner_selection = "/account/profile-dictionaries/" + choice_key + "/items"
    editor_selection = "/platform/users/" + user["id"] + "/profile-dictionaries/" + choice_key + "/items"
    admin_id = api("/auth/me")["userId"]
    for selection in (owner_selection, editor_selection):
        assert api(selection, actor=actor)["items"][0]["id"] == item["id"]
        assert api(selection + "/" + item["id"], actor=actor)["label"] == item["label"]
        api(selection + "?pageSize=101", expected=400, actor=actor)
    api("/account/profile-dictionaries/" + text_key + "/items", expected=404, actor=actor)
    api("/account/profile-dictionaries/" + code + "/items", expected=404, actor=actor)
    api("/dictionaries/" + code + "/items", expected=403, actor=actor)
    for suffix_path in ("", "/" + item["id"]):
        api("/platform/users/" + admin_id + "/profile-dictionaries/" + choice_key + "/items" + suffix_path, expected=403, actor=actor)
    published = api("/account/profile-fields", actor=actor)
    assert any(field["key"] == text_key for field in published["data"]["fields"])
    plan = api("/platform/modules/plugins/dictionaries/uninstall-plan", "POST")
    assert any("profile-console" in blocker for blocker in plan["blockers"]), plan
    before = snapshot(user["id"], dictionary["id"])
    preserve_plugin(admin, "profile-console", "uninstall")
    for route in [path, path + "/history", path + "/history/1", path + "/dictionaries"]:
        assert call(admin, route)[0] in {403, 404, 503}, route
    assert snapshot(user["id"], dictionary["id"]) == before
    assert api("/account/profile-fields", actor=actor) == published
    assert api("/account/profile-values", actor=actor) == values
    assert api(owner_selection + "/" + item["id"], actor=actor)["id"] == item["id"]
    assert api(editor_selection, actor=actor)["items"][0]["id"] == item["id"]
    assert api("/dictionaries/" + code + "/items/" + item["id"])["id"] == item["id"]
    plan = api("/platform/modules/plugins/dictionaries/uninstall-plan", "POST")
    assert "dictionaryReferenced:settings" in plan["blockers"], plan
    assert not any("profile-console" in blocker for blocker in plan["blockers"]), plan
    preserve_plugin(admin, "profile-console", "install")
    assert snapshot(user["id"], dictionary["id"]) == before
    assert api(path) == current
    assert api("/account/profile-values", actor=actor) == values
    assert api(owner_selection + "/" + item["id"], actor=actor)["id"] == item["id"]
    assert api(editor_selection, actor=actor)["items"][0]["id"] == item["id"]
    Path(".runtime/architecture-fix/profile-console-fixture.json").write_text(json.dumps({"runToken": config["LUMIRA_QA_RUN_TOKEN"], "textKey": text_key, "textLabel": text["label"], "choiceKey": choice_key, "choiceLabel": choice["label"], "value": retained_value, "username": username, "password": password}))
    Path(".runtime/architecture-fix/profile-console-fixture.json").chmod(0o600)
    print("PASS: actual optional profile schema publication/rollback and dictionary selection; both publication and restore enforce current public option limits, parallel versions exactly200/409 and global page/action separation; schema/history/nav/dictionary/reference/profile values retained through unload/reinstall; core account projection, personal values and field-scoped account/editor lookups survive editor removal without management grants or cross-target access, declared and stored provider dependencies require explicit manual detachment")
finally:
    write(admin, "/auth/logout", "POST", {})
