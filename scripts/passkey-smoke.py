"""Verify the passkey API boundary without requiring a hardware authenticator."""
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

config = json.loads((Path(__file__).resolve().parents[1] / ".runtime/local.json").read_text())
base = "http://127.0.0.1:8010/api"
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(
    urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar)
)


def call(path, method="GET", data=None, headers=None):
    request = urllib.request.Request(base + path, data=data, method=method, headers=headers or {})
    try:
        with client.open(request, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def csrf_headers(content_type=None):
    status, body = call("/auth/csrf")
    assert status == 200, (status, body)
    token = json.loads(body)
    headers = {token["headerName"]: token["token"]}
    if content_type:
        headers["Content-Type"] = content_type
    return headers


headers = csrf_headers("application/x-www-form-urlencoded")
login = urllib.parse.urlencode(
    {"username": "admin", "password": config["LUMIRA_BOOTSTRAP_PASSWORD"]}
).encode()
assert call("/auth/login", "POST", login, headers)[0] == 200

status, body = call("/platform/settings/passkey")
assert status == 200, (status, body)
snapshot = json.loads(body)
assert snapshot["version"] == 0 and snapshot["data"]["enabled"] is False

status, body = call(
    "/auth/passkeys/authentication/options",
    "POST",
    b"",
    csrf_headers(),
)
assert status == 403, (status, body)

status, body = call("/auth/passkeys", "GET")
assert status == 200, (status, body)
credentials = json.loads(body)
assert isinstance(credentials, list)
for credential in credentials:
    assert set(credential) <= {"id", "label", "createdAt", "lastUsedAt"}

status, body = call(
    "/auth/passkeys/registration/options",
    "POST",
    json.dumps({"currentPassword": config["LUMIRA_BOOTSTRAP_PASSWORD"]}).encode(),
    csrf_headers("application/json"),
)
assert status == 403, (status, body)
print("PASS: passkey settings, CSRF boundary, default feature gate, and safe credential projection")
