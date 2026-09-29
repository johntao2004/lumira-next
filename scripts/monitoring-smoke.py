"""Verify the global monitoring snapshot is permission protected and bounded to counters."""
from smoke_support import *
import json, uuid

username = "qa_monitoring_" + uuid.uuid4().hex
password = config["LUMIRA_BOOTSTRAP_PASSWORD"]
admin = client()
created = None
try:
    assert login(admin, "admin", password) == 200
    status, body = call(admin, "/platform/monitoring/summary")
    assert status == 200, (status, body)
    summary = json.loads(body)
    expected = {"totalUsers", "activeUsers", "activeSessions", "enabledModules", "totalModules", "pendingOutbox", "failedOutbox", "deadLetterOutbox", "pendingMail", "failedMail", "unreadNotifications", "auditEvents24h", "measuredAt"}
    assert set(summary) == expected
    assert summary["totalUsers"] >= 1 and summary["activeUsers"] >= 1 and summary["activeSessions"] >= 1
    assert all(isinstance(summary[key], int) and summary[key] >= 0 for key in expected - {"measuredAt"})

    status, body = write(admin, "/platform/users", "POST", {"username": username, "displayName": "Monitoring QA", "password": password})
    assert status == 201, (status, body)
    created = json.loads(body)
    denied = client()
    assert login(denied, username, password) == 200
    assert call(denied, "/platform/monitoring/summary")[0] == 403
    print("PASS: monitoring aggregate projection, non-negative counters, global audit permission and ordinary-user denial")
finally:
    if created:
        sql("DELETE FROM platform_audit WHERE target_id='" + created["id"] + "'; DELETE FROM spring_session WHERE principal_name='" + username + "'; DELETE FROM platform_user WHERE id='" + created["id"] + "'")
