"""Verify the passkey API boundary without requiring a hardware authenticator."""

import json

from smoke_support import call, client, config, login, write


connection = client()
assert login(connection, "admin", config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200

status, body = call(connection, "/platform/settings/passkey")
assert status == 200, (status, body)
snapshot = json.loads(body)
assert snapshot["data"]["enabled"] is False
assert snapshot["data"]["passwordlessEnabled"] is False
assert snapshot["data"]["selfBindingEnabled"] is False

status, body = write(connection, "/auth/passkeys/authentication/options", "POST", {})
assert status == 403, (status, body)
assert json.loads(body).get("detail") == "passkeyFeatureDisabled", body

status, body = call(connection, "/auth/passkeys", "GET")
assert status == 200, (status, body)
credentials = json.loads(body)
assert isinstance(credentials, list)
for credential in credentials:
    assert set(credential) <= {"id", "label", "createdAt", "lastUsedAt"}

status, body = write(
    connection,
    "/auth/passkeys/registration/options",
    "POST",
    {"currentPassword": config["LUMIRA_BOOTSTRAP_PASSWORD"]},
)
assert status == 403, (status, body)
assert json.loads(body).get("detail") == "passkeyFeatureDisabled", body
print("PASS: current passkey settings, JSON DTO feature gates, and safe credential projection")
