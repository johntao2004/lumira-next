"""Isolated checks for the Python smoke QA guard with fake psql and loopback HTTP."""

import base64
import http.server
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = PROJECT_ROOT / "scripts"
RUN_TOKEN = "123e4567-e89b-42d3-a456-426614174000"


class SmokeSupportQaGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lumira-smoke-guard-")
        self.directory = Path(self.temp.name)
        self.config_path = self.directory / "qa-config.json"
        self.query_log = self.directory / "psql-queries.log"
        self.fake_psql_path = self.directory / "fake-psql.py"
        self.write_config()
        self.write_fake_psql()

    def tearDown(self):
        self.temp.cleanup()

    def write_config(self, *, database="lumira_security_guard_test", token=RUN_TOKEN):
        self.config_path.write_text(json.dumps({
            "LUMIRA_DB_URL": f"jdbc:postgresql://127.0.0.1:5432/{database}",
            "LUMIRA_DB_USER": "qa_user",
            "LUMIRA_DB_PASSWORD": "fixture-secret-never-printed",
            "LUMIRA_QA_RUN_TOKEN": token,
        }))
        self.config_path.chmod(0o600)

    def write_fake_psql(self):
        self.fake_psql_path.write_text(
            "#!/usr/bin/env python3\n"
            "import os, re, sys\n"
            "query = sys.stdin.read()\n"
            "with open(os.environ['QA_FAKE_PSQL_QUERY_LOG'], 'a', encoding='utf-8') as log:\n"
            "    log.write(query.replace('\\n', '<NL>') + '\\n')\n"
            "if 'public.lumira_qa_fixture_guard' in query:\n"
            "    mode = os.environ.get('QA_FAKE_PSQL_MODE', 'valid')\n"
            "    if mode == 'valid': print(os.environ['LUMIRA_QA_RUN_TOKEN'] + '|isolated-security-qa')\n"
            "    elif mode == 'wrong-token': print('123e4567-e89b-42d3-a456-426614174001|isolated-security-qa')\n"
            "    elif mode == 'wrong-purpose': print(os.environ['LUMIRA_QA_RUN_TOKEN'] + '|other-purpose')\n"
            "    elif mode == 'multiple': print(os.environ['LUMIRA_QA_RUN_TOKEN'] + '|isolated-security-qa\\n' + os.environ['LUMIRA_QA_RUN_TOKEN'] + '|isolated-security-qa')\n"
            "    elif mode == 'missing': pass\n"
            "elif 'public.spring_session' in query:\n"
            "    match = re.search(r\"session_id\\s*=\\s*'([^']+)'\", query)\n"
            "    known = set(filter(None, os.environ.get('QA_FAKE_PSQL_SESSION_IDS', '').split(',')))\n"
            "    print('t' if match and match.group(1) in known else 'f')\n"
            "elif query.strip() == 'SELECT 1': print('mock-sql-ok')\n"
            "else: print('fixture-sql-must-not-run')\n"
        )
        self.fake_psql_path.chmod(0o700)

    def environment(self, **updates):
        result = dict(os.environ)
        result.update({
            "LUMIRA_SMOKE_CONFIG": str(self.config_path),
            "BASE_URL": "http://127.0.0.1:8010",
            "PSQL_BIN": str(self.fake_psql_path),
            "PYTHONPATH": str(SCRIPTS),
            "QA_FAKE_PSQL_QUERY_LOG": str(self.query_log),
            "QA_FAKE_PSQL_MODE": "valid",
            "QA_FAKE_PSQL_SESSION_IDS": "",
            "LUMIRA_QA_RUN_TOKEN": RUN_TOKEN,
        })
        result.update({key: str(value) for key, value in updates.items()})
        return result

    def run_python(self, source, env=None):
        return subprocess.run(
            [sys.executable, "-c", source],
            cwd=SCRIPTS,
            env=env or self.environment(),
            text=True,
            capture_output=True,
            timeout=10,
            check=False,
        )

    def queries(self):
        return self.query_log.read_text().splitlines() if self.query_log.exists() else []

    def start_csrf_server(self, session_id, *, set_cookie=True, delay=0, drip_delay=0):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.server.request_count += 1
                if self.path != "/api/auth/csrf":
                    self.send_error(404)
                    return
                if self.server.delay:
                    time.sleep(self.server.delay)
                body = b'{"headerName":"X-CSRF-TOKEN","token":"mock-token"}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                if self.server.set_cookie:
                    encoded = base64.b64encode(self.server.session_id.encode("ascii")).decode("ascii")
                    self.send_header(
                        "Set-Cookie",
                        f"LUMIRA_NEXT_SESSION={encoded}; Path=/; HttpOnly; SameSite=Lax",
                    )
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if self.server.drip_delay:
                    for byte in body:
                        try:
                            self.wfile.write(bytes([byte]))
                            self.wfile.flush()
                        except OSError:
                            return
                        time.sleep(self.server.drip_delay)
                else:
                    self.wfile.write(body)

            def log_message(self, _format, *_args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.session_id = session_id
        server.set_cookie = set_cookie
        server.delay = delay
        server.drip_delay = drip_delay
        server.request_count = 0
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def stop_server():
            server.shutdown()
            thread.join(timeout=2)
            server.server_close()

        self.addCleanup(stop_server)
        return f"http://127.0.0.1:{server.server_address[1]}", server

    def test_import_checks_marker_read_only_once_and_caches_success(self):
        result = self.run_python(
            "import smoke_support; print(smoke_support.sql('SELECT 1'))"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "mock-sql-ok")
        queries = self.queries()
        self.assertEqual(len(queries), 2, queries)
        self.assertTrue(queries[0].startswith("SELECT "))
        self.assertIn("FROM public.lumira_qa_fixture_guard WHERE id = true;", queries[0])
        self.assertEqual(queries[1], "SELECT 1")

    def test_absent_explicit_config_refuses_before_psql_or_any_module_work(self):
        env = self.environment()
        env.pop("LUMIRA_SMOKE_CONFIG")
        result = self.run_python("import smoke_support; print('UNGUARDED')", env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("explicit private QA config", result.stderr)
        self.assertNotIn("UNGUARDED", result.stdout)
        self.assertEqual(self.queries(), [])

    def test_legacy_local_config_path_is_rejected_without_reading_it(self):
        result = self.run_python(
            "import smoke_support",
            self.environment(LUMIRA_SMOKE_CONFIG=PROJECT_ROOT / ".runtime" / "local.json"),
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing to use the legacy .runtime/local.json", result.stderr)
        self.assertEqual(self.queries(), [])

    def test_non_qa_database_name_is_rejected_before_read_only_marker_cli(self):
        self.write_config(database="lumira_local")
        result = self.run_python("import smoke_support", self.environment())
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dedicated lumira_{security|qa|test|smoke}_ namespace", result.stderr)
        self.assertEqual(self.queries(), [], "The CLI must not try matching a QA marker in a non-QA database")

    def test_wrong_token_missing_marker_wrong_purpose_and_duplicate_rows_refuse_before_sql(self):
        for mode, expected in [
            ("wrong-token", "run token must match"),
            ("missing", "run token must match"),
            ("wrong-purpose", "run token must match"),
            ("multiple", "run token must match"),
        ]:
            with self.subTest(mode=mode):
                self.query_log.unlink(missing_ok=True)
                env = self.environment(QA_FAKE_PSQL_MODE=mode)
                result = self.run_python(
                    "import smoke_support; smoke_support.sql('DELETE FROM platform_user')",
                    env,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(expected, result.stderr)
                query_log = self.queries()
                self.assertEqual(len(query_log), 1, query_log)
                self.assertIn("public.lumira_qa_fixture_guard", query_log[0])
                self.assertNotIn("DELETE FROM platform_user", "\n".join(query_log))

    def test_config_symlink_or_group_readable_file_is_rejected_before_psql(self):
        real = self.directory / "real-config.json"
        real.write_bytes(self.config_path.read_bytes())
        real.chmod(0o600)
        link = self.directory / "config-link.json"
        link.symlink_to(real)
        self.config_path.unlink()
        result = self.run_python("import smoke_support", self.environment(LUMIRA_SMOKE_CONFIG=link))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("regular private file, not a symlink", result.stderr)
        self.assertEqual(self.queries(), [])

        self.config_path.write_bytes(real.read_bytes())
        self.config_path.chmod(0o644)
        result = self.run_python("import smoke_support", self.environment())
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("private to its owner", result.stderr)
        self.assertEqual(self.queries(), [])

    def test_non_loopback_http_target_is_rejected_before_marker(self):
        result = self.run_python("import smoke_support", self.environment(BASE_URL="https://example.invalid"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("BASE_URL must be a loopback origin", result.stderr)
        self.assertEqual(self.queries(), [])

    def test_each_sql_entry_revalidates_cached_guard_before_fixture_statement(self):
        source = "\n".join([
            "import os, smoke_support",
            "smoke_support._qa_run_guard_verified = False",
            "os.environ['QA_FAKE_PSQL_MODE'] = 'missing'",
            "try:",
            "    smoke_support.sql('DELETE FROM platform_user')",
            "except RuntimeError as error:",
            "    print(str(error))",
        ])
        result = self.run_python(source)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("run token must match", result.stdout)
        queries = self.queries()
        self.assertEqual(len(queries), 2, queries)
        self.assertTrue(all("public.lumira_qa_fixture_guard" in query for query in queries))
        self.assertNotIn("DELETE FROM platform_user", "\n".join(queries))

    def test_api_entry_revalidates_guard_before_opening_http_connection(self):
        source = "\n".join([
            "import os, smoke_support",
            "connection = object()",
            "smoke_support._qa_run_guard_verified = False",
            "os.environ['QA_FAKE_PSQL_MODE'] = 'missing'",
            "try:",
            "    smoke_support.call(connection, '/must-not-connect')",
            "except RuntimeError as error:",
            "    print(str(error))",
        ])
        result = self.run_python(source)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("run token must match", result.stdout)
        queries = self.queries()
        self.assertEqual(len(queries), 2, queries)
        self.assertTrue(all("public.lumira_qa_fixture_guard" in query for query in queries))

    def test_binding_checks_frontend_and_optional_backend_sessions_in_marked_database(self):
        frontend_session = "223e4567-e89b-42d3-a456-426614174000"
        backend_session = "323e4567-e89b-42d3-a456-426614174000"
        frontend, frontend_server = self.start_csrf_server(frontend_session)
        backend, backend_server = self.start_csrf_server(backend_session)
        env = self.environment(
            BASE_URL=frontend,
            LUMIRA_BACKEND_BASE_URL=backend,
            QA_FAKE_PSQL_SESSION_IDS=f"{frontend_session},{backend_session}",
        )
        result = self.run_python(
            "import smoke_support; smoke_support.ensure_qa_target_binding(); print('QA_TARGET_BOUND')",
            env,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "QA_TARGET_BOUND")
        self.assertEqual(frontend_server.request_count, 1)
        self.assertEqual(backend_server.request_count, 1)
        queries = self.queries()
        self.assertEqual(len(queries), 3, queries)
        self.assertIn("public.lumira_qa_fixture_guard", queries[0])
        self.assertIn(frontend_session, queries[1])
        self.assertIn(backend_session, queries[2])

    def test_same_marker_in_different_qa_database_does_not_bind_app_target(self):
        session_id = "423e4567-e89b-42d3-a456-426614174000"
        origin, server = self.start_csrf_server(session_id)
        self.write_config(database="lumira_security_other_qa")
        result = self.run_python(
            "import smoke_support; smoke_support.ensure_qa_target_binding(); print('QA_TARGET_BOUND')",
            self.environment(BASE_URL=origin, QA_FAKE_PSQL_SESSION_IDS="different-session-id"),
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("session is absent from the marked database", result.stderr)
        self.assertNotIn("QA_TARGET_BOUND", result.stdout)
        self.assertEqual(server.request_count, 1)
        queries = self.queries()
        self.assertEqual(len(queries), 2, queries)
        self.assertIn("public.lumira_qa_fixture_guard", queries[0])
        self.assertIn(session_id, queries[1])

    def test_binding_rejects_missing_cookie_without_querying_session_table(self):
        origin, server = self.start_csrf_server(
            "523e4567-e89b-42d3-a456-426614174000",
            set_cookie=False,
        )
        result = self.run_python(
            "import smoke_support; smoke_support.ensure_qa_target_binding()",
            self.environment(BASE_URL=origin),
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("session check failed", result.stderr)
        self.assertEqual(server.request_count, 1)
        queries = self.queries()
        self.assertEqual(len(queries), 1, queries)
        self.assertIn("public.lumira_qa_fixture_guard", queries[0])

    def test_binding_total_budget_stops_a_continuously_dripping_response(self):
        origin, server = self.start_csrf_server(
            "723e4567-e89b-42d3-a456-426614174000",
            drip_delay=0.02,
        )
        source = "\n".join([
            "import smoke_support",
            "smoke_support.TARGET_BINDING_HTTP_TIMEOUT_SECONDS = 0.2",
            "smoke_support.TARGET_BINDING_TOTAL_TIMEOUT_SECONDS = 0.12",
            "smoke_support.ensure_qa_target_binding()",
        ])
        started = time.monotonic()
        result = self.run_python(source, self.environment(BASE_URL=origin))
        elapsed = time.monotonic() - started
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("time budget", result.stderr)
        self.assertLess(elapsed, 0.8)
        self.assertEqual(server.request_count, 1)
        queries = self.queries()
        self.assertEqual(len(queries), 1, queries)
        self.assertIn("public.lumira_qa_fixture_guard", queries[0])

    def test_binding_timeout_is_bounded_and_does_not_reach_session_query(self):
        origin, server = self.start_csrf_server(
            "623e4567-e89b-42d3-a456-426614174000",
            delay=0.2,
        )
        source = "\n".join([
            "import smoke_support",
            "smoke_support.TARGET_BINDING_HTTP_TIMEOUT_SECONDS = 0.03",
            "smoke_support.ensure_qa_target_binding()",
        ])
        started = time.monotonic()
        result = self.run_python(source, self.environment(BASE_URL=origin))
        elapsed = time.monotonic() - started
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("time budget", result.stderr)
        self.assertLess(elapsed, 1.0)
        self.assertEqual(server.request_count, 1)
        queries = self.queries()
        self.assertEqual(len(queries), 1, queries)
        self.assertIn("public.lumira_qa_fixture_guard", queries[0])


if __name__ == "__main__":
    unittest.main()
