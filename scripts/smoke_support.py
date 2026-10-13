"""Shared local HTTP/SQL helpers; never log secrets or touch the legacy database."""

import http.cookiejar
import json
import os
import re
import stat
import subprocess
import sys
import time
import uuid
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASE_URL = "http://127.0.0.1:8010"
BASE_URL = (os.environ.get("BASE_URL") or DEFAULT_BASE_URL).rstrip("/")
API_BASE_URL = f"{BASE_URL}/api"
DATABASE_NAMESPACE = re.compile(r"lumira_(?:security|qa|test|smoke)_[a-z0-9_]+\Z")
RUN_TOKEN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\Z", re.IGNORECASE)
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
QA_MARKER_QUERY = "SELECT run_token::text || '|' || purpose FROM public.lumira_qa_fixture_guard WHERE id = true;"
TARGET_BINDING_HTTP_TIMEOUT_SECONDS = 5
TARGET_BINDING_TOTAL_TIMEOUT_SECONDS = 8
TARGET_BINDING_SQL_TIMEOUT_SECONDS = 10
TARGET_BINDING_MAX_RESPONSE_BYTES = 16 * 1024
TARGET_BINDING_READ_CHUNK_BYTES = 4096
SESSION_COOKIE_NAME = "LUMIRA_NEXT_SESSION"
AUTHORIZATION_REVISION_HEADER = "X-Lumira-Authorization-Context-Revision"
AUTH_CONTEXT_EXEMPT_ROUTES = {
    "/auth/csrf",
    "/auth/login",
    "/auth/logout",
    "/auth/me",
}


def default_config_path():
    return PROJECT_ROOT / ".runtime" / "local.json"


def config_path():
    configured = os.environ.get("LUMIRA_SMOKE_CONFIG")
    if not isinstance(configured, str) or not configured.strip():
        raise RuntimeError("Set LUMIRA_SMOKE_CONFIG to an explicit private QA config; refusing the legacy local config.")
    path = Path(configured).expanduser()
    if not path.is_absolute():
        path = PROJECT_ROOT / path
    path = Path(os.path.abspath(path))
    if path == Path(os.path.abspath(default_config_path())):
        raise RuntimeError("Refusing to use the legacy .runtime/local.json config for QA fixtures or API calls.")
    try:
        info = path.lstat()
    except OSError as error:
        raise RuntimeError("The explicit QA config must exist before the smoke can run.") from error
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise RuntimeError("The QA config must be a regular private file, not a symlink.")
    if stat.S_IMODE(info.st_mode) & 0o077:
        raise RuntimeError("The QA config must be private to its owner (mode 0600 or stricter).")
    return path


