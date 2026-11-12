"""Actual authentication administration removal with retained kernel policy; private QA only."""
import copy
import json
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from smoke_support import client, call, write, sql, login, config
from plugin_lifecycle_support import preserve_plugin

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
prefix = "/platform/settings/"
sections = ["security", "authentication", "passkey", "wechat", "captcha", "registration"]
secret = "qa-wechat-" + uuid.uuid4().hex


def api(route, method="GET", payload=None, expected=200, actor=None):
    actor = actor or admin
    status, raw = call(actor, route) if method == "GET" else write(actor, route, method, payload or {})
    assert status == expected, (route, status, raw.decode())
    assert secret.encode() not in raw, "Provider secret exposed in response"
    return json.loads(raw) if raw else None


def snapshot():
    predicate = "section IN ('security','passkey','wechat')"
    predicates = {
        "platform_setting": predicate,
        "platform_setting_revision": "setting_id IN (SELECT id FROM platform_setting WHERE " + predicate + ")",
        "platform_secret": "secret_context IN (SELECT 'settings:'||id||':wechat-app-secret' FROM platform_setting WHERE section='wechat')",
        "platform_global_authentication_policy": "true",
        "platform_registration_policy": "true",
        "platform_captcha_settings": "true",
        "platform_navigation_menu": "id IN ('00000000-0000-0000-0000-000000000049','f15212df-d192-491d-a0c6-1c1cbf10171d','00000000-0000-0000-0000-000000000052','ca740762-2005-4b8d-8a4a-87af93a096ce','0e348dcc-ac99-4f40-a844-e14486e77663','230e98d2-91d3-4793-b9a8-98f4d96ca209')",
    }
    return {table: sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text) FROM " + table + " t WHERE " + where)
            for table, where in predicates.items()}


