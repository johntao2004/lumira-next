"""Actual site administration and persisted kernel policy in explicitly bound disposable QA."""
import base64
import json
import uuid
from pathlib import Path
from smoke_support import client, call, write, token, sql, login, config
from plugin_lifecycle_support import preserve_plugin

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
sections = ["branding", "watermark", "agreements", "maintenance"]
prefix = "/platform/settings/"


def api(path, method="GET", payload=None, expected=200, actor=None):
    actor = actor or admin
    status, body = call(actor, path) if method == "GET" else write(actor, path, method, payload or {})
    assert status == expected, (path, status, body.decode())
    return json.loads(body) if body else None


def snapshot(file_id):
    section_list = ",".join("'" + section + "'" for section in sections)
    predicates = {
        "platform_setting": "section IN (" + section_list + ")",
        "platform_setting_revision": "setting_id IN (SELECT id FROM platform_setting WHERE section IN (" + section_list + "))",
        "platform_file": "id='" + file_id + "'",
        "platform_file_blob_chunk": "file_id='" + file_id + "'",
        "platform_branding_file": "file_id='" + file_id + "'",
        "platform_navigation_menu": "id IN ('00000000-0000-0000-0000-000000000027','00000000-0000-0000-0000-000000000030','00000000-0000-0000-0000-000000000031','00000000-0000-0000-0000-000000000032')",
    }
    return {table: sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text) FROM " + table + " t WHERE " + predicate)
            for table, predicate in predicates.items()}


