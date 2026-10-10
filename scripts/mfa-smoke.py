"""Exercise the MFA plugin and login boundary using disposable qa-mfa-* users.

Run after the MFA plugin has been built, installed, and the local 8010/8090
services are healthy. The normal run removes every account it creates. The
browser fixture commands are separate and intentionally keep one temporary
account until --cleanup-browser is requested.
"""
import argparse
import base64
from datetime import datetime
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import tempfile
import struct
import subprocess
import time
import urllib.parse
import uuid

from smoke_support import client, call, config, login, sql, token, write


ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".runtime/mfa"
BROWSER_FIXTURE = WORK / "browser-fixture.json"
BROWSER_ROLE_FIXTURE = WORK / "browser-role-fixture.json"
USER_PASSWORD = "Lumira-MFA-QA-Only-2026!x9"
ADMIN = "admin"
MFA_ACCOUNT = "/mfa/account"
MFA_MANAGE = "/mfa/manage"
SECOND_FACTOR = "/auth/second-factor"
PASSKEY_SETTINGS = "/platform/settings/passkey"
PASSKEY_OPTIONS = "/auth/passkeys/authentication/options"
PASSKEY_COMPLETE = "/auth/passkeys/authentication/complete"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def parsed(status, body, expected, context):
    require(status == expected, f"{context}: expected HTTP {expected}, received {status}")
    if not body:
        return {}
    try:
        return json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {}


def json_get(c, path, expected=200):
    status, body = call(c, path)
    return parsed(status, body, expected, path)


def json_write(c, path, method, payload, expected=200):
    status, body = write(c, path, method, payload)
    return parsed(status, body, expected, f"{method} {path}")


