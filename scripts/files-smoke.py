from hashlib import sha256
from uuid import uuid4
import http.client
import urllib.parse
import urllib.request

from smoke_support import *


owner = str(uuid4())
other = str(uuid4())
name = "qa_file_" + uuid4().hex
second_name = name + "_other"
all_files = []
retained_pending = {}
a = client()
b = client()


def upload(actor, filename, body, csrf=True):
    headers = {"Content-Type": "application/octet-stream"}
    if csrf:
        headers.update(token(actor))
    return call(
        actor,
        "/account/files?" + urllib.parse.urlencode({"filename": filename}),
        "POST",
        body,
        headers,
    )


def file_snapshot(file_id):
    result = sql(
        f"""
        SELECT storage_backend || '|' || state || '|' ||
               coalesce(size_bytes,reserved_bytes)::text || '|' ||
               (SELECT count(*)::text FROM platform_file_blob_chunk c WHERE c.file_id=f.id) || '|' ||
               coalesce((
                   SELECT floor(extract(epoch FROM (min(c.retire_after)-clock_timestamp())))::bigint::text
                   FROM platform_file_blob_chunk c WHERE c.file_id=f.id
               ), '')
          FROM platform_file f WHERE f.id='{file_id}' AND f.owner_id='{owner}'
        """
    )
    if not result:
        return None
    backend, state, size, chunks, seconds_to_retirement = result.split("|")
    return {
        "backend": backend,
        "state": state,
        "size": int(size),
        "chunks": int(chunks),
        "seconds_to_retirement": (
            int(seconds_to_retirement) if seconds_to_retirement else None
        ),
    }


def page(actor, path):
    status, body = call(actor, path)
    assert status == 200, (status, body)
    return json.loads(body)


def quota_usage(actor):
    return json.loads(call(actor, "/account/files/usage")[1])


def assert_hidden_from_lists(actor, file_id):
    assert file_id not in {
        row["id"] for row in page(actor, "/account/files?pageSize=100")["items"]
    }
    assert file_id not in {
        row["id"] for row in page(actor, "/account/files/trash?pageSize=100")["items"]
    }


def trash_and_purge(actor, file_id, size_bytes):
    before = quota_usage(actor)
    assert call(
        actor,
        f"/account/files/{file_id}?version=1",
        "DELETE",
        headers=token(actor),
    )[0] == 204

    trashed_status, trashed_body = call(actor, f"/account/files/{file_id}")
    assert trashed_status == 200, (trashed_status, trashed_body)
    trashed = json.loads(trashed_body)
    assert trashed["state"] == "TRASHED" and trashed["version"] == 2
    assert file_id not in {
        row["id"] for row in page(actor, "/account/files?pageSize=100")["items"]
    }
    assert file_id in {
        row["id"] for row in page(actor, "/account/files/trash?pageSize=100")["items"]
    }
    status, body = call(actor, f"/account/files/{file_id}/content")
    assert status == 409 and json.loads(body)["detail"] == "fileNotReady"
    assert call(
        actor,
        f"/account/files/{file_id}/purge?version=1",
        "DELETE",
        headers=token(actor),
    )[0] == 409
    assert call(
        actor,
        f"/account/files/{file_id}/purge?version=2",
        "DELETE",
        headers=token(actor),
    )[0] == 204

    snapshot = file_snapshot(file_id)
    after = quota_usage(actor)
    if snapshot is not None:
        # New uploads are stored in PostgreSQL. Published chunks remain charged until
        # their 24-hour retirement and a bounded cleanup pass removes them.
        assert snapshot["backend"] == "postgres"
        assert snapshot["state"] == "DELETING"
        assert snapshot["size"] == size_bytes and snapshot["chunks"] > 0
        assert 23 * 60 * 60 <= snapshot["seconds_to_retirement"] <= 24 * 60 * 60 + 30
        assert after["chargedBytes"] == before["chargedBytes"]
        assert after["fileCount"] == before["fileCount"]
        assert after["pendingDeletingCount"] == before["pendingDeletingCount"] + 1
        assert after["pendingDeletingBytes"] == before["pendingDeletingBytes"] + size_bytes
        assert_hidden_from_lists(actor, file_id)
        status, body = call(actor, f"/account/files/{file_id}/content")
        assert status == 409 and json.loads(body)["detail"] == "fileNotReady"
        retained_pending[file_id] = size_bytes
    else:
        # Local objects are unlinked synchronously, and zero-byte PostgreSQL objects
        # have no chunks to retire. This script does not age or delete live chunks.
        assert after["chargedBytes"] == before["chargedBytes"] - size_bytes
        assert after["fileCount"] == before["fileCount"] - 1
        assert after["pendingDeletingCount"] == before["pendingDeletingCount"]
        assert after["pendingDeletingBytes"] == before["pendingDeletingBytes"]
        assert call(actor, f"/account/files/{file_id}")[0] == 404
        assert call(actor, f"/account/files/{file_id}/content")[0] == 404