def load_config():
    try:
        return json.loads(config_path().read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError("The explicit private QA config could not be read as JSON.") from error


config = load_config()
BACKEND_BASE_URL = (
    os.environ.get("LUMIRA_BACKEND_BASE_URL")
    or config.get("LUMIRA_BACKEND_BASE_URL", "")
).rstrip("/")


def _validate_loopback_origin(value, label, *, default=None):
    candidate = value or default
    if not isinstance(candidate, str) or not candidate:
        raise RuntimeError(f"{label} must identify a loopback QA origin.")
    try:
        parsed = urllib.parse.urlsplit(candidate)
        _ = parsed.port
    except ValueError as error:
        raise RuntimeError(f"{label} must identify a loopback QA origin.") from error
    if (
        parsed.scheme not in {"http", "https"}
        or parsed.hostname not in LOOPBACK_HOSTS
        or parsed.username
        or parsed.password
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise RuntimeError(f"{label} must be a loopback origin without credentials or a path.")
    return parsed


def _validate_qa_config():
    # config_path() checks explicit selection, symlink status, and private mode.
    config_path()
    _validate_loopback_origin(BASE_URL, "BASE_URL", default=DEFAULT_BASE_URL)
    if BACKEND_BASE_URL:
        _validate_loopback_origin(BACKEND_BASE_URL, "LUMIRA_BACKEND_BASE_URL")

    jdbc_url = str(config.get("LUMIRA_DB_URL", ""))
    try:
        database = urllib.parse.urlsplit(jdbc_url.removeprefix("jdbc:"))
        _ = database.port
    except ValueError as error:
        raise RuntimeError("LUMIRA_DB_URL must identify the dedicated local QA PostgreSQL database.") from error
    if database.scheme != "postgresql" or database.hostname not in LOOPBACK_HOSTS:
        raise RuntimeError("LUMIRA_DB_URL must use a loopback PostgreSQL database.")
    database_name = urllib.parse.unquote(database.path.lstrip("/"))
    if not DATABASE_NAMESPACE.fullmatch(database_name):
        raise RuntimeError("The database name must use the dedicated lumira_{security|qa|test|smoke}_ namespace.")

    run_token = config.get("LUMIRA_QA_RUN_TOKEN")
    if not isinstance(run_token, str) or not RUN_TOKEN.fullmatch(run_token):
        raise RuntimeError("The private QA config must provide a valid LUMIRA_QA_RUN_TOKEN UUID.")
    if not config.get("LUMIRA_DB_USER") or not isinstance(config.get("LUMIRA_DB_PASSWORD"), str):
        raise RuntimeError("The private QA config must include its PostgreSQL fixture credentials.")
    return database_name, run_token.lower()


def _psql_command_unchecked():
    jdbc_url = str(config.get("LUMIRA_DB_URL", ""))
    parsed = urllib.parse.urlsplit(jdbc_url.removeprefix("jdbc:"))
    if parsed.scheme != "postgresql" or not parsed.path.strip("/"):
        raise ValueError("LUMIRA_DB_URL must identify a PostgreSQL host and database")
    try:
        port = parsed.port or 5432
    except ValueError as error:
        raise ValueError("LUMIRA_DB_URL contains an invalid PostgreSQL port") from error

    return [
        os.environ.get("PSQL_BIN", "/opt/homebrew/opt/postgresql@18/bin/psql"),
        "-X",
        "-A",
        "-t",
        "-h",
        parsed.hostname or "127.0.0.1",
        "-p",
        str(port),
        "-U",
        config["LUMIRA_DB_USER"],
        "-d",
        urllib.parse.unquote(parsed.path.lstrip("/")),
        "-v",
        "ON_ERROR_STOP=1",
    ]


def _psql_environment_unchecked():
    return {**os.environ, "PGPASSWORD": config["LUMIRA_DB_PASSWORD"]}


def _run_psql_unchecked(statement, *, timeout=20):
    result = subprocess.run(
        _psql_command_unchecked(),
        input=statement,
        text=True,
        capture_output=True,
        env=_psql_environment_unchecked(),
        timeout=timeout,
    )
    return result


_qa_run_guard_verified = False
_qa_run_guard_checking = False
_qa_target_binding_verified = False
_qa_target_bound_origins = set()


def ensure_qa_run_guard():
    """Validate the isolated QA marker once before any fixture SQL or HTTP I/O."""
    global _qa_run_guard_verified, _qa_run_guard_checking
    if _qa_run_guard_verified:
        return
    if _qa_run_guard_checking:
        raise RuntimeError("Recursive QA guard validation is not allowed.")
    _validate_qa_config()
    _qa_run_guard_checking = True
    try:
        try:
            result = _run_psql_unchecked(QA_MARKER_QUERY, timeout=15)
        except (OSError, subprocess.SubprocessError) as error:
            raise RuntimeError("The read-only QA marker check failed; refusing fixtures and API calls.") from error
        if result.returncode != 0:
            raise RuntimeError("The read-only QA marker check failed; refusing fixtures and API calls.")
        rows = [line.strip() for line in result.stdout.splitlines() if line.strip()]
        expected = f"{config['LUMIRA_QA_RUN_TOKEN'].lower()}|isolated-security-qa"
        if rows != [expected]:
            raise RuntimeError("The configured run token must match the single isolated-security-qa marker row.")
        _qa_run_guard_verified = True
    finally:
        _qa_run_guard_checking = False


def _origin(value, label):
    parsed = _validate_loopback_origin(value, label)
    return f"{parsed.scheme}://{parsed.netloc}"


_SESSION_BINDING_PROBE = r'''import base64, http.cookies, json, os, ssl, sys, time, urllib.request, uuid

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None

def probe():
    origin = os.environ["QA_TARGET_ORIGIN"]
    socket_timeout = float(os.environ["QA_TARGET_HTTP_TIMEOUT"])
    total_timeout = float(os.environ["QA_TARGET_TOTAL_TIMEOUT"])
    max_bytes = int(os.environ["QA_TARGET_MAX_BYTES"])
    chunk_bytes = int(os.environ["QA_TARGET_READ_CHUNK_BYTES"])
    deadline = time.monotonic() + total_timeout
    tls_context = ssl.create_default_context()
    cert_file = os.environ.get("SSL_CERT_FILE")
    cert_dir = os.environ.get("SSL_CERT_DIR")
    if cert_file or cert_dir:
        tls_context.load_verify_locations(cafile=cert_file, capath=cert_dir)
    tls_context.verify_mode = ssl.CERT_REQUIRED
    tls_context.check_hostname = True
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        NoRedirect(),
        urllib.request.HTTPSHandler(context=tls_context),
    )
    request = urllib.request.Request(origin + "/api/auth/csrf", headers={"Accept": "application/json"}, method="GET")
    with opener.open(request, timeout=socket_timeout) as response:
        if response.status != 200:
            raise ValueError("unexpected status")
        raw = getattr(getattr(response, "fp", None), "raw", None)
        connection = getattr(raw, "_sock", None)
        if not callable(getattr(connection, "settimeout", None)) or not callable(getattr(response, "read1", None)):
            raise ValueError("bounded reads unavailable")
        body = bytearray()
        while len(body) <= max_bytes:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("total response deadline")
            # HTTPResponse may close the underlying socket as soon as a
            # Content-Length or chunked body is fully consumed. Avoid setting
            # a timeout on that closed socket (EBADF on Python 3.12); the
            # response.length check below handles complete fixed-size bodies.
            if connection.fileno() >= 0:
                connection.settimeout(min(socket_timeout, remaining))
            chunk = response.read1(min(chunk_bytes, max_bytes + 1 - len(body)))
            if not chunk:
                break
            body.extend(chunk)
            if response.length == 0:
                break
        if len(body) > max_bytes or time.monotonic() > deadline:
            raise ValueError("response budget exceeded")
        cookies = []
        for value in response.headers.get_all("Set-Cookie", []):
            parsed = http.cookies.SimpleCookie()
            parsed.load(value)
            morsel = parsed.get("LUMIRA_NEXT_SESSION")
            if morsel is not None:
                cookies.append(morsel.value)
    payload = json.loads(body)
    if not isinstance(payload, dict) or not isinstance(payload.get("headerName"), str) or not payload["headerName"] or len(payload["headerName"]) > 128:
        raise ValueError("invalid csrf response")
    if not isinstance(payload.get("token"), str) or not payload["token"] or len(payload["token"]) > 4096:
        raise ValueError("invalid csrf response")
    if len(cookies) != 1 or not cookies[0]:
        raise ValueError("missing session cookie")
    decoded = base64.b64decode(cookies[0], validate=True).decode("ascii")
    session_id = str(uuid.UUID(decoded))
    if decoded.casefold() != session_id:
        raise ValueError("noncanonical session id")
    return session_id

try:
    print(probe())
except BaseException:
    sys.exit(2)
'''


def _new_session_id_for_origin(origin):
    child_env = {
        "QA_TARGET_ORIGIN": origin,
        "QA_TARGET_HTTP_TIMEOUT": str(TARGET_BINDING_HTTP_TIMEOUT_SECONDS),
        "QA_TARGET_TOTAL_TIMEOUT": str(TARGET_BINDING_TOTAL_TIMEOUT_SECONDS),
        "QA_TARGET_MAX_BYTES": str(TARGET_BINDING_MAX_RESPONSE_BYTES),
        "QA_TARGET_READ_CHUNK_BYTES": str(TARGET_BINDING_READ_CHUNK_BYTES),
        "PYTHONIOENCODING": "utf-8",
    }
    for variable in ("SSL_CERT_FILE", "SSL_CERT_DIR"):
        if variable in os.environ:
            child_env[variable] = os.environ[variable]
    hard_timeout = max(TARGET_BINDING_HTTP_TIMEOUT_SECONDS, TARGET_BINDING_TOTAL_TIMEOUT_SECONDS) + 1
    try:
        result = subprocess.run(
            [sys.executable, "-c", _SESSION_BINDING_PROBE],
            text=True,
            capture_output=True,
            env=child_env,
            timeout=hard_timeout,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("The loopback QA target session check failed or exceeded its time budget; refusing HTTP writes.") from None
    rows = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if result.returncode != 0 or len(rows) != 1:
        raise RuntimeError("The loopback QA target session check failed or exceeded its time budget; refusing HTTP writes.")
    try:
        session_id = str(uuid.UUID(rows[0]))
    except ValueError:
        raise RuntimeError("The loopback QA target returned an invalid session binding.") from None
    if rows[0] != session_id:
        raise RuntimeError("The loopback QA target returned an invalid session binding.")
    return session_id


def _session_exists_query(session_id):
    # session_id comes only from strict Base64-decoded canonical UUID validation.
    return (
        "SELECT EXISTS (SELECT 1 FROM public.spring_session "
        f"WHERE session_id = '{session_id}' AND expiry_time > "
        "(extract(epoch FROM clock_timestamp()) * 1000)::bigint);"
    )


def _assert_session_in_marked_database(session_id):
    try:
        result = _run_psql_unchecked(
            _session_exists_query(session_id),
            timeout=TARGET_BINDING_SQL_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise RuntimeError("The QA target session could not be verified in the marked database.") from None
    rows = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if result.returncode != 0 or rows != ["t"]:
        raise RuntimeError("The QA target session is absent from the marked database; refusing HTTP writes.")


def ensure_qa_target_binding():
    """Bind each configured loopback HTTP origin to the marked QA database."""
    global _qa_target_binding_verified
    ensure_qa_run_guard()
    if _qa_target_binding_verified:
        return

    origins = [_origin(BASE_URL, "BASE_URL")]
    if BACKEND_BASE_URL:
        origins.append(_origin(BACKEND_BASE_URL, "LUMIRA_BACKEND_BASE_URL"))
    for origin in dict.fromkeys(origins):
        if origin in _qa_target_bound_origins:
            continue
        session_id = _new_session_id_for_origin(origin)
        _assert_session_in_marked_database(session_id)
        _qa_target_bound_origins.add(origin)
    _qa_target_binding_verified = True


def psql_command():
    ensure_qa_run_guard()
    return _psql_command_unchecked()


def psql_environment():
    ensure_qa_run_guard()
    return _psql_environment_unchecked()


def sql(statement):
    ensure_qa_run_guard()
    result = _run_psql_unchecked(statement)
    assert result.returncode == 0, result.stderr or "Database fixture operation failed"
    return result.stdout.strip()


def client():
    ensure_qa_target_binding()
    # The desktop proxy is enabled globally on macOS; local smoke tests must
    # connect directly so a proxy cannot close the loopback request.
    connection = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
    )
    connection._lumira_auth_context = {"user_id": None, "revision": None}
    return connection


def cookie_jar(connection):
    for handler in connection.handlers:
        if isinstance(handler, urllib.request.HTTPCookieProcessor):
            return handler.cookiejar
    raise ValueError("HTTP client has no cookie jar")


def _clear_auth_context(connection):
    connection._lumira_auth_context = {"user_id": None, "revision": None}


def _remember_auth_context(connection, body):
    try:
        value = json.loads(body)
        user_id = value.get("userId") if isinstance(value, dict) else None
        context = value.get("authorizationContext") if isinstance(value, dict) else None
        if not isinstance(user_id, str) or not user_id:
            _clear_auth_context(connection)
            return None
        revision = None
        if isinstance(context, dict) and context.get("rolePreviewSupported") is True:
            candidate = context.get("revision")
            if isinstance(candidate, str) and candidate:
                revision = candidate
        identity = {"user_id": user_id, "revision": revision}
        connection._lumira_auth_context = identity
        return identity
    except (UnicodeDecodeError, json.JSONDecodeError, AttributeError):
        _clear_auth_context(connection)
        return None


def _read_auth_context(connection):
    status, body = _send(connection, "/api/auth/me", "GET", None, {})
    if status != 200:
        return None
    return _remember_auth_context(connection, body)


def _request_path(path):
    api_path = path if path.startswith("/api/") else f"/api{path}"
    url = API_BASE_URL + api_path.removeprefix("/api")
    return api_path, url


def _send(connection, api_path, method, data, headers):
    ensure_qa_target_binding()
    _, url = _request_path(api_path)
    request = urllib.request.Request(
        url,
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with connection.open(request, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def _has_explicit_preview_or_revision(headers):
    for name in headers:
        normalized = str(name).casefold()
        if normalized == AUTHORIZATION_REVISION_HEADER.casefold():
            return True
        if normalized.startswith("x-lumira-") and "preview" in normalized:
            return True
    return False


def _authorization_context_changed(status, body):
    if status != 409:
        return False
    try:
        value = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return False
    return isinstance(value, dict) and value.get("detail") == "authorizationContextChanged"


def authorization_context_headers(connection, path, headers=None):
    """Add the current revision to raw authenticated requests, matching call()."""
    api_path, _ = _request_path(path)
    route = urllib.parse.urlsplit(api_path).path.removeprefix("/api")
    request_headers = dict(headers or {})
    state = getattr(connection, "_lumira_auth_context", {"user_id": None, "revision": None})
    revision = state.get("revision")
    if (
        revision
        and not _has_explicit_preview_or_revision(request_headers)
        and route not in AUTH_CONTEXT_EXEMPT_ROUTES
        and not route.startswith("/public/")
    ):
        request_headers[AUTHORIZATION_REVISION_HEADER] = revision
    return request_headers


def _clear_login_session(connection):
    _clear_auth_context(connection)
    cookie_jar(connection).clear()


def call(connection, path, method="GET", data=None, headers=None):
    api_path, _ = _request_path(path)
    route = urllib.parse.urlsplit(api_path).path.removeprefix("/api")
    method = method.upper()
    supplied_headers = dict(headers or {})
    explicit_context = _has_explicit_preview_or_revision(supplied_headers)
    state = getattr(connection, "_lumira_auth_context", {"user_id": None, "revision": None})
    revision = state.get("revision")
    add_revision = (
        bool(revision)
        and not explicit_context
        and route not in AUTH_CONTEXT_EXEMPT_ROUTES
        and not route.startswith("/public/")
    )
    request_headers = authorization_context_headers(connection, api_path, supplied_headers)

    is_logout = route == "/auth/logout"
    if is_logout:
        _clear_auth_context(connection)
    status, body = _send(connection, api_path, method, data, request_headers)

    if route == "/auth/me" and not explicit_context:
        if status == 200:
            _remember_auth_context(connection, body)
        else:
            _clear_auth_context(connection)

    if is_logout:
        _clear_login_session(connection)
        return status, body

    if (
        add_revision
        and _authorization_context_changed(status, body)
        and isinstance(state.get("user_id"), str)
        and state["user_id"]
    ):
        previous_user_id = state["user_id"]
        previous_revision = revision
        refreshed = _read_auth_context(connection)
        if refreshed is None or refreshed["user_id"] != previous_user_id:
            _clear_auth_context(connection)
            if refreshed is not None:
                cookie_jar(connection).clear()
            return status, body
        if refreshed["revision"] and refreshed["revision"] != previous_revision:
            retry_headers = dict(supplied_headers)
            retry_headers[AUTHORIZATION_REVISION_HEADER] = refreshed["revision"]
            return _send(connection, api_path, method, data, retry_headers)
    return status, body


def token(connection):
    status, body = call(connection, "/auth/csrf")
    assert status == 200, f"CSRF endpoint returned HTTP {status}"
    value = json.loads(body)
    return {value["headerName"]: value["token"]}


def login(connection, username, password):
    _clear_login_session(connection)
    body = urllib.parse.urlencode({"username": username, "password": password}).encode()
    headers = {**token(connection), "Content-Type": "application/x-www-form-urlencoded"}
    status, _ = call(connection, "/auth/login", "POST", body, headers)
    if status == 200:
        _read_auth_context(connection)
    return status


def write(connection, path, method, payload):
    headers = {**token(connection), "Content-Type": "application/json"}
    return call(connection, path, method, json.dumps(payload).encode(), headers)


def wait_for_readiness(timeout_seconds=20, poll_interval_seconds=0.1):
    """Wait on the QA backend; configure LUMIRA_BACKEND_BASE_URL when BASE_URL fronts the web app."""
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(
        (BACKEND_BASE_URL or BASE_URL) + "/actuator/health/readiness",
        headers={"Accept": "application/json"},
    )
    deadline = time.monotonic() + timeout_seconds
    last_status = None
    last_body = b""
    while time.monotonic() < deadline:
        try:
            with opener.open(request, timeout=5) as response:
                last_status, last_body = response.status, response.read()
        except urllib.error.HTTPError as error:
            last_status, last_body = error.code, error.read()
        except OSError as error:
            last_status, last_body = None, str(error).encode()
        if last_status == 200:
            try:
                health = json.loads(last_body)
            except (UnicodeDecodeError, json.JSONDecodeError):
                health = {}
            if health.get("status") == "UP":
                return
        time.sleep(poll_interval_seconds)
    raise AssertionError(
        (
            f"readiness did not recover within {timeout_seconds} seconds",
            last_status,
            last_body.decode(errors="replace"),
        )
    )


# Smoke modules frequently perform fixture setup immediately after importing
# this helper (and some use urllib/http.client directly). Fail closed at import
# so no module-level operation can precede the cached marker validation.
ensure_qa_run_guard()