try:
    preserve_plugin(admin, "site-console", "install")
    baseline = {section: api(prefix + section) for section in sections}
    baseline_history_total = api(prefix + "branding/history")["total"]
    suffix = uuid.uuid4().hex[:12]
    username = "qa_site_" + suffix
    password = "Qa!8" + uuid.uuid4().hex + uuid.uuid4().hex
    user = api("/platform/users", "POST", {"username": username, "displayName": "QA site actor", "password": password}, 201)
    assert login(client(), username, password) == 200
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    scoped = api("/platform/roles", "POST", {"code": "qa_site_scoped_" + suffix, "name": "QA scoped site", "enabled": True, "dataScope": "SELF", "authorities": ["platform:branding:access", "platform:branding:update"]}, 201)
    assigned = api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": user["version"], "roleIds": [scoped["id"]]})
    reader = client()
    assert login(reader, username, password) == 200
    api(prefix + "branding", expected=403, actor=reader)
    api(prefix + "branding/history", expected=403, actor=reader)
    readonly = api("/platform/roles", "POST", {"code": "qa_site_reader_" + suffix, "name": "QA readonly site", "enabled": True, "dataScope": "ALL", "authorities": ["platform:branding:access"]}, 201)
    api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": assigned["version"], "roleIds": [readonly["id"]]})
    assert login(reader, username, password) == 200
    assert api(prefix + "branding", actor=reader) == baseline["branding"]
    assert api(prefix + "branding/history", actor=reader)["total"] == baseline_history_total
    api(prefix + "branding", "PUT", {"version": baseline["branding"]["version"], "data": baseline["branding"]["data"]}, 403, actor=reader)
    api(prefix + "branding/rollback", "POST", {"version": 1, "sourceVersion": 1}, 403, actor=reader)
    assert call(reader, prefix + "branding/assets?slot=FAVICON", "POST", png, {**token(reader), "Content-Type": "application/octet-stream"})[0] == 403
    allowed_role = api("/platform/roles", "POST", {"code": "qa_site_allow_" + suffix, "name": "QA maintenance allow " + suffix, "enabled": True, "dataScope": "SELF", "authorities": []}, 201)
    allowed_username = "qa_site_allow_" + suffix
    allowed_user = api("/platform/users", "POST", {"username": allowed_username, "displayName": "QA allowed site actor", "password": password}, 201)
    api("/platform/users/" + allowed_user["id"] + "/roles", "PUT", {"version": allowed_user["version"], "roleIds": [allowed_role["id"]]})
    status, body = call(admin, prefix + "branding/assets?slot=FAVICON", "POST", png,
                        {**token(admin), "Content-Type": "application/octet-stream"})
    assert status == 200, (status, body.decode())
    asset = json.loads(body)
    file_id = asset["url"].rsplit("/", 1)[1]
    data = {section: dict(view["data"]) for section, view in baseline.items()}
    data["branding"].update(websiteName="QA site " + suffix, companyName="QA company " + suffix, websiteFaviconUrl=asset["url"])
    data["watermark"].update(enabled=True, mode="TEXT", textLines=["QA watermark " + suffix])
    data["agreements"].update(userAgreementMarkdown="QA retained terms " + suffix, privacyAgreementMarkdown="QA retained privacy " + suffix)
    data["maintenance"].update(enabled=True, title="QA maintenance " + suffix, message="Stored policy survives editor unload", endAt=None, allowedRoleIds=[allowed_role["id"]])
    current = {}
    for section in sections:
        path = prefix + section
        saved = api(path, "PUT", {"version": baseline[section]["version"], "data": data[section], "changeReason": "QA publication"})
        api(path, "PUT", {"version": baseline[section]["version"], "data": data[section]}, 409)
        changed = dict(data[section])
        field = {"branding": "companyName", "watermark": "fontColor", "agreements": "userAgreementMarkdown", "maintenance": "message"}[section]
        changed[field] = "#123456" if section == "watermark" else "QA second publication " + suffix
        second = api(path, "PUT", {"version": saved["version"], "data": changed, "changeReason": "QA second publication"})
        restored = api(path + "/rollback", "POST", {"version": second["version"], "sourceVersion": saved["version"], "changeReason": "QA rollback"})
        assert restored["data"] == data[section]
        assert api(path) == {"version": restored["version"], "data": data[section]}
        historical = api(path + "/history/" + str(saved["version"]))
        assert historical["data"] == data[section]
        assert api(path + "/history")["total"] >= 3
        api(path + "/rollback", "POST", {"version": second["version"], "sourceVersion": saved["version"]}, 409)
        current[section] = api(path)
    assert api(prefix + "maintenance/roles")["total"] >= 2
    invalid_roles = {**data["maintenance"], "allowedRoleIds": [str(uuid.uuid4())]}
    api(prefix + "maintenance", "PUT", {"version": current["maintenance"]["version"], "data": invalid_roles}, 400)
    assert api(prefix + "maintenance") == current["maintenance"]
    assert login(client(), username, password) == 503
    assert login(client(), allowed_username, password) == 200
    anonymous = client()
    public = {section: api("/public/" + section, actor=anonymous) for section in sections}
    assert public["maintenance"]["active"] is True
    status, stored_png = call(anonymous, asset["url"])
    assert status == 200 and stored_png.startswith(b"\x89PNG")
    before = snapshot(file_id)
    preserve_plugin(admin, "site-console", "uninstall")
    for section in sections:
        for path in [prefix + section, prefix + section + "/history", prefix + section + "/history/1"]:
            assert call(admin, path)[0] in {403, 404, 503}, path
    assert snapshot(file_id) == before
    for section in sections:
        assert api("/public/" + section, actor=anonymous) == public[section]
    assert call(anonymous, asset["url"]) == (200, stored_png)
    assert login(client(), username, password) == 503
    assert login(client(), allowed_username, password) == 200
    assert login(client(), "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    preserve_plugin(admin, "site-console", "install")
    assert snapshot(file_id) == before
    for section in sections:
        assert api(prefix + section) == current[section]
    assert call(anonymous, asset["url"]) == (200, stored_png)
    # End the deliberately enabled fixture maintenance before unrelated acceptance scenarios.
    disabled = api(prefix + "maintenance", "PUT", {"version": current["maintenance"]["version"], "data": baseline["maintenance"]["data"], "changeReason": "End isolated maintenance fixture"})
    assert disabled["data"]["enabled"] is False
    assert login(client(), username, password) == 200
    Path(".runtime/architecture-fix/site-console-fixture.json").write_text(json.dumps({"runToken": config["LUMIRA_QA_RUN_TOKEN"], "data": data, "assetUrl": asset["url"]}))
    print("PASS: actual optional site uploads/read/write/history/rollback, scoped/global/read/action separation and stale guards; exact settings/revisions/nav/branding metadata+bytes preserved across unload/reinstall; published values/assets and maintenance login restrictions continue without management; admin remains reachable and ordinary login recovers after disabling fixture maintenance")
finally:
    write(admin, "/auth/logout", "POST", {})
