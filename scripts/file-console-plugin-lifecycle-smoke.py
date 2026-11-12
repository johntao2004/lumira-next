"""Independent global file administration, scoped rights and preserved storage in marked QA only."""
import json
import uuid
from pathlib import Path
from smoke_support import client, call, write, token, sql, login, config
from plugin_lifecycle_support import preserve_plugin

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200

def api(path, method="GET", payload=None, expected=200, actor=None):
    actor = actor or admin
    status, body = call(actor, path) if method == "GET" else write(actor, path, method, payload or {})
    assert status == expected, (path, status, body.decode())
    return json.loads(body) if body else None

def upload(actor, filename, content, expected=201):
    status, body = call(actor, "/account/files?filename=" + filename, "POST", content,
                        {**token(actor), "Content-Type": "application/octet-stream"})
    assert status == expected, (filename, status, body.decode())
    return json.loads(body)

def snapshot(ids):
    quoted = ",".join("'" + identifier + "'" for identifier in ids)
    predicates = {"platform_file": "id IN (" + quoted + ")",
        "platform_file_blob_chunk": "file_id IN (" + quoted + ")",
        "platform_navigation_menu": "id='00000000-0000-0000-0000-000000000050'",
        "platform_setting": "section='file-quota'",
        "platform_setting_revision": "setting_id=(SELECT id FROM platform_setting WHERE section='file-quota')",
        "platform_audit": "target_id IN (" + quoted + ")"}
    return {table: sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text) FROM " + table + " t WHERE " + predicate)
            for table,predicate in predicates.items()}

try:
    preserve_plugin(admin, "file-console", "install")
    quota = api("/platform/settings/file-quota")
    configured = api("/platform/settings/file-quota", "PUT", {"version": quota["version"], "data": {"maximumBytes": 1_000_000, "maximumFiles": 2}})
    api("/platform/settings/file-quota", "PUT", {"version": quota["version"], "data": quota["data"]}, 409)
    suffix = uuid.uuid4().hex[:12]
    username = "qa_files_" + suffix
    password = "Qa!8" + uuid.uuid4().hex + uuid.uuid4().hex
    user = api("/platform/users", "POST", {"username": username, "displayName": "QA file owner " + suffix, "password": password}, 201)
    readonly = ["platform:files-all:access", "platform:files-all:preview", "platform:files-all:download"]
    scoped = api("/platform/roles", "POST", {"code": "qa_files_scoped_" + suffix, "name": "QA scoped files", "enabled": True, "dataScope": "SELF", "authorities": readonly}, 201)
    assignment = api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": user["version"], "roleIds": [scoped["id"]]})
    owner = client()
    assert login(owner, username, password) == 200
    content = b"QA retained file console bytes"
    retained = upload(owner, "qa-file-console-retained-" + suffix + ".txt", content)
    trash = upload(owner, "qa-file-console-trash-" + suffix + ".txt", b"QA restorable bytes")
    api("/platform/files", expected=403, actor=owner)
    api("/platform/files/" + retained["id"] + "/preview", expected=403, actor=owner)
    global_role = api("/platform/roles", "POST", {"code": "qa_files_global_" + suffix, "name": "QA readonly global files", "enabled": True, "dataScope": "ALL", "authorities": readonly}, 201)
    api("/platform/users/" + user["id"] + "/roles", "PUT", {"version": assignment["version"], "roleIds": [global_role["id"]]})
    assert login(owner, username, password) == 200
    assert api("/platform/files?search=" + suffix, actor=owner)["total"] == 2
    assert api("/platform/files/" + retained["id"] + "/preview", actor=owner)["text"] == content.decode()
    assert call(owner, "/platform/files/" + retained["id"] + "/content") == (200, content)
    api("/platform/files/" + retained["id"] + "?version=" + str(retained["version"]), "DELETE", expected=403, actor=owner)
    api("/account/files/" + trash["id"] + "?version=" + str(trash["version"]), "DELETE", expected=204, actor=owner)
    trash = api("/account/files/" + trash["id"], actor=owner)
    assert api("/platform/files/trash?search=" + suffix)["total"] == 1
    api("/platform/files/" + trash["id"] + "/restore?version=" + str(trash["version"]), "POST", expected=204)
    trash = api("/account/files/" + trash["id"], actor=owner)
    api("/platform/files/" + trash["id"] + "?version=" + str(trash["version"]), "DELETE", expected=204)
    trash = api("/account/files/" + trash["id"], actor=owner)
    api("/platform/files/" + trash["id"] + "/restore?version=0", "POST", expected=409)
    ids = [retained["id"], trash["id"]]
    before = snapshot(ids)
    usage = api("/account/files/usage", actor=owner)
    assert usage["maximumFiles"] == 2 and usage["maximumBytes"] == 1_000_000
    preserve_plugin(admin, "file-console", "uninstall")
    for path in ["/platform/files", "/platform/files/trash", "/platform/files/" + retained["id"] + "/content", "/platform/files/" + retained["id"] + "/preview", "/platform/settings/file-quota"]:
        assert call(admin, path)[0] != 200, path
    assert snapshot(ids) == before
    assert call(owner, "/account/files/" + retained["id"] + "/content") == (200, content)
    assert api("/account/files/usage", actor=owner)["maximumFiles"] == 2
    upload(owner, "qa-files-quota-denied-" + suffix + ".txt", b"cannot exceed stored quota", 409)
    assert snapshot(ids) == before
    preserve_plugin(admin, "file-console", "install")
    assert snapshot(ids) == before
    assert api("/platform/settings/file-quota") == configured
    assert call(admin, "/platform/files/" + retained["id"] + "/content") == (200, content)
    assert api("/platform/files/" + retained["id"] + "/preview")["text"] == content.decode()
    api("/platform/files/" + retained["id"] + "?version=0", "DELETE", expected=409)
    api("/platform/files/" + trash["id"] + "/purge?version=" + str(trash["version"]), "DELETE", expected=204)
    api("/account/files/" + trash["id"], expected=404, actor=owner)
    assert sql("SELECT count(*) FROM platform_file_blob_chunk WHERE file_id='" + trash["id"] + "'") == "0"
    assert api("/account/files/usage", actor=owner)["fileCount"] == 1
    upload(owner, "qa-files-after-purge-" + suffix + ".txt", b"released quota works again")
    api("/platform/settings/file-quota", "PUT", {"version": configured["version"], "data": quota["data"]})
    Path(".runtime/architecture-fix/file-console-fixture.json").write_text(json.dumps({"runToken": config["LUMIRA_QA_RUN_TOKEN"], "fileId": retained["id"], "filename": retained["originalName"], "username": username, "content": content.decode()}))
    print("PASS: actual optional file administration queries/preview/download/trash/restore/purge; global/scoped and read/action separation; file bytes+metadata+versions+quota history retained across unload/reinstall; core downloads and stored quota enforcement continue without the admin plugin")
finally:
    write(admin, "/auth/logout", "POST", {})