def assert_backend_content_length_rejection(actor):
    if not BACKEND_BASE_URL:
        print(
            "SKIP: direct backend Content-Length check; set LUMIRA_BACKEND_BASE_URL "
            "to an explicit QA backend origin"
        )
        return

    parsed = urllib.parse.urlsplit(BACKEND_BASE_URL)
    assert parsed.scheme in {"http", "https"} and parsed.hostname
    assert not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
    target = parsed.path.rstrip("/") + "/api/account/files?filename=large.bin"
    raw_url = BACKEND_BASE_URL + "/api/account/files?filename=large.bin"
    cookie_request = urllib.request.Request(raw_url)
    cookie_jar(actor).add_cookie_header(cookie_request)
    headers = authorization_context_headers(
        actor,
        "/account/files?filename=large.bin",
        {
            **token(actor),
            "Content-Type": "application/octet-stream",
            "Content-Length": str(20 * 1024 * 1024 + 1),
        },
    )
    if cookie_request.get_header("Cookie"):
        headers["Cookie"] = cookie_request.get_header("Cookie")

    connector = (
        http.client.HTTPSConnection
        if parsed.scheme == "https"
        else http.client.HTTPConnection
    )
    connection = connector(parsed.hostname, parsed.port, timeout=20)
    try:
        connection.putrequest("POST", target)
        for key, value in headers.items():
            connection.putheader(key, value)
        connection.endheaders()
        assert connection.getresponse().status == 413
    finally:
        connection.close()


