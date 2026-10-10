"""Local, real PostgreSQL-backed authentication smoke. Never prints credentials."""

import json
import urllib.parse
import uuid

from smoke_support import call, client, config, cookie_jar, login, sql, token


connection = client()
assert call(connection, "/auth/me")[0] == 401
assert call(connection, "/platform/modules")[0] == 401
assert (
    call(
        connection,
        "/auth/login",
        "POST",
        urllib.parse.urlencode({"username": "admin", "password": "invalid"}).encode(),
    )[0]
    == 403
)

invalid_login = login(connection, "admin", "invalid")
assert invalid_login == 401
valid_login = login(connection, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"])
assert valid_login == 200

status, body = call(connection, "/auth/me")
assert status == 200 and json.loads(body)["username"] == "admin"
status, body = call(connection, "/platform/modules")
assert status == 200
module_ids = {module["id"] for module in json.loads(body)}
assert {
    "identity",
    "platform",
    "communication",
    "directory",
    "dictionaries",
    "files",
    "settings",
    "events",
    "navigation",
    "notifications",
    "monitoring",
    "audit-console",
    "feedback",
    "sensitive-words",
    "localization",
} <= module_ids
status, body = call(connection, "/platform/users")
assert status == 200 and all(
    "password" not in key for row in json.loads(body)["items"] for key in row
)

# Mutate only the independent scaffold bootstrap account, restore even after failures.
try:
    sql(
        "DELETE FROM platform_authority WHERE user_id=(SELECT id FROM platform_user WHERE username='admin') AND authority='platform:users:read'"
    )
    assert call(connection, "/platform/users")[0] == 403, "Revoked permission still allowed"
finally:
    sql(
        "INSERT INTO platform_authority(user_id,authority) SELECT id,'platform:users:read' FROM platform_user WHERE username='admin' ON CONFLICT DO NOTHING"
    )
assert call(connection, "/platform/users")[0] == 200

# Isolated, non-login fixtures prove pagination beyond the former 100-row cap.
prefix = "qa_" + uuid.uuid4().hex + "_"
try:
    sql(
        "INSERT INTO platform_user(id,username,display_name,password_hash,enabled) SELECT gen_random_uuid(), '"
        + prefix
        + "' || lpad(n::text,3,'0'), 'Pagination QA', 'disabled-test-fixture', false FROM generate_series(1,105) n"
    )
    status, body = call(
        connection,
        "/platform/users?"
        + urllib.parse.urlencode({"page": 1, "pageSize": 100, "search": prefix}),
    )
    first = json.loads(body)
    assert status == 200 and first["total"] == 105 and len(first["items"]) == 100
    status, body = call(
        connection,
        "/platform/users?"
        + urllib.parse.urlencode({"page": 2, "pageSize": 100, "search": prefix}),
    )
    second = json.loads(body)
    assert status == 200 and second["total"] == 105 and len(second["items"]) == 5
    assert not ({row["id"] for row in first["items"]} & {row["id"] for row in second["items"]})
    assert call(connection, "/platform/users?page=0")[0] == 400
    assert call(connection, "/platform/users?pageSize=101")[0] == 400
    status, body = call(connection, "/platform/users?search=%25")
    assert status == 200 and json.loads(body)["total"] == 0, "Percent must be literal, not a wildcard"
finally:
    sql(
        "DELETE FROM platform_user WHERE left(username,"
        + str(len(prefix))
        + ")='"
        + prefix
        + "'"
    )
print("PASS: 105-user pagination, nonoverlap, search, bounded parameters, fixture cleanup")

old_cookie = "; ".join(
    f"{cookie.name}={cookie.value}" for cookie in cookie_jar(connection)
)
assert call(connection, "/auth/logout", "POST", b"", token(connection))[0] == 204
assert call(connection, "/auth/me", headers={"Cookie": old_cookie})[0] == 401
print(
    "PASS: anonymous denial, CSRF, invalid login, login, current user, module registry, safe user fields, permission revocation/restoration, logout cookie replay rejection"
)
