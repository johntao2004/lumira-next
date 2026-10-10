from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from uuid import uuid4

from smoke_support import *


owner = str(uuid4())
name = "qa_quota_" + uuid4().hex
seed = str(uuid4())
created = []
actors = [client(), client()]


def upload(actor, body):
    return call(
        actor,
        "/account/files?filename=quota.txt",
        "POST",
        body,
        {"Content-Type": "application/octet-stream", **token(actor)},
    )


def usage(actor):
    status, body = call(actor, "/account/files/usage")
    assert status == 200, (status, body)
    return json.loads(body)


def page(actor, path):
    status, body = call(actor, path)
    assert status == 200, (status, body)
    return json.loads(body)


def file_snapshot(file_id):
    return sql(
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


try:
    sql(
        f"""
        INSERT INTO platform_user(id,username,display_name,password_hash)
        SELECT '{owner}','{name}','Quota QA',password_hash
          FROM platform_user WHERE username='admin'
        """
    )
    for actor in actors:
        assert login(actor, name, config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200

    actor = actors[0]
    initial = usage(actor)
    assert initial["chargedBytes"] == 0 and initial["fileCount"] == 0
    capacity = initial["maximumBytes"]
    slots = initial["maximumFiles"]
    assert capacity > 1 and 1 < slots <= 5000
    assert call(client(), "/account/files/usage")[0] == 401

    # Synthetic reservation fixture: it contains no storage bytes and is removed only
    # as fixture cleanup below.
    sql(
        f"""
        INSERT INTO platform_file(id,owner_id,storage_backend,original_name,reserved_bytes)
        VALUES('{seed}','{owner}','postgres','QA byte reservation fixture',{capacity - 1})
        """
    )
    assert upload(actor, b"xx")[0] == 409

    gate = Barrier(2)

    def race(candidate):
        gate.wait()
        return upload(candidate, b"x")

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(race, actors))
    for status, body in results:
        if status == 201:
            created.append(json.loads(body)["id"])
    assert sorted(status for status, body in results) == [201, 409]
    assert len(created) == 1
    file_id = created[0]
    assert file_snapshot(file_id).startswith("postgres|READY|1|1|")
    assert usage(actor)["chargedBytes"] == capacity

    # Move the real upload through trash and explicit purge. Its PostgreSQL chunk remains
    # charged for the configured 24-hour grace period and is hidden from both file lists.
    assert call(
        actor,
        f"/account/files/{file_id}?version=1",
        "DELETE",
        headers=token(actor),
    )[0] == 204
    assert call(
        actor,
        f"/account/files/{file_id}/purge?version=2",
        "DELETE",
        headers=token(actor),
    )[0] == 204
    pending = file_snapshot(file_id).split("|")
    assert pending[0:4] == ["postgres", "DELETING", "1", "1"]
    assert 23 * 60 * 60 <= int(pending[4]) <= 24 * 60 * 60 + 30
    visible = page(actor, "/account/files?pageSize=100")
    assert {row["id"] for row in visible["items"]} == {seed}
    assert visible["total"] == 1
    assert page(actor, "/account/files/trash?pageSize=100")["total"] == 0
    after_purge = usage(actor)
    assert after_purge["chargedBytes"] == capacity
    assert after_purge["fileCount"] == 2
    assert after_purge["pendingDeletingCount"] == 1
    assert after_purge["pendingDeletingBytes"] == 1
    assert upload(actor, b"x")[0] == 409

    # Remove only the synthetic byte-reservation row. The real pending upload remains
    # charged and is not aged or deleted by SQL.
    sql(f"DELETE FROM platform_file WHERE id='{seed}' AND owner_id='{owner}'")
    after_seed_cleanup = usage(actor)
    assert after_seed_cleanup["chargedBytes"] == 1
    assert after_seed_cleanup["fileCount"] == 1
    assert after_seed_cleanup["pendingDeletingCount"] == 1
    assert after_seed_cleanup["pendingDeletingBytes"] == 1

    # Synthetic READY zero-byte rows model completed empty files for the file-count bound.
    empty_digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    sql(
        f"""
        INSERT INTO platform_file(
            id,owner_id,storage_backend,original_name,state,size_bytes,reserved_bytes,sha256,version)
        SELECT gen_random_uuid(),'{owner}','postgres','QA zero-byte slot fixture',
               'READY',0,0,'{empty_digest}',1
          FROM generate_series(1,{slots - 1})
        """
    )
    full = usage(actor)
    assert full["chargedBytes"] == 1 and full["fileCount"] == slots
    assert full["pendingDeletingCount"] == 1 and full["pendingDeletingBytes"] == 1
    assert page(actor, "/account/files?pageSize=100")["total"] == slots - 1
    assert upload(actor, b"")[0] == 409

    # This removes only the zero-byte quota fixtures. The quota observation after this
    # cleanup still includes the real pending upload, so cleanup is not lifecycle proof.
    sql(
        f"DELETE FROM platform_file WHERE owner_id='{owner}' "
        "AND original_name='QA zero-byte slot fixture'"
    )
    final_usage = usage(actor)
    assert final_usage["chargedBytes"] == 1
    assert final_usage["fileCount"] == 1
    assert final_usage["pendingDeletingCount"] == 1
    assert final_usage["pendingDeletingBytes"] == 1
    assert sql(
        f"SELECT string_agg(action,',' ORDER BY action) FROM platform_audit WHERE target_id='{file_id}'"
    ) == "file.purgeRequested,file.trash,file.upload"
    print(
        "PASS: usage isolation, exact byte reservations, concurrent last-byte admission, "
        "PostgreSQL pending deletion stays charged and hidden, and zero-byte file-count limit"
    )
finally:
    # Teardown is restricted to this script's synthetic UUID-scoped account and is not
    # used as evidence that product cleanup released physical quota.
    sql(
        f"""
        DELETE FROM platform_file_blob_chunk
         WHERE file_id IN (SELECT id FROM platform_file WHERE owner_id='{owner}');
        DELETE FROM platform_file WHERE owner_id='{owner}';
        DELETE FROM platform_audit WHERE actor='{name}';
        DELETE FROM spring_session WHERE principal_name='{name}';
        DELETE FROM platform_user WHERE id='{owner}'
        """
    )