try:
    sql(
        f"""
        INSERT INTO platform_user(id,username,display_name,password_hash)
        SELECT '{owner}','{name}','File QA',password_hash
          FROM platform_user WHERE username='admin';
        INSERT INTO platform_user(id,username,display_name,password_hash)
        SELECT '{other}','{second_name}','File QA',password_hash
          FROM platform_user WHERE username='admin'
        """
    )
    assert login(a, name, config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    assert login(b, second_name, config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
    assert upload(a, "bad/path", b"x")[0] == 400
    assert upload(a, "test", b"x", csrf=False)[0] == 403

    created = []
    for filename in ["Alpha report.txt", "alpha%_report.txt", "Other.txt"]:
        status, body = upload(a, filename, b"list fixture")
        assert status == 201, (status, body)
        file_id = json.loads(body)["id"]
        created.append(file_id)
        all_files.append(file_id)

    first = page(a, "/account/files?pageSize=2")
    second_page = page(a, "/account/files?page=2&pageSize=2")
    assert first["total"] == 3 and len(first["items"]) == 2 and len(second_page["items"]) == 1
    assert len({row["id"] for row in first["items"] + second_page["items"]}) == 3
    assert page(a, "/account/files?search=ALPHA")["total"] == 2
    assert page(a, "/account/files?search=%25_")["total"] == 1
    assert page(b, "/account/files")["total"] == 0
    assert call(client(), "/account/files")[0] == 401
    assert call(a, "/account/files?pageSize=101")[0] == 400
    assert call(a, "/account/files?page=0")[0] == 400

    for file_id in created[:]:
        trash_and_purge(a, file_id, len(b"list fixture"))
        created.remove(file_id)

    for body in [b"hello files", b""]:
        status, response = upload(a, "report.txt", body)
        assert status == 201, (status, response)
        entry = json.loads(response)
        file_id = entry["id"]
        created.append(file_id)
        all_files.append(file_id)
        path = "/account/files/" + file_id
        assert entry["state"] == "READY"
        assert entry["sizeBytes"] == len(body)
        assert entry["sha256"] == sha256(body).hexdigest()
        assert call(a, path)[0] == 200
        assert call(client(), path)[0] == 401
        assert call(b, path)[0] == 404
        assert call(b, path + "/content")[0] == 404
        assert call(b, path + "?version=1", "DELETE", headers=token(b))[0] == 404

        request_path = path + "/content"
        request = urllib.request.Request(
            API_BASE_URL + request_path,
            headers=authorization_context_headers(a, request_path),
        )
        with a.open(request, timeout=20) as download:
            assert download.read() == body
            assert download.headers["Content-Type"].startswith("application/octet-stream")
            assert download.headers["Content-Disposition"].startswith("attachment;")
            assert download.headers["X-Content-Type-Options"] == "nosniff"

        assert call(a, path + "?version=0", "DELETE", headers=token(a))[0] == 409
        assert sql(
            f"SELECT string_agg(action,',' ORDER BY action) FROM platform_audit WHERE target_id='{file_id}'"
        ) == "file.upload"
        trash_and_purge(a, file_id, len(body))
        created.remove(file_id)

    assert_backend_content_length_rejection(a)

    assert page(a, "/account/files?pageSize=100")["total"] == 0
    assert page(a, "/account/files/trash?pageSize=100")["total"] == 0
    usage = quota_usage(a)
    assert usage["pendingDeletingCount"] == len(retained_pending)
    assert usage["pendingDeletingBytes"] == sum(retained_pending.values())
    assert usage["fileCount"] == len(retained_pending)
    assert usage["chargedBytes"] == sum(retained_pending.values())
    assert sql(
        f"SELECT count(*) FROM platform_file WHERE owner_id='{owner}' AND state<>'DELETING'"
    ) == "0"
    for file_id in all_files:
        expected_actions = "file.purgeRequested,file.trash,file.upload"
        if file_id not in retained_pending:
            expected_actions = "file.cleanup," + expected_actions
        assert sql(
            f"SELECT string_agg(action,',' ORDER BY action) FROM platform_audit WHERE target_id='{file_id}'"
        ) == expected_actions
    print(
        "PASS: raw uploads and digest, attachment download with revision header, owner/CSRF "
        "checks, pagination/search, trash/purge, hidden pending files with retained quota, "
        "and zero-byte immediate release"
    )
finally:
    # This SQL removes only this script's UUID-scoped fixtures. It is teardown, not evidence
    # that the product's physical-retirement lifecycle completed.
    sql(
        f"""
        DELETE FROM platform_audit
         WHERE target_id IN (SELECT id FROM platform_file WHERE owner_id IN ('{owner}','{other}'));
        DELETE FROM platform_file_blob_chunk
         WHERE file_id IN (SELECT id FROM platform_file WHERE owner_id IN ('{owner}','{other}'));
        DELETE FROM platform_file WHERE owner_id IN ('{owner}','{other}');
        DELETE FROM platform_audit WHERE actor IN ('{name}','{second_name}');
        DELETE FROM spring_session WHERE principal_name IN ('{name}','{second_name}');
        DELETE FROM platform_user WHERE id IN ('{owner}','{other}')
        """
    )