def current_time_step(now=None):
    return int((time.time() if now is None else now) // 30)


def totp_code(secret, step):
    key = base64.b32decode(secret.upper() + "=" * ((8 - len(secret) % 8) % 8))
    digest = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    value = struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF
    return f"{value % 1_000_000:06d}"


def wrong_code(secret):
    step = current_time_step()
    valid = {totp_code(secret, value) for value in range(max(0, step - 1), step + 2)}
    for candidate in range(1_000_000):
        code = f"{candidate:06d}"
        if code not in valid:
            return code
    raise AssertionError("Could not generate a nonmatching TOTP fixture")


def fresh_code(secret, last_step=-1, timeout=35):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        step = current_time_step()
        if step > last_step:
            return totp_code(secret, step), step
        time.sleep(0.25)
    raise AssertionError("TOTP time step did not advance")


def b64url(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def cbor_head(major, value):
    if value < 24:
        return bytes([(major << 5) | value])
    if value < 256:
        return bytes([(major << 5) | 24, value])
    if value < 65536:
        return bytes([(major << 5) | 25]) + value.to_bytes(2, "big")
    raise ValueError("Synthetic COSE value is too large")


def cbor_value(value):
    if isinstance(value, int):
        return cbor_head(0, value) if value >= 0 else cbor_head(1, -1 - value)
    if isinstance(value, bytes):
        return cbor_head(2, len(value)) + value
    raise TypeError("Unsupported synthetic COSE value")


def cbor_cose_p256(x, y):
    entries = ((1, 2), (3, -7), (-1, 1), (-2, x), (-3, y))
    encoded = bytearray(cbor_head(5, len(entries)))
    for key, value in entries:
        encoded.extend(cbor_value(key))
        encoded.extend(cbor_value(value))
    return bytes(encoded)


def temporary_passkey_policy(admin):
    original = page(admin, PASSKEY_SETTINGS)
    data = dict(original["data"])
    changed = [field for field in ("enabled", "passwordlessEnabled") if data.get(field) is not True]
    if not changed:
        return original, original, []
    temporary = dict(data)
    for field in changed:
        temporary[field] = True
    updated = json_write(
        admin,
        PASSKEY_SETTINGS,
        "PUT",
        {
            "version": original["version"],
            "data": temporary,
            "changeReason": "Temporary synthetic passkey MFA smoke",
        },
    )
    return original, updated, changed


def restore_passkey_policy(admin, original, temporary, changed):
    if not changed or temporary["version"] == original["version"]:
        return
    # Restore only fields still holding this fixture's value; preserve unrelated
    # changes made by another administrator and use optimistic version checks.
    for _ in range(3):
        current = page(admin, PASSKEY_SETTINGS)
        data = dict(current["data"])
        owned = [field for field in changed if data.get(field) is temporary["data"].get(field)]
        concurrent = [field for field in changed if field not in owned]
        if not owned:
            raise AssertionError("Passkey smoke settings changed concurrently; left those fields untouched")
        for field in owned:
            data[field] = original["data"].get(field)
        status, body = write(
            admin,
            PASSKEY_SETTINGS,
            "PUT",
            {
                "version": current["version"],
                "data": data,
                "changeReason": "Restore synthetic passkey MFA smoke fields",
            },
        )
        if status == 200:
            if concurrent:
                raise AssertionError(
                    "Concurrent passkey policy fields were left untouched: " + ", ".join(concurrent)
                )
            return
        if status != 409:
            parsed(status, body, 200, "restore temporary passkey smoke settings")
    raise AssertionError("Could not restore temporary passkey smoke settings after concurrent updates")


def synthetic_passkey_assertion(private_key, challenge, rp_id):
    client_data = json.dumps(
        {
            "type": "webauthn.get",
            "challenge": challenge,
            "origin": "http://127.0.0.1:8010",
        },
        separators=(",", ":"),
    ).encode()
    authenticator_data = hashlib.sha256(rp_id.encode()).digest() + b"\x05\x00\x00\x00\x00"
    signed = authenticator_data + hashlib.sha256(client_data).digest()
    try:
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(private_key)],
            input=signed,
            capture_output=True,
            check=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        raise AssertionError("Could not sign the synthetic ES256 WebAuthn assertion") from None
    return {
        "clientDataJSON": b64url(client_data),
        "authenticatorData": b64url(authenticator_data),
        "signature": b64url(signature),
    }


def passkey_mfa_login(admin, username, user_id, secret):
    WORK.mkdir(parents=True, exist_ok=True)
    original, temporary, changed = temporary_passkey_policy(admin)
    credential_id = os.urandom(32)
    user_handle = os.urandom(32)
    credential_inserted = False
    passkey_challenge_id = None
    passkey_client = None
    try:
        with tempfile.TemporaryDirectory(prefix="synthetic-passkey-", dir=WORK) as directory:
            private_key = Path(directory) / "qa-private.pem"
            public_key = Path(directory) / "qa-public.der"
            try:
                subprocess.run(
                    ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(private_key)],
                    capture_output=True,
                    check=True,
                )
                private_key.chmod(0o600)
                subprocess.run(
                    ["openssl", "pkey", "-in", str(private_key), "-pubout", "-outform", "DER", "-out", str(public_key)],
                    capture_output=True,
                    check=True,
                )
            except (OSError, subprocess.CalledProcessError):
                raise AssertionError("Could not create the synthetic ES256 WebAuthn key") from None

            point = public_key.read_bytes()[-65:]
            require(len(point) == 65 and point[0] == 4, "Synthetic ES256 public key was malformed")
            cose_key = cbor_cose_p256(point[1:33], point[33:])
            credential_sql = (
                "INSERT INTO platform_passkey_credential "
                "(id,user_id,user_handle,credential_id,public_key_cose,sign_count,transports,"
                "backup_eligible,backup_state,label) VALUES ('"
                + str(uuid.uuid4())
                + "','"
                + user_id
                + "',decode('"
                + base64.b64encode(user_handle).decode()
                + "','base64'),decode('"
                + base64.b64encode(credential_id).decode()
                + "','base64'),decode('"
                + base64.b64encode(cose_key).decode()
                + "','base64'),0,'internal',false,false,'MFA QA synthetic ES256')"
            )
            sql(credential_sql)
            credential_inserted = True

            available = False
            for _ in range(30):
                status, body = call(client(), "/auth/passkeys/authentication/availability")
                if status == 200 and json.loads(body).get("enabled") is True:
                    available = True
                    break
                time.sleep(0.1)
            require(available, "Temporary passkey authentication policy did not become available")

            passkey_client = client()
            status, body = call(passkey_client, PASSKEY_OPTIONS, "POST", b"", token(passkey_client))
            options = parsed(status, body, 200, "passkey authentication options")
            passkey_challenge_id = options["challengeId"]
            webauthn_challenge = options["publicKey"]["challenge"]
            assertion = synthetic_passkey_assertion(
                private_key, webauthn_challenge, temporary["data"]["rpId"]
            )
            raw_id = b64url(credential_id)
            request = {
                "challengeId": options["challengeId"],
                "id": raw_id,
                "rawId": raw_id,
                "type": "public-key",
                "authenticatorAttachment": "platform",
                "response": {
                    **assertion,
                    "userHandle": b64url(user_handle),
                },
            }
            status, body = write(
                passkey_client,
                PASSKEY_COMPLETE,
                "POST",
                request,
            )
            response = parsed(status, body, 200, "synthetic WebAuthn first factor")
            require(response.get("authenticated") is False, "Passkey first factor authenticated without MFA")
            require(response.get("secondFactorRequired") is True, "Passkey login bypassed the MFA requirement")
            challenge_id = response.get("challengeId")
            require(challenge_id, "Passkey first factor did not create an MFA challenge")
            require(call(passkey_client, "/auth/me")[0] == 401, "Passkey-only session accessed protected account state")
            require(
                call(passkey_client, MFA_ACCOUNT)[0] in (401, 403),
                "Passkey-only session accessed the MFA self-service API",
            )
            last_step = int(
                sql("SELECT last_step FROM plugin_mfa_authenticator WHERE user_id='" + user_id + "'")
            )
            code, _ = fresh_code(secret, last_step)
            complete_challenge(passkey_client, challenge_id, code)
            require(call(passkey_client, "/auth/me")[0] == 200, "MFA did not complete the synthetic passkey login")
            print("PASS: synthetic ES256 WebAuthn first factor created a separate MFA challenge; no physical authenticator was used")
    finally:
        if passkey_client is not None:
            try:
                write(passkey_client, "/auth/logout", "POST", {})
            except Exception:
                pass
        if credential_inserted:
            sql(
                "DELETE FROM platform_passkey_credential WHERE user_id='"
                + user_id
                + "' AND credential_id=decode('"
                + base64.b64encode(credential_id).decode()
                + "','base64')"
            )
        if passkey_challenge_id:
            sql("DELETE FROM platform_passkey_challenge WHERE id='" + passkey_challenge_id + "'")
        restore_passkey_policy(admin, original, temporary, changed)


