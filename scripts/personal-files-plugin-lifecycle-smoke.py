"""Actual owner workflow removal; only the explicitly guarded private QA database is writable."""
import json
import uuid
import struct
import zlib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from smoke_support import client, call, write, token, sql, login, config
from plugin_lifecycle_support import preserve_plugin

admin = client()
assert login(admin, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200


def api(route, method="GET", payload=None, expected=200, actor=None):
    actor = actor or admin
    status, raw = call(actor, route) if method == "GET" else write(actor, route, method, payload or {})
    assert status == expected, (route, status, raw.decode())
    return json.loads(raw) if raw else None


def snapshot(owner):
    predicates = {
        "platform_file": "owner_id='" + owner + "'",
        "platform_file_blob_chunk": "file_id IN (SELECT id FROM platform_file WHERE owner_id='" + owner + "')",
        "platform_account_media": "user_id='" + owner + "'",
        "platform_navigation_menu": "id='00000000-0000-0000-0000-000000000011'",
        "platform_audit": "target_id IN (SELECT id FROM platform_file WHERE owner_id='" + owner + "')",
    }
    return {table: sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text) FROM " + table + " t WHERE " + where)
            for table, where in predicates.items()}


try:
    preserve_plugin(admin, "personal-files", "install")
    suffix = uuid.uuid4().hex[:12]
    username = "qa_personal_files_" + suffix
    password = "Qa!8" + uuid.uuid4().hex + uuid.uuid4().hex
    user = api("/platform/users", "POST", {"username": username, "displayName": "QA personal file owner", "password": password}, 201)
    owner = client()
    assert login(owner, username, password) == 200
    content = ("QA preserved personal bytes " + suffix).encode()

    def upload(name, body):
        status, raw = call(owner, "/account/files?filename=" + name, "POST", body, {**token(owner), "Content-Type": "application/octet-stream"})
        assert status == 201, (status, raw.decode())
        return json.loads(raw)

    retained = upload("qa-personal-retained-" + suffix + ".txt", content)
    trashed = upload("qa-personal-trash-" + suffix + ".txt", b"QA retained recycle bin bytes")
    assert api("/account/files?search=" + suffix, actor=owner)["total"] == 2
    api("/account/files/" + retained["id"], expected=404)
    api("/account/files/" + retained["id"] + "/preview", expected=404)
    api("/account/files/" + retained["id"] + "?version=" + str(retained["version"]), "DELETE", expected=404)
    assert call(client(), "/account/files")[0] == 401
    contenders = [client(), client()]
    for contender in contenders:
        assert login(contender, username, password) == 200
    with ThreadPoolExecutor(max_workers=2) as pool:
        statuses = list(pool.map(lambda contender: write(contender, "/account/files/" + trashed["id"] + "?version=" + str(trashed["version"]), "DELETE", {})[0], contenders))
    assert sorted(statuses) == [204, 409], statuses
    trash = api("/account/files/" + trashed["id"], actor=owner)
    assert trash["state"] == "TRASHED"
    api("/account/files/" + trash["id"] + "/restore?version=" + str(trashed["version"]), "POST", expected=409, actor=owner)
    api("/account/files/" + trash["id"] + "/restore?version=" + str(trash["version"]), "POST", expected=204, actor=owner)
    restored = api("/account/files/" + trash["id"], actor=owner)
    api("/account/files/" + trash["id"] + "?version=" + str(restored["version"]), "DELETE", expected=204, actor=owner)
    trash = api("/account/files/" + trash["id"], actor=owner)
    assert api("/account/files/trash?search=" + suffix, actor=owner)["total"] == 1
    usage = api("/account/files/usage", actor=owner)
    assert usage["fileCount"] == 2
    avatar = api("/account/avatar", actor=owner)
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 1, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\xff\x00\xff\x00\xff")) + chunk(b"IEND", b"")
    status, raw = call(owner, "/account/avatar?version=" + str(avatar["version"]), "POST", png,
                       {**token(owner), "Content-Type": "application/octet-stream"})
    assert status == 200, (status, raw.decode())
    avatar = json.loads(raw)
    assert avatar["fileId"]
    usage = api("/account/files/usage", actor=owner)
    assert usage["fileCount"] == 3
    avatar_bytes = call(owner, "/account/avatar/content?version=" + str(avatar["version"]))
    assert avatar_bytes[0] == 200
    before = snapshot(user["id"])
    preserve_plugin(admin, "personal-files", "uninstall")
    for route in ["/account/files", "/account/files/usage", "/account/files/trash", "/account/files/" + retained["id"], "/account/files/" + retained["id"] + "/content", "/account/files/" + retained["id"] + "/preview"]:
        assert call(owner, route)[0] in {403, 404, 503}, route
    assert snapshot(user["id"]) == before
    assert call(admin, "/platform/files/" + retained["id"] + "/content") == (200, content)
    assert api("/platform/files/" + retained["id"] + "/preview")["text"] == content.decode()
    assert api("/account/avatar", actor=owner) == avatar
    assert call(owner, "/account/avatar/content?version=" + str(avatar["version"])) == avatar_bytes
    assert api("/platform/users/" + user["id"] + "/file-quota")["fileCount"] == 3
    preserve_plugin(admin, "personal-files", "install")
    assert snapshot(user["id"]) == before
    assert api("/account/files/usage", actor=owner) == usage
    assert api("/account/files/" + retained["id"], actor=owner) == retained
    assert api("/account/files/" + trash["id"], actor=owner) == trash
    assert call(owner, "/account/files/" + retained["id"] + "/content") == (200, content)
    fixture = Path(".runtime/architecture-fix/personal-files-fixture.json")
    fixture.write_text(json.dumps({"runToken": config["LUMIRA_QA_RUN_TOKEN"], "username": username, "password": password, "fileId": retained["id"], "filename": retained["originalName"], "content": content.decode()}))
    fixture.chmod(0o600)
    print("PASS: actual optional personal file owner workflow; cross-owner reads/writes denied, parallel same-version trash exactly204/409, stale restore denied; all owned metadata/blob bytes/audit and pinned navigation unchanged through unload/reinstall; independent admin download/preview, quota projection and core avatar remain available; original identities, versions, usage and ready/trash state restored")
finally:
    write(admin, "/auth/logout", "POST", {})
