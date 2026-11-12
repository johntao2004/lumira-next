"""Preserve-only lifecycle operations for explicitly bound disposable QA fixtures."""
import json
import time
from smoke_support import call, write, wait_for_readiness


def preserve_plugin(connection, identifier, action):
    base = "/platform/modules/plugins"
    status, body = write(connection, f"{base}/{identifier}/{action}-plan", "POST", {})
    assert status == 200, (identifier, action, status, body.decode())
    plan = json.loads(body)
    assert not plan["blockers"], plan
    status, body = write(connection, f"{base}/{identifier}/{action}", "POST", {
        "revision": plan["revision"], "dataMode": "PRESERVE"})
    assert status == 200, (identifier, action, status, body.decode())
    operation = json.loads(body)
    deadline = time.monotonic() + 45
    while operation["status"] not in {"SUCCEEDED", "FAILED"} and time.monotonic() < deadline:
        time.sleep(.2)
        status, body = call(connection, base + "/operations/" + operation["id"])
        assert status == 200, status
        operation = json.loads(body)
    assert operation["status"] == "SUCCEEDED", operation
    wait_for_readiness()
