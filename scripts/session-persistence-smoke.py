"""Verify the configured Spring Session write window and immediate revocation in bound QA."""
import base64
import json
import math
import re
import time
from smoke_support import client, call, login, write, sql, config, cookie_jar, SESSION_COOKIE_NAME

assert int(config.get("LUMIRA_SESSION_TOUCH_WINDOW_MS", 0)) == 1000, "Explicit 1000ms QA touch window required"
actor = client()
assert login(actor, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200
cookie = next(item for item in cookie_jar(actor) if item.name == SESSION_COOKIE_NAME)
identifier = base64.b64decode(cookie.value, validate=True).decode("ascii")
assert re.fullmatch(r"[a-f0-9-]{36}", identifier)


def row_version():
    return sql(f"SELECT xmin::text FROM spring_session WHERE session_id='{identifier}'")


def attribute_versions():
    # Inspect tuple versions, never serialized attribute values or credentials.
    raw = sql(f"SELECT coalesce(jsonb_object_agg(a.attribute_name,a.xmin::text),'{{}}'::jsonb)::text FROM spring_session_attributes a JOIN spring_session s ON s.primary_id=a.session_primary_id WHERE s.session_id='{identifier}'")
    return json.loads(raw)


try:
    previous = row_version()
    previous_attributes = attribute_versions()
    assert previous
    changed = 0
    attribute_updates = 0
    started = time.monotonic()
    checks = 20
    for _ in range(checks):
        status, body = call(actor, "/auth/me")
        assert status == 200 and json.loads(body)["username"] == "admin"
        current = row_version()
        assert current
        changed += current != previous
        previous = current
        current_attributes = attribute_versions()
        attribute_updates += sum(previous_attributes.get(name) != current_attributes.get(name)
                                 for name in previous_attributes.keys() | current_attributes.keys())
        previous_attributes = current_attributes
    elapsed = time.monotonic() - started
    assert changed <= math.ceil(elapsed) + 1, (changed, elapsed)
    if elapsed < checks * .8:
        assert changed < checks, "Repository customizer was not applied to the running backend"
    assert write(actor, "/auth/logout", "POST", {})[0] in (200, 204)
    assert not row_version(), "Logout retained the session row"
    cookie_jar(actor).set_cookie(cookie)
    assert call(actor, "/auth/me")[0] in (401, 403), "A revoked cookie was accepted"
    print(json.dumps({"stage": "session-window-acceptance", "requests": checks,
                      "metadataUpdates": changed, "attributeUpdates": attribute_updates,
                      "seconds": round(elapsed, 3),
                      "replayedRevokedCookieDenied": True}))
finally:
    cookie_jar(actor).clear()