def run_passkey_smoke():
    admin = client()
    require(login(admin, ADMIN, config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200, "Admin login failed")
    plugin = plugin_view(admin)
    require(
        plugin and plugin["installed"] and plugin["enabled"] and plugin["status"] == "RUNNING",
        "MFA plugin must be installed, enabled, and running in its isolated container",
    )
    username = create_name("primary")
    user = None
    account = None
    try:
        user = create_user(admin, username, "Synthetic Passkey MFA QA")
        account = client()
        require(login(account, username, USER_PASSWORD) == 200, "Synthetic passkey QA login failed")
        setup = mfa_setup(account, label="Synthetic passkey MFA QA")
        recovery = mfa_confirm(account, USER_PASSWORD, setup["secret"])
        require(len(recovery) == 10, "Synthetic passkey MFA setup did not produce recovery codes")
        passkey_mfa_login(admin, username, user["id"], setup["secret"])
    finally:
        if account is not None:
            try:
                write(account, "/auth/logout", "POST", {})
            except Exception:
                pass
        if user:
            delete_user(user["id"], username)
        try:
            write(admin, "/auth/logout", "POST", {})
        except Exception:
            pass
    print("PASS: standalone synthetic passkey MFA acceptance completed with only a qa-mfa-* account")


def login_response(c, username, password):
    headers = {
        **token(c),
        "Content-Type": "application/x-www-form-urlencoded",
    }
    data = urllib.parse.urlencode({"username": username, "password": password}).encode()
    return call(c, "/auth/login", "POST", data, headers)


def create_user(admin, username, display_name):
    result = json_write(
        admin,
        "/platform/users",
        "POST",
        {"username": username, "displayName": display_name, "password": USER_PASSWORD},
        201,
    )
    return result


def create_name(role):
    return f"qa-mfa-{role}-{uuid.uuid4().hex[:16]}"


def mfa_setup(c, password=USER_PASSWORD, label="QA authenticator"):
    return json_write(
        c,
        MFA_ACCOUNT + "/setup",
        "POST",
        {"currentPassword": password, "label": label},
    )


def mfa_confirm(c, password, secret):
    code, _ = fresh_code(secret)
    status, body = write(
        c,
        MFA_ACCOUNT + "/confirm",
        "POST",
        {"currentPassword": password, "code": code},
    )
    result = parsed(status, body, 200, "confirm MFA setup")
    return result["recoveryCodes"]


def self_enroll(username, password=USER_PASSWORD, label="QA authenticator"):
    c = client()
    require(login(c, username, password) == 200, f"Could not sign in QA account {username}")
    setup = mfa_setup(c, password, label)
    recovery = mfa_confirm(c, password, setup["secret"])
    return c, setup["secret"], recovery


def create_browser_fixture():
    WORK.mkdir(parents=True, exist_ok=True)
    if BROWSER_FIXTURE.exists():
        raise AssertionError(f"Browser fixture already exists: {BROWSER_FIXTURE}")
    admin = client()
    require(login(admin, ADMIN, config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200, "Admin login failed")
    username = create_name("browser")
    user = None
    temporary = None
    try:
        user = create_user(admin, username, "MFA Browser QA")
        browser_session, _, recovery_codes = self_enroll(username, label="Browser QA authenticator")
        write(browser_session, "/auth/logout", "POST", {})
        fixture = {
            "username": username,
            "password": USER_PASSWORD,
            "userId": user["id"],
            "recoveryCodes": recovery_codes,
        }
        temporary = WORK / f".browser-fixture-{uuid.uuid4().hex}.tmp"
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as output:
            output.write(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(BROWSER_FIXTURE)
    except BaseException:
        if temporary:
            temporary.unlink(missing_ok=True)
        if user:
            delete_user(user["id"], username)
        raise
    finally:
        write(admin, "/auth/logout", "POST", {})
    print("PASS: browser-only MFA fixture created; login and one-time recovery codes are in .runtime/mfa/browser-fixture.json (mode 0600)")


def browser_fixture_identity():
    if not BROWSER_FIXTURE.exists():
        raise AssertionError("Prepare the browser MFA fixture before granting management access")
    fixture = json.loads(BROWSER_FIXTURE.read_text())
    username = fixture.get("username", "")
    user_id = fixture.get("userId", "")
    if not re.fullmatch(r"qa-mfa-browser-[0-9a-f]{16}", username):
        raise AssertionError("Refusing an unrecognized browser fixture account")
    try:
        if str(uuid.UUID(user_id)) != user_id:
            raise ValueError(user_id)
    except (TypeError, ValueError, AttributeError):
        raise AssertionError("Refusing an invalid browser fixture user ID") from None
    return fixture


def grant_browser_management():
    WORK.mkdir(parents=True, exist_ok=True)
    fixture = browser_fixture_identity()
    if BROWSER_ROLE_FIXTURE.exists():
        raise AssertionError("Browser fixture already has a temporary management role")

    username = fixture["username"]
    user_id = fixture["userId"]
    role_id = str(uuid.uuid4())
    role_code = "qa_mfa_browser_manage_" + uuid.uuid4().hex
    statement = (
        "BEGIN;"
        "INSERT INTO platform_role(id,code,name,enabled,data_scope) VALUES('") + role_id + (
        "','") + role_code + (
        "','MFA browser QA manager',true,'ALL');"
        "INSERT INTO platform_role_authority(role_id,authority) VALUES('") + role_id + (
        "','platform:mfa:manage');"
        "INSERT INTO platform_user_role(user_id,role_id) "
        "SELECT id,'") + role_id + (
        "' FROM platform_user WHERE id='") + user_id + (
        "' AND username='") + username + (
        "' AND enabled AND deleted_at IS NULL;"
        "COMMIT;"
    )
    sql(statement)
    attached = sql(
        "SELECT count(*) FROM platform_user_role ur "
        "JOIN platform_role_authority ra ON ra.role_id=ur.role_id "
        "JOIN platform_role r ON r.id=ur.role_id AND r.enabled "
        "WHERE ur.user_id='" + user_id + "' AND ur.role_id='" + role_id + "' "
        "AND ra.authority='platform:mfa:manage' AND r.code='" + role_code + "'"
    )
    if attached != "1":
        sql(
            "DELETE FROM platform_user_role WHERE role_id='" + role_id + "';"
            "DELETE FROM platform_role_authority WHERE role_id='" + role_id + "';"
            "DELETE FROM platform_role WHERE id='" + role_id + "' AND code='" + role_code + "'"
        )
        raise AssertionError("Could not verify the browser-only MFA management role")

    metadata = {"roleId": role_id, "roleCode": role_code, "userId": user_id, "username": username}
    temporary = WORK / f".browser-role-fixture-{uuid.uuid4().hex}.tmp"
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w") as output:
            output.write(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(BROWSER_ROLE_FIXTURE)
    except BaseException:
        temporary.unlink(missing_ok=True)
        sql(
            "DELETE FROM platform_user_role WHERE role_id='" + role_id + "';"
            "DELETE FROM platform_role_authority WHERE role_id='" + role_id + "';"
            "DELETE FROM platform_role WHERE id='" + role_id + "' AND code='" + role_code + "'"
        )
        raise
    print(f"PASS: granted temporary platform:mfa:manage role to {username} (role ID {role_id}); no reset endpoint called")


def delete_user(user_id, username):
    if not re.fullmatch(r"qa-mfa-(primary|target|observer|manager|browser)-[0-9a-f]{16}", username):
        raise AssertionError("Refusing cleanup outside the qa-mfa-* fixture namespace")
    try:
        if str(uuid.UUID(user_id)) != user_id:
            raise ValueError(user_id)
    except (TypeError, ValueError, AttributeError):
        raise AssertionError("Refusing cleanup with an invalid fixture user ID") from None
    sql(
        "DELETE FROM platform_audit WHERE target_id='"
        + user_id
        + "' OR actor='"
        + username
        + "';"
        "DELETE FROM plugin_mfa_reset_audit WHERE target_id='"
        + user_id
        + "';"
        "DELETE FROM spring_session WHERE principal_name='"
        + username
        + "';"
        "DELETE FROM platform_login_challenge WHERE user_id='"
        + user_id
        + "';"
        "DELETE FROM platform_second_factor_requirement WHERE user_id='"
        + user_id
        + "';"
        "DELETE FROM platform_user WHERE id='"
        + user_id
        + "' AND username='"
        + username
        + "'"
    )


def cleanup_browser_fixture():
    if BROWSER_ROLE_FIXTURE.exists():
        role_fixture = json.loads(BROWSER_ROLE_FIXTURE.read_text())
        role_id = role_fixture.get("roleId", "")
        role_code = role_fixture.get("roleCode", "")
        user_id = role_fixture.get("userId", "")
        username = role_fixture.get("username", "")
        try:
            if str(uuid.UUID(role_id)) != role_id or str(uuid.UUID(user_id)) != user_id:
                raise ValueError("invalid fixture ID")
        except (TypeError, ValueError, AttributeError):
            raise AssertionError("Refusing to remove an invalid browser role fixture") from None
        if not re.fullmatch(r"qa_mfa_browser_manage_[0-9a-f]{32}", role_code):
            raise AssertionError("Refusing to remove an unrecognized browser role")
        if not re.fullmatch(r"qa-mfa-browser-[0-9a-f]{16}", username):
            raise AssertionError("Refusing to remove a role outside the browser fixture namespace")
        if BROWSER_FIXTURE.exists():
            browser_fixture = browser_fixture_identity()
            if browser_fixture["username"] != username or browser_fixture["userId"] != user_id:
                raise AssertionError("Browser account and temporary role fixtures do not match")
        sql(
            "BEGIN;"
            "DELETE FROM platform_user_role WHERE role_id='" + role_id + "' AND user_id='" + user_id + "';"
            "DELETE FROM platform_role_authority WHERE role_id='" + role_id + "' AND authority='platform:mfa:manage';"
            "DELETE FROM platform_role WHERE id='" + role_id + "' AND code='" + role_code + "';"
            "COMMIT;"
        )
        BROWSER_ROLE_FIXTURE.unlink()
        print(f"PASS: temporary MFA management role {role_id} removed")
    if not BROWSER_FIXTURE.exists():
        print("PASS: no browser MFA account to clean")
        return
    fixture = browser_fixture_identity()
    delete_user(fixture["userId"], fixture["username"])
    BROWSER_FIXTURE.unlink()
    print("PASS: browser-only MFA fixture and its temporary account removed")


def page(c, path, expected=200):
    status, body = call(c, path)
    return parsed(status, body, expected, path)


def challenge(c, username):
    status, body = login_response(c, username, USER_PASSWORD)
    result = parsed(status, body, 202, "password login with MFA")
    require(result.get("authenticated") is False, "MFA challenge unexpectedly authenticated")
    require(result.get("secondFactorRequired") is True, "MFA challenge was not required")
    require(result.get("challengeId"), "MFA challenge ID is missing")
    return result


def complete_challenge(c, challenge_id, code, expected=200):
    return json_write(
        c,
        SECOND_FACTOR + "/confirm",
        "POST",
        {"challengeId": challenge_id, "code": code},
        expected,
    )


def cancel_pending(c):
    status, _ = write(c, SECOND_FACTOR + "/cancel", "POST", {})
    require(status == 204, "Could not cancel QA login challenge")


def account_lookup(admin, username):
    result = page(admin, "/platform/users?" + urllib.parse.urlencode({"search": username}))
    row = next((item for item in result["items"] if item["username"] == username), None)
    require(row is not None, f"QA account was not found: {username}")
    return row


def set_plugin_enabled(admin, enabled):
    modules = page(admin, "/platform/modules")
    module = next((item for item in modules if item["id"] == "mfa"), None)
    require(module is not None, "MFA module is not registered")
    if module["enabled"] == enabled:
        return
    json_write(
        admin,
        "/platform/modules/mfa/state",
        "PUT",
        {
            "enabled": enabled,
            "version": module["stateVersion"],
            "reason": "MFA HTTP smoke",
        },
    )


def plugin_view(admin):
    plugins = page(admin, "/platform/modules/plugins")
    return next((item for item in plugins if item["id"] == "mfa"), None)


def operate_plugin(admin, kind):
    plan = json_write(admin, f"/platform/modules/plugins/mfa/{kind}-plan", "POST", {})
    require(not plan.get("blockers"), f"MFA {kind} preflight blocked: {plan.get('blockers')}")
    operation = json_write(
        admin,
        f"/platform/modules/plugins/mfa/{kind}",
        "POST",
        {"revision": plan["revision"], "dataMode": "PRESERVE"},
    )
    for _ in range(300):
        operation = page(admin, "/platform/modules/plugins/operations/" + operation["id"])
        if operation["status"] in ("SUCCEEDED", "FAILED"):
            break
        time.sleep(0.1)
    require(operation["status"] == "SUCCEEDED", f"MFA {kind} failed: {operation.get('errorCode')}")


def assert_fail_closed(username):
    c = client()
    status, body = login_response(c, username, USER_PASSWORD)
    result = parsed(status, body, 503, "required MFA provider unavailable")
    require(result.get("detail") == "secondFactorUnavailable", "Missing MFA provider did not fail closed")
    require(call(c, "/auth/me")[0] == 401, "Password-only session escaped the unavailable MFA provider")


def process_ids():
    return subprocess.check_output(["node", "scripts/status.mjs"], cwd=ROOT, text=True).splitlines()[:2]


def run_smoke(skip_hotplug=False):
    admin = client()
    require(login(admin, ADMIN, config["LUMIRA_BOOTSTRAP_PASSWORD"]) == 200, "Admin login failed")
    initial_plugin = plugin_view(admin)
    require(
        initial_plugin
        and initial_plugin["installed"]
        and initial_plugin["enabled"]
        and initial_plugin["status"] == "RUNNING",
        "MFA plugin must be installed, enabled, and running in its isolated container",
    )

    names = {
        "primary": create_name("primary"),
        "target": create_name("target"),
        "observer": create_name("observer"),
        "manager": create_name("manager"),
    }
    users = {}
    sessions = []
    manager_role = None
    plugin_original_enabled = True
    plugin_original_installed = True
    try:
        for role, username in names.items():
            users[role] = create_user(admin, username, f"MFA {role.title()} QA")

        # An anonymous caller cannot read self-service state; writes still require CSRF.
        anonymous = client()
        require(call(anonymous, MFA_ACCOUNT)[0] in (401, 403), "Anonymous self-service access was not denied")
        no_csrf = call(
            anonymous,
            MFA_ACCOUNT + "/setup",
            "POST",
            json.dumps({"currentPassword": USER_PASSWORD, "label": "No CSRF"}).encode(),
            {"Content-Type": "application/json"},
        )
        require(no_csrf[0] == 403, "MFA setup accepted a request without CSRF")

        primary = client()
        sessions.append(primary)
        require(login(primary, names["primary"], USER_PASSWORD) == 200, "Primary QA login failed")
        wrong_password_status, wrong_password_body = write(
            primary,
            MFA_ACCOUNT + "/setup",
            "POST",
            {"currentPassword": "wrong-QA-password", "label": "QA authenticator"},
        )
        require(wrong_password_status == 400, "Wrong current password was accepted")
        wrong_password_detail = json.loads(wrong_password_body).get("detail") if wrong_password_body else None
        require(wrong_password_detail == "wrongCurrentPassword", "Wrong current password did not return the expected rejection")

        # First setup is expired in the QA row to verify server-side expiry without waiting ten minutes.
        expired_setup = mfa_setup(primary)
        sql(
            "UPDATE plugin_mfa_authenticator SET expires_at=now()-interval '1 second' WHERE user_id='"
            + users["primary"]["id"]
            + "'"
        )
        expiry = sql(
            "SELECT count(*) FROM plugin_mfa_authenticator WHERE user_id='"
            + users["primary"]["id"]
            + "' AND expires_at<=now()"
        )
        require(expiry == "1", "Could not isolate the pending MFA setup fixture")
        expired_code = totp_code(expired_setup["secret"], current_time_step())
        status, _ = write(
            primary,
            MFA_ACCOUNT + "/confirm",
            "POST",
            {"currentPassword": USER_PASSWORD, "code": expired_code},
        )
        require(status == 400, "Expired MFA setup was accepted")
        require(json_get(primary, MFA_ACCOUNT)["enabled"] is False, "Expired setup became active")

        setup = mfa_setup(primary)
        require(setup["secret"] and setup["uri"].startswith("otpauth://totp/"), "Setup secret or TOTP URI missing")
        expiry_epoch = datetime.fromisoformat(setup["expiresAt"].replace("Z", "+00:00")).timestamp()
        require(time.time() < expiry_epoch <= time.time() + 610, "Setup expiry did not match the ten-minute contract")
        setup_secret = setup["secret"]
        status, _ = write(
            primary,
            MFA_ACCOUNT + "/confirm",
            "POST",
            {"currentPassword": USER_PASSWORD, "code": wrong_code(setup_secret)},
        )
        require(status == 400, "Invalid TOTP code was accepted during setup")
        primary_recovery = mfa_confirm(primary, USER_PASSWORD, setup_secret)
        require(len(primary_recovery) == 10, "Enrollment did not return ten recovery codes")
        primary_status = json_get(primary, MFA_ACCOUNT)
        require(primary_status["enabled"] and primary_status["recoveryCodesRemaining"] == 10, "Enrollment state was not persisted")

        envelope = sql("SELECT secret_envelope FROM plugin_mfa_authenticator WHERE user_id='" + users["primary"]["id"] + "'")
        require(envelope.startswith("v1:") and setup_secret not in envelope, "TOTP secret was stored without encryption")
        recovery_digests = sql("SELECT string_agg(digest, ',') FROM plugin_mfa_recovery WHERE user_id='" + users["primary"]["id"] + "'")
        for recovery in primary_recovery:
            require(recovery.replace("-", "") not in recovery_digests, "Recovery code was stored in plaintext")
        print("PASS: anonymous/CSRF/password checks, expired setup, TOTP enrollment, encrypted secret, and one-time recovery-code storage")

        target, _, _ = self_enroll(names["target"], label="Reset target QA")
        sessions.append(target)
        require(json_get(target, MFA_ACCOUNT)["recoveryCodesRemaining"] == 10, "Reset target MFA was not persisted")

        # Ordinary signed-in users can manage their own factor but do not receive the global reset capability.
        observer = client()
        sessions.append(observer)
        require(login(observer, names["observer"], USER_PASSWORD) == 200, "Observer QA login failed")
        require(call(observer, MFA_MANAGE)[0] == 403, "MFA management was not denied by default")

        listing = page(admin, MFA_MANAGE + "?page=1&pageSize=1&search=qa-mfa-")
        following = page(admin, MFA_MANAGE + "?page=2&pageSize=1&search=qa-mfa-")
        require(listing["total"] >= 2 and len(listing["items"]) == len(following["items"]) == 1, "MFA management pagination failed")
        require(listing["items"][0]["id"] != following["items"][0]["id"], "MFA pages repeated an account")
        filtered = page(admin, MFA_MANAGE + "?" + urllib.parse.urlencode({"search": names["primary"], "page": 1, "pageSize": 10}))
        require(filtered["total"] == 1 and filtered["items"][0]["username"] == names["primary"], "MFA management search failed")
        require(not ({"secret", "secretEnvelope", "recoveryCodes", "recoveryDigest"} & set(filtered["items"][0])), "Management API exposed MFA secrets")
        print("PASS: MFA management pagination, search, safe projection, and default-deny authorization")

        # A disposable global MFA manager proves that management reset also requires the actor's factor.
        manager_role = str(uuid.uuid4())
        manager_role_code = "qa_mfa_manage_" + uuid.uuid4().hex
        sql(
            "INSERT INTO platform_role(id,code,name,enabled,data_scope) VALUES('"
            + manager_role
            + "','"
            + manager_role_code
            + "','MFA QA Manager',true,'ALL');"
            "INSERT INTO platform_role_authority(role_id,authority) VALUES('"
            + manager_role
            + "','platform:mfa:manage');"
            "INSERT INTO platform_user_role(user_id,role_id) VALUES('"
            + users["manager"]["id"]
            + "','"
            + manager_role
            + "')"
        )
        manager, manager_secret, manager_recovery = self_enroll(names["manager"], label="MFA manager QA")
        sessions.append(manager)
        require(call(manager, MFA_MANAGE)[0] == 200, "Global MFA manager could not access its management page")

        # TOTP must be single-use. A recovery code can complete the same pending challenge once.
        first_challenge = client()
        sessions.append(first_challenge)
        first = challenge(first_challenge, names["primary"])
        require(call(first_challenge, "/auth/me")[0] == 401, "Primary password authenticated before the second factor")
        require(call(first_challenge, MFA_ACCOUNT)[0] in (401, 403), "Pending challenge accessed authenticated MFA account data")
        pending = page(first_challenge, SECOND_FACTOR + "/pending")
        require(pending["secondFactorRequired"] and str(pending["challengeId"]) == str(first["challengeId"]), "Challenge was not bound to the login session")
        stranger = client()
        sessions.append(stranger)
        cross = write(
            stranger,
            SECOND_FACTOR + "/confirm",
            "POST",
            {"challengeId": first["challengeId"], "code": primary_recovery[0]},
        )
        require(cross[0] == 400 and call(stranger, "/auth/me")[0] == 401, "A second browser session completed the challenge")

        previous_step = int(sql("SELECT last_step FROM plugin_mfa_authenticator WHERE user_id='" + users["primary"]["id"] + "'"))
        current_totp, current_step = fresh_code(setup_secret, previous_step)
        require(current_step > previous_step, "Login TOTP did not advance beyond setup code")
        complete_challenge(first_challenge, first["challengeId"], current_totp)
        require(call(first_challenge, "/auth/me")[0] == 200, "Valid TOTP did not complete login")

        replay_client = client()
        sessions.append(replay_client)
        replay = challenge(replay_client, names["primary"])
        status, _ = write(
            replay_client,
            SECOND_FACTOR + "/confirm",
            "POST",
            {"challengeId": replay["challengeId"], "code": current_totp},
        )
        require(status == 400 and call(replay_client, "/auth/me")[0] == 401, "A TOTP value was accepted twice")
        complete_challenge(replay_client, replay["challengeId"], primary_recovery[0])
        require(call(replay_client, "/auth/me")[0] == 200, "Unused recovery code did not complete login")
        require(sql("SELECT count(*) FROM plugin_mfa_recovery WHERE user_id='" + users["primary"]["id"] + "'") == "9", "Recovery code was not consumed")

        recovery_replay_client = client()
        sessions.append(recovery_replay_client)
        recovery_replay = challenge(recovery_replay_client, names["primary"])
        status, _ = write(
            recovery_replay_client,
            SECOND_FACTOR + "/confirm",
            "POST",
            {"challengeId": recovery_replay["challengeId"], "code": primary_recovery[0]},
        )
        require(status == 400 and call(recovery_replay_client, "/auth/me")[0] == 401, "A recovery code was accepted twice")
        cancel_pending(recovery_replay_client)

        new_recovery = json_write(
            replay_client,
            MFA_ACCOUNT + "/recovery",
            "POST",
            {"currentPassword": USER_PASSWORD, "code": primary_recovery[1]},
        )["recoveryCodes"]
        require(len(new_recovery) == 10 and sql("SELECT count(*) FROM plugin_mfa_recovery WHERE user_id='" + users["primary"]["id"] + "'") == "10", "Recovery regeneration failed")
        json_write(
            replay_client,
            MFA_ACCOUNT + "/label",
            "PUT",
            {"currentPassword": USER_PASSWORD, "label": "Renamed QA authenticator"},
        )
        require(json_get(replay_client, MFA_ACCOUNT)["label"] == "Renamed QA authenticator", "Authenticator rename was not persisted")
        stale_code_client = client()
        sessions.append(stale_code_client)
        stale_code_challenge = challenge(stale_code_client, names["primary"])
        status, _ = write(
            stale_code_client,
            SECOND_FACTOR + "/confirm",
            "POST",
            {"challengeId": stale_code_challenge["challengeId"], "code": primary_recovery[2]},
        )
        require(status == 400, "A code invalidated by recovery regeneration was accepted")
        cancel_pending(stale_code_client)

        expired_client = client()
        sessions.append(expired_client)
        expired_challenge = challenge(expired_client, names["primary"])
        sql(
            "UPDATE platform_login_challenge SET expires_at=now()-interval '1 second' WHERE id='"
            + str(expired_challenge["challengeId"])
            + "' AND user_id='"
            + users["primary"]["id"]
            + "'"
        )
        pending = page(expired_client, SECOND_FACTOR + "/pending")
        require(pending["secondFactorRequired"] is False, "Expired login challenge remained pending")
        status, _ = write(
            expired_client,
            SECOND_FACTOR + "/confirm",
            "POST",
            {"challengeId": expired_challenge["challengeId"], "code": new_recovery[0]},
        )
        require(status == 400 and call(expired_client, "/auth/me")[0] == 401, "Expired login challenge was accepted")
        cancel_pending(expired_client)

        limited_client = client()
        sessions.append(limited_client)
        limited = challenge(limited_client, names["primary"])
        bad = wrong_code(setup_secret)
        for _ in range(5):
            status, _ = write(
                limited_client,
                SECOND_FACTOR + "/confirm",
                "POST",
                {"challengeId": limited["challengeId"], "code": bad},
            )
            require(status == 400, "Invalid challenge code was not rejected")
        require(sql("SELECT attempts FROM platform_login_challenge WHERE id='" + str(limited["challengeId"]) + "'") == "5", "Challenge did not persist its five-attempt limit")
        status, _ = write(
            limited_client,
            SECOND_FACTOR + "/confirm",
            "POST",
            {"challengeId": limited["challengeId"], "code": new_recovery[1]},
        )
        require(status == 400 and page(limited_client, SECOND_FACTOR + "/pending")["secondFactorRequired"] is False, "Challenge survived the five-attempt limit")
        require(call(limited_client, "/auth/me")[0] == 401, "Five failed attempts produced an authenticated session")
        cancel_pending(limited_client)
        print("PASS: session-bound challenge, TOTP/recovery replay protection, recovery rotation, expiry, and five-attempt lockout")

        admin_row = account_lookup(admin, ADMIN)
        target_id = users["target"]["id"]
        reset_path = f"{MFA_MANAGE}/{target_id}/reset"
        bad_password = write(
            admin,
            reset_path,
            "POST",
            {"currentPassword": "wrong-QA-password", "reason": "QA reset target"},
        )
        require(bad_password[0] == 400, "MFA administrator reset did not require reauthentication")
        missing_reason = write(
            admin,
            reset_path,
            "POST",
            {"currentPassword": config["LUMIRA_BOOTSTRAP_PASSWORD"], "code": "", "reason": "x"},
        )
        require(missing_reason[0] == 400, "MFA administrator reset accepted a missing reason")
        self_reset = write(
            admin,
            f"{MFA_MANAGE}/{admin_row['id']}/reset",
            "POST",
            {"currentPassword": config["LUMIRA_BOOTSTRAP_PASSWORD"], "reason": "QA self reset"},
        )
        require(self_reset[0] == 400, "MFA administrator reset allowed self-reset")

        # Reset revokes the target's active session, then removes the login requirement.
        manager_missing_code = write(
            manager,
            reset_path,
            "POST",
            {"currentPassword": USER_PASSWORD, "reason": "QA verify session revocation", "code": ""},
        )
        require(manager_missing_code[0] == 400, "An MFA-enabled administrator reset did not require their own code")
        status, _ = write(
            manager,
            reset_path,
            "POST",
            {"currentPassword": USER_PASSWORD, "reason": "QA verify session revocation", "code": manager_recovery[0]},
        )
        require(status == 200, "Reauthenticated MFA administrator could not reset the other account")
        require(call(target, "/auth/me")[0] == 401, "Administrator MFA reset left the target session active")
        require(sql("SELECT count(*) FROM plugin_mfa_reset_audit WHERE actor='" + names["manager"] + "' AND target_id='" + target_id + "' AND reason='QA verify session revocation'") == "1", "MFA reset reason was not persisted")
        require(sql("SELECT count(*) FROM platform_audit WHERE actor='" + names["manager"] + "' AND action='mfa.adminReset' AND target_id='" + target_id + "'") == "1", "MFA administrator reset was not audited")
        require(sql("SELECT count(*) FROM platform_second_factor_requirement WHERE user_id='" + target_id + "'") == "0", "MFA reset left the target login requirement in place")
        require(login(client(), names["target"], USER_PASSWORD) == 200, "Reset target could not sign in after MFA removal")

        # A still-MFA-enabled identity remains denied when disabled or soft-deleted.
        manager_row = account_lookup(admin, names["manager"])
        json_write(
            admin,
            "/platform/users/" + users["manager"]["id"],
            "PUT",
            {"displayName": manager_row["displayName"], "enabled": False, "version": manager_row["version"]},
        )
        require(login(client(), names["manager"], USER_PASSWORD) == 401, "Disabled MFA account could still sign in")
        manager_row = account_lookup(admin, names["manager"])
        json_write(
            admin,
            "/platform/users/" + users["manager"]["id"],
            "PUT",
            {"displayName": manager_row["displayName"], "enabled": True, "version": manager_row["version"]},
        )
        manager_row = account_lookup(admin, names["manager"])
        status, _ = call(
            admin,
            "/platform/users/" + users["manager"]["id"] + "?version=" + str(manager_row["version"]),
            "DELETE",
            b"",
            token(admin),
        )
        require(status == 204, "Could not soft-delete the isolated MFA manager")
        require(login(client(), names["manager"], USER_PASSWORD) == 401, "Soft-deleted MFA account could still sign in")
        print("PASS: admin reauthentication, required reason, no self-reset, disabled/deleted MFA-account denial, reset audit, and factor removal")

        # Keep the primary factor active for hotplug checks and preserve its recovery set.
        require(json_get(primary, MFA_ACCOUNT)["enabled"], "Primary MFA user unexpectedly lost enrollment")
        required = sql("SELECT count(*) FROM platform_second_factor_requirement WHERE user_id='" + users["primary"]["id"] + "' AND provider_id='mfa'")
        require(required == "1", "Primary MFA user was not required to use the provider")

        if not skip_hotplug:
            before_pids = process_ids()
            plugin = plugin_view(admin)
            require(plugin and plugin["installed"] and plugin["enabled"], "MFA plugin is not enabled before hotplug check")
            plugin_original_installed = plugin["installed"]
            module = next(item for item in page(admin, "/platform/modules") if item["id"] == "mfa")
            plugin_original_enabled = module["enabled"]
            try:
                set_plugin_enabled(admin, False)
                assert_fail_closed(names["primary"])
                set_plugin_enabled(admin, True)
                operate_plugin(admin, "uninstall")
                plugin = plugin_view(admin)
                require(plugin and not plugin["installed"], "MFA uninstall did not withdraw the plugin")
                assert_fail_closed(names["primary"])
                operate_plugin(admin, "install")
                plugin = plugin_view(admin)
                require(
                    plugin
                    and plugin["installed"]
                    and plugin["enabled"]
                    and plugin["status"] == "RUNNING",
                    "MFA reinstall did not restore the plugin in its isolated container",
                )
                require(sql("SELECT count(*) FROM platform_second_factor_requirement WHERE user_id='" + users["primary"]["id"] + "' AND provider_id='mfa'") == "1", "Uninstall erased the core MFA requirement")
                restored = client()
                sessions.append(restored)
                after_install = challenge(restored, names["primary"])
                complete_challenge(restored, after_install["challengeId"], new_recovery[0])
                require(call(restored, "/auth/me")[0] == 200, "Preserved recovery code could not sign in after reinstall")
                require(process_ids() == before_pids, "MFA disable/uninstall/reinstall restarted a host service")
            finally:
                current = plugin_view(admin)
                if current and not current["installed"] and plugin_original_installed:
                    operate_plugin(admin, "install")
                module = next(item for item in page(admin, "/platform/modules") if item["id"] == "mfa")
                if module["enabled"] != plugin_original_enabled:
                    set_plugin_enabled(admin, plugin_original_enabled)
            require(process_ids() == before_pids, "MFA hotplug cleanup restarted a host service")
            print("PASS: disable/enable and uninstall/reinstall stay in-process; missing required provider fails closed with HTTP 503; recovery data survives")

        # Self-service unbind needs both the password and an unused recovery code.
        status, _ = write(
            primary,
            MFA_ACCOUNT,
            "DELETE",
            {"currentPassword": USER_PASSWORD, "code": new_recovery[2]},
        )
        require(status in (200, 204), "Self-service unbind was rejected")
        require(json_get(primary, MFA_ACCOUNT)["enabled"] is False, "Unbind left the authenticator enabled")
        require(sql("SELECT count(*) FROM platform_second_factor_requirement WHERE user_id='" + users["primary"]["id"] + "'") == "0", "Unbind left a second-factor login requirement")
        require(sql("SELECT count(*) FROM plugin_mfa_authenticator WHERE user_id='" + users["primary"]["id"] + "'") == "0", "Unbind left the authenticator secret stored")
        require(call(replay_client, "/auth/me")[0] == 401, "Unbind did not revoke the other authenticated session")
        require(call(primary, "/auth/me")[0] == 200, "Unbind revoked the session used to confirm the operation")
        print("PASS: self-service unbind reauth, factor removal, requirement removal, and other-session revocation")

        print("PASS: MFA HTTP integration acceptance complete; only qa-mfa-* users were used")
    finally:
        for c in sessions:
            try:
                write(c, "/auth/logout", "POST", {})
            except Exception:
                pass
        if manager_role:
            sql(
                "DELETE FROM platform_user_role WHERE role_id='"
                + manager_role
                + "'; DELETE FROM platform_role_authority WHERE role_id='"
                + manager_role
                + "'; DELETE FROM platform_role WHERE id='"
                + manager_role
                + "'"
            )
        for role in ("primary", "target", "observer", "manager"):
            if role in users:
                delete_user(users[role]["id"], names[role])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--prepare-browser", action="store_true", help="create and retain a browser-only qa-mfa account")
    mode.add_argument("--grant-browser-management", action="store_true", help="grant the browser-only fixture a temporary MFA management role")
    mode.add_argument("--cleanup-browser", action="store_true", help="remove the retained browser-only qa-mfa account")
    mode.add_argument("--passkey-only", action="store_true", help="exercise a synthetic signed WebAuthn assertion followed by MFA")
    parser.add_argument("--skip-hotplug", action="store_true", help="skip reversible MFA plugin disable/uninstall/reinstall checks")
    args = parser.parse_args()
    if args.prepare_browser:
        create_browser_fixture()
    elif args.grant_browser_management:
        grant_browser_management()
    elif args.cleanup_browser:
        cleanup_browser_fixture()
    elif args.passkey_only:
        run_passkey_smoke()
    else:
        run_smoke(args.skip_hotplug)


if __name__ == "__main__":
    main()