try:
    preserve_plugin(admin, "authentication-console", "install")
    initial = {section: api(prefix + section) for section in sections}
    suffix = uuid.uuid4().hex[:12]
    username = "qa_auth_console_" + suffix
    password = "Qa!8" + uuid.uuid4().hex + uuid.uuid4().hex
    user = api("/platform/users", "POST", {"username": username, "displayName": "QA authentication reader", "password": password}, 201)
    grants = ["platform:" + section + ":access" for section in sections]
    scoped = api("/platform/roles", "POST", {"code": "qa_auth_scope_" + suffix, "name": "QA authentication scoped reader", "enabled": True, "dataScope": "SELF", "authorities": grants}, 201)
    assigned = api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": user["version"], "roleIds": [scoped["id"]]})
    actor = client()
    assert login(actor, username, password) == 200
    for section in sections:
        api(prefix + section, expected=403, actor=actor)
    reader = api("/platform/roles", "POST", {"code": "qa_auth_read_" + suffix, "name": "QA authentication global reader", "enabled": True, "dataScope": "ALL", "authorities": grants}, 201)
    api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": assigned["version"], "roleIds": [reader["id"]]})
    assert login(actor, username, password) == 200
    for section in sections:
        view = api(prefix + section, actor=actor)
        api(prefix + section, "PUT", {"version": view["version"]}, 403, actor=actor)
    api(prefix + "registration/roles", expected=403, actor=actor)
    api(prefix + "passkey/history", actor=actor)
    api(prefix + "passkey/rollback", "POST", {"version": 1, "sourceVersion": 1}, 403, actor=actor)

    data = copy.deepcopy(initial["security"]["data"])
    data["passwordMinLength"] = 18
    security = api(prefix + "security", "PUT", {"version": initial["security"]["version"], "data": data, "changeReason": "QA retained runtime password policy"})
    api(prefix + "security", "PUT", {"version": initial["security"]["version"], "data": data}, 409)
    contenders = [client(), client()]
    for contender in contenders:
        assert login(contender, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    with ThreadPoolExecutor(max_workers=2) as pool:
        statuses = list(pool.map(lambda contender: write(contender, prefix + "security", "PUT", {"version": security["version"], "data": data})[0], contenders))
    assert sorted(statuses) == [200, 409], statuses

    passkey_data = copy.deepcopy(initial["passkey"]["data"])
    passkey_data.update(enabled=False, passwordlessEnabled=False, selfBindingEnabled=False, rpName="QA retained " + suffix)
    first = api(prefix + "passkey", "PUT", {"version": initial["passkey"]["version"], "data": passkey_data})
    second_data = {**passkey_data, "rpName": "QA temporary " + suffix}
    second = api(prefix + "passkey", "PUT", {"version": first["version"], "data": second_data})
    restored = api(prefix + "passkey/rollback", "POST", {"version": second["version"], "sourceVersion": first["version"], "changeReason": "QA retained policy rollback"})
    assert restored["data"] == passkey_data
    assert api(prefix + "passkey/history/" + str(first["version"]))["data"] == passkey_data

    api(prefix + "authentication", "PUT", {"version": initial["authentication"]["version"], "enabledMethods": ["PASSWORD"], "mfaRequired": False})
    api(prefix + "registration", "PUT", {"version": initial["registration"]["version"], "enabled": False, "channels": [], "defaultRoleId": initial["registration"]["defaultRoleId"]})
    captcha = copy.deepcopy(initial["captcha"]["data"])
    captcha.update(enabled=False, actions={key: "OFF" for key in captcha["actions"]})
    api(prefix + "captcha", "PUT", {"version": initial["captcha"]["version"], "data": captcha})
    wechat = initial["wechat"]
    configured = api(prefix + "wechat", "PUT", {"version": wechat["version"], "enabled": False, "appId": "qa_retained_" + suffix, "publicOrigin": wechat["publicOrigin"], "timeoutSeconds": 8, "appSecret": {"action": "REPLACE", "value": secret}})
    assert configured["appSecretConfigured"] is True
    assert sql("SELECT count(*) FROM platform_secret WHERE secret_context IN (SELECT 'settings:'||id||':wechat-app-secret' FROM platform_setting WHERE section='wechat')") == "1"
    current = {section: api(prefix + section) for section in sections}
    history = api(prefix + "passkey/history")
    public = {route: api(route) for route in ["/auth/methods", "/auth/registration/config", "/account/authentication-policy"]}
    before = snapshot()
    preserve_plugin(admin, "authentication-console", "uninstall")
    for section in sections:
        assert call(admin, prefix + section)[0] in {403, 404, 503}, section
    assert call(admin, prefix + "passkey/history")[0] in {403, 404, 503}
    assert snapshot() == before
    for route, expected in public.items():
        assert api(route) == expected, route
    # This password meets the baseline 12-character rule but must fail the published 18-character rule.
    weak_user = "qa_auth_short_" + suffix
    api("/platform/users", "POST", {"username": weak_user, "displayName": "QA rejected policy probe", "password": "Qz!9Lm#7Rt$5"}, 400)
    assert sql("SELECT count(*) FROM platform_user WHERE username='" + weak_user + "'") == "0"
    fresh = client()
    assert login(fresh, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    api("/auth/passkeys/registration/options", "POST", {"currentPassword": config["LUMIRA_BOOTSTRAP_PASSWORD"]}, 403)
    preserve_plugin(admin, "authentication-console", "install")
    assert snapshot() == before
    for section, expected in current.items():
        assert api(prefix + section) == expected, section
    assert api(prefix + "passkey/history") == history
    fixture = Path(".runtime/architecture-fix/authentication-console-fixture.json")
    fixture.write_text(json.dumps({"runToken": config["LUMIRA_QA_RUN_TOKEN"], "username": username, "password": password, "passwordMinLength": 18}))
    fixture.chmod(0o600)
    print("PASS: six actual optional authentication editors and passkey history withdraw/reinstall; exact policies, versions, revisions, encrypted provider credential and six legacy navigation rows retained; kernel login, public and account policy continue independently; published 18-character password rule and disabled passkey still enforced; global read/action separation and concurrent writes exactly 200/409")
finally:
    write(admin, "/auth/logout", "POST", {})
