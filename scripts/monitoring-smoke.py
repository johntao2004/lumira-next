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
    snapshot = json.loads(body)
    assert set(snapshot) == {"summary", "machine", "database", "flow"}
    summary = snapshot["summary"]
    expected = {"totalUsers", "activeUsers", "activeSessions", "enabledModules", "totalModules", "pendingOutbox", "processingOutbox", "failedOutbox", "deadLetterOutbox", "eventsCreatedLast5m", "eventsDeliveredLast5m", "retryPendingOutbox", "overdueOutbox", "pendingMail", "failedMail", "pendingSms", "failedSms", "unreadNotifications", "auditEvents24h", "measuredAt"}
    assert set(summary) == expected
    assert summary["totalUsers"] >= 1 and summary["activeUsers"] >= 1 and summary["activeSessions"] >= 1
    assert all(isinstance(summary[key], int) and summary[key] >= 0 for key in expected - {"measuredAt"})
    flow = snapshot["flow"]
    assert set(flow) == {"status", "producerTypes", "consumerTypes", "connectedTypes", "unmatchedConsumerTypes", "missingConsumers", "createdLast5m", "deliveredLast5m", "retryPending", "overdue", "recentEvents"}
    assert flow["status"] in {"UNCONNECTED", "IDLE", "HEALTHY", "PROCESSING", "ATTENTION"}
    assert flow["producerTypes"] == 2 and flow["consumerTypes"] == 2 and flow["connectedTypes"] == 2
    assert flow["unmatchedConsumerTypes"] == 0 and flow["missingConsumers"] == []
    assert flow["createdLast5m"] == summary["eventsCreatedLast5m"]
    assert flow["deliveredLast5m"] == summary["eventsDeliveredLast5m"]

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
