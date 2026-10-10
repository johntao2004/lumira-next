import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const scriptsPath = path.join(repositoryRoot, "scripts");
const targetSessionId = "123e4567-e89b-42d3-a456-426614174000";
const targetSessionCookie = `LUMIRA_NEXT_SESSION=${Buffer.from(targetSessionId).toString("base64")}`;

function temporaryConfig(directory) {
  const file = path.join(directory, "smoke-config.json");
  writeFileSync(
    file,
    JSON.stringify({
      LUMIRA_DB_URL: "jdbc:postgresql://127.0.0.1:6543/lumira_qa_fixture?sslmode=disable",
      LUMIRA_DB_USER: "fixture_user",
      LUMIRA_DB_PASSWORD: "fixture-only-password",
      LUMIRA_BOOTSTRAP_PASSWORD: "fixture-login-password",
      LUMIRA_QA_RUN_TOKEN: "123e4567-e89b-42d3-a456-426614174000",
    }),
  );
  chmodSync(file, 0o600);
  return file;
}

function fakePsql(directory) {
  const file = path.join(directory, "fake-psql.py");
  writeFileSync(
    file,
    "#!/usr/bin/env python3\nimport json, os, re, sys\nstatement=sys.stdin.read()\nif 'public.lumira_qa_fixture_guard' in statement:\n print(os.environ['LUMIRA_QA_RUN_TOKEN'] + '|isolated-security-qa')\nelif 'public.spring_session' in statement:\n match=re.search(r\"session_id\\s*=\\s*'([^']+)'\", statement)\n print('t' if match and match.group(1)==os.environ['LUMIRA_QA_TARGET_SESSION_ID'] else 'f')\nelse:\n print(json.dumps({'args':sys.argv[1:],'passwordConfigured':bool(os.environ.get('PGPASSWORD'))}))\n",
  );
  chmodSync(file, 0o700);
  return file;
}

function csrfSetCookie(acceptHeader, fallbackValue) {
  const value = acceptHeader === "application/json"
    ? targetSessionCookie
    : `SMOKE_SESSION=${fallbackValue}`;
  return `${value}; Path=/; HttpOnly; SameSite=Lax`;
}

function runPython(source, env) {
  return new Promise((resolve, reject) => {
    const process = spawn("python3", ["-c", source], { cwd: scriptsPath, env });
    let stdout = "";
    let stderr = "";
    process.stdout.setEncoding("utf8").on("data", (chunk) => (stdout += chunk));
    process.stderr.setEncoding("utf8").on("data", (chunk) => (stderr += chunk));
    process.on("error", reject);
    process.on("close", (status) => resolve({ status, stdout, stderr }));
  });
}

async function runWithHttpMock(handle, source) {
  const directory = mkdtempSync(path.join(tmpdir(), "lumira-smoke-http-mock-"));
  const configFile = temporaryConfig(directory);
  const psqlFile = fakePsql(directory);
  const requests = [];
  const server = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = Buffer.concat(chunks).toString("utf8");
    const summary = {
      method: request.method,
      path: request.url,
      accept: request.headers.accept ?? null,
      revision: request.headers["x-lumira-authorization-context-revision"] ?? null,
      preview: request.headers["x-lumira-role-preview-token"] ?? null,
      cookie: request.headers.cookie ?? null,
    };
    requests.push(summary);
    await handle(summary, body, response);
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const env = {
    ...process.env,
    LUMIRA_SMOKE_CONFIG: configFile,
    BASE_URL: `http://127.0.0.1:${address.port}/`,
    PSQL_BIN: psqlFile,
    LUMIRA_QA_RUN_TOKEN: "123e4567-e89b-42d3-a456-426614174000",
    LUMIRA_QA_TARGET_SESSION_ID: targetSessionId,
    PYTHONPATH: scriptsPath,
  };
  try {
    const result = await runPython(source, env);
    return { result, requests };
  } finally {
    await new Promise((resolve) => server.close(resolve));
    rmSync(directory, { recursive: true, force: true });
  }
}

test("shared smoke support reuses HTTP, cookies, CSRF, and configured database routing", async () => {
  const directory = mkdtempSync(path.join(tmpdir(), "lumira-smoke-support-"));
  const configFile = temporaryConfig(directory);
  const fakePsqlFile = fakePsql(directory);

  const requests = [];
  const server = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = Buffer.concat(chunks).toString("utf8");
    const summary = {
      method: request.method,
      path: request.url,
      accept: request.headers.accept ?? null,
      csrf: request.headers["x-smoke-csrf"] ?? null,
      revision: request.headers["x-lumira-authorization-context-revision"] ?? null,
      cookie: request.headers.cookie ?? null,
      bodyLength: body.length,
    };
    requests.push(summary);

    if (request.url === "/api/denied") {
      response.writeHead(403, { "Content-Type": "text/plain" });
      response.end("denied");
      return;
    }
    if (request.url === "/api/missing") {
      response.writeHead(404, { "Content-Type": "text/plain" });
      response.end("missing");
      return;
    }
    if (request.url === "/api/auth/csrf") {
      response.writeHead(200, {
        "Content-Type": "application/json",
        "Set-Cookie": csrfSetCookie(summary.accept, "fixture-session"),
      });
      response.end(JSON.stringify({ headerName: "X-Smoke-CSRF", token: "fixture-csrf-token" }));
      return;
    }
    if (request.url === "/api/auth/login") {
      const formIsExpected =
        body === "username=fixture-user&password=fixture-login-password" &&
        request.headers["content-type"] === "application/x-www-form-urlencoded";
      const csrfIsExpected = summary.csrf === "fixture-csrf-token";
      const cookieIsExpected = summary.cookie?.includes("SMOKE_SESSION=fixture-session");
      response.writeHead(formIsExpected && csrfIsExpected && cookieIsExpected ? 200 : 400);
      response.end();
      return;
    }
    if (request.url === "/api/auth/me") {
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(
        JSON.stringify({
          userId: "fixture-user-id",
          authorizationContext: { rolePreviewSupported: true, revision: "fixture-revision-1" },
        }),
      );
      return;
    }
    if (request.url === "/api/test/write") {
      let payloadMatches = false;
      try {
        payloadMatches = JSON.stringify(JSON.parse(body)) === '{"sample":"value"}';
      } catch {}
      const jsonIsExpected =
        request.method === "POST" &&
        request.headers["content-type"] === "application/json" &&
        payloadMatches;
      const csrfIsExpected = summary.csrf === "fixture-csrf-token";
      const cookieIsExpected = summary.cookie?.includes("SMOKE_SESSION=fixture-session");
      const revisionIsExpected = summary.revision === "fixture-revision-1";
      response.writeHead(
        jsonIsExpected && csrfIsExpected && cookieIsExpected && revisionIsExpected ? 204 : 400,
      );
      response.end();
      return;
    }
    response.writeHead(500);
    response.end();
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const baseURL = `http://127.0.0.1:${address.port}/`;
  try {
    const env = {
      ...process.env,
      LUMIRA_SMOKE_CONFIG: configFile,
      BASE_URL: baseURL,
      PSQL_BIN: fakePsqlFile,
      LUMIRA_QA_RUN_TOKEN: "123e4567-e89b-42d3-a456-426614174000",
      LUMIRA_QA_TARGET_SESSION_ID: targetSessionId,
      PYTHONPATH: scriptsPath,
    };
    const result = await runPython(
      [
        "import json",
        "from smoke_support import BASE_URL, call, client, config, config_path, cookie_jar, login, sql, write",
        "connection = client()",
        "denied = call(connection, '/denied')",
        "missing = call(connection, '/missing')",
        "login_status = login(connection, 'fixture-user', config['LUMIRA_BOOTSTRAP_PASSWORD'])",
        "write_result = write(connection, '/test/write', 'POST', {'sample': 'value'})",
        "psql_result = json.loads(sql('SELECT fixture'))",
        "print(json.dumps({'baseURL': BASE_URL, 'configPath': str(config_path()), 'denied': [denied[0], denied[1].decode()], 'missing': [missing[0], missing[1].decode()], 'login': login_status, 'write': [write_result[0], write_result[1].decode()], 'cookieCount': len(cookie_jar(connection)), 'psql': psql_result}))",
      ].join("\n"),
      env,
    );

    assert.equal(result.status, 0, `${result.stderr}\nFake HTTP requests: ${JSON.stringify(requests)}`);
    const output = JSON.parse(result.stdout);
    assert.equal(output.baseURL, baseURL.slice(0, -1));
    assert.equal(output.configPath, configFile);
    assert.deepEqual(output.denied, [403, "denied"]);
    assert.deepEqual(output.missing, [404, "missing"]);
    assert.equal(output.login, 200);
    assert.deepEqual(output.write, [204, ""]);
    assert.equal(output.cookieCount, 1);
    assert.equal(output.psql.passwordConfigured, true);
    assert.deepEqual(output.psql.args, [
      "-X",
      "-A",
      "-t",
      "-h",
      "127.0.0.1",
      "-p",
      "6543",
      "-U",
      "fixture_user",
      "-d",
      "lumira_qa_fixture",
      "-v",
      "ON_ERROR_STOP=1",
    ]);
    assert.deepEqual(
      requests.map(({ method, path: requestPath }) => [method, requestPath]),
      [
        ["GET", "/api/auth/csrf"],
        ["GET", "/api/denied"],
        ["GET", "/api/missing"],
        ["GET", "/api/auth/csrf"],
        ["POST", "/api/auth/login"],
        ["GET", "/api/auth/me"],
        ["GET", "/api/auth/csrf"],
        ["POST", "/api/test/write"],
      ],
    );
    assert.equal(requests[0].accept, "application/json");
    assert.equal(requests[0].cookie, null);
    assert.equal(requests[3].csrf, null);
    assert.equal(requests[3].cookie, null);
    assert.equal(requests[4].csrf, "fixture-csrf-token");
    assert.equal(requests[4].cookie, "SMOKE_SESSION=fixture-session");
    assert.equal(requests[1].revision, null);
    assert.equal(requests[2].revision, null);
    assert.equal(requests[5].revision, null);
    assert.equal(requests[6].csrf, null);
    assert.equal(requests[6].revision, null);
    assert.equal(requests[6].cookie, "SMOKE_SESSION=fixture-session");
    assert.equal(requests[7].csrf, "fixture-csrf-token");
    assert.equal(requests[7].revision, "fixture-revision-1");
    assert.equal(requests[7].cookie, "SMOKE_SESSION=fixture-session");
  } finally {
    await new Promise((resolve) => server.close(resolve));
    rmSync(directory, { recursive: true, force: true });
  }
});

test("smoke calls refresh and retry once only for an exact stale-context response", async () => {
  let revision = "revision-1";
  let staleSent = false;
  const { result, requests } = await runWithHttpMock(async (request, _body, response) => {
    if (request.path === "/api/auth/csrf") {
      response.writeHead(200, {
        "Content-Type": "application/json",
        "Set-Cookie": csrfSetCookie(request.accept, "fixture"),
      });
      response.end(JSON.stringify({ headerName: "X-Smoke-CSRF", token: "csrf" }));
      return;
    }
    if (request.path === "/api/auth/login") {
      response.writeHead(200);
      response.end();
      return;
    }
    if (request.path === "/api/auth/me") {
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(
        JSON.stringify({
          userId: "same-user",
          authorizationContext: { rolePreviewSupported: true, revision },
        }),
      );
      return;
    }
    if (request.path === "/api/retry") {
      if (!staleSent && request.revision === "revision-1") {
        staleSent = true;
        revision = "revision-2";
        response.writeHead(409, { "Content-Type": "application/problem+json" });
        response.end(JSON.stringify({ detail: "authorizationContextChanged" }));
      } else {
        response.writeHead(request.revision === "revision-2" ? 200 : 400);
        response.end("retried");
      }
      return;
    }
    if (request.path === "/api/generic-conflict") {
      response.writeHead(409, { "Content-Type": "application/problem+json" });
      response.end(JSON.stringify({ detail: "staleRecord" }));
      return;
    }
    if (request.path === "/api/unauthorized") {
      response.writeHead(401, { "Content-Type": "application/problem+json" });
      response.end(JSON.stringify({ detail: "authenticationRequired" }));
      return;
    }
    if (request.path === "/api/unavailable") {
      response.writeHead(503, { "Content-Type": "application/problem+json" });
      response.end(JSON.stringify({ detail: "unavailable" }));
      return;
    }
    if (request.path === "/api/explicit-revision" || request.path === "/api/explicit-preview") {
      response.writeHead(409, { "Content-Type": "application/problem+json" });
      response.end(JSON.stringify({ detail: "authorizationContextChanged" }));
      return;
    }
    response.writeHead(404);
    response.end();
  }, [
    "import json",
    "from smoke_support import call, client, login",
    "connection = client()",
    "login_status = login(connection, 'fixture-user', 'fixture-password')",
    "retried = call(connection, '/retry')",
    "generic = call(connection, '/generic-conflict')",
    "unauthorized = call(connection, '/unauthorized')",
    "unavailable = call(connection, '/unavailable')",
    "explicit_revision = call(connection, '/explicit-revision', headers={'X-Lumira-Authorization-Context-Revision': 'caller-revision'})",
    "explicit_preview = call(connection, '/explicit-preview', headers={'X-Lumira-Role-Preview-Token': 'caller-preview'})",
    "print(json.dumps({'login': login_status, 'retried': retried[0], 'generic': generic[0], 'unauthorized': unauthorized[0], 'unavailable': unavailable[0], 'explicitRevision': explicit_revision[0], 'explicitPreview': explicit_preview[0]}))",
  ].join("\n"));

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), {
    login: 200,
    retried: 200,
    generic: 409,
    unauthorized: 401,
    unavailable: 503,
    explicitRevision: 409,
    explicitPreview: 409,
  });
  assert.deepEqual(
    requests.filter((request) => request.path === "/api/retry").map((request) => request.revision),
    ["revision-1", "revision-2"],
  );
  assert.equal(requests.filter((request) => request.path === "/api/auth/me").length, 2);
  for (const path of ["/api/generic-conflict", "/api/unauthorized", "/api/unavailable"]) {
    assert.equal(requests.filter((request) => request.path === path).length, 1);
  }
  const explicitRevision = requests.find((request) => request.path === "/api/explicit-revision");
  const explicitPreview = requests.find((request) => request.path === "/api/explicit-preview");
  assert.equal(explicitRevision.revision, "caller-revision");
  assert.equal(explicitPreview.revision, null);
  assert.equal(explicitPreview.preview, "caller-preview");
  assert.equal(requests.filter((request) => request.path === "/api/explicit-revision").length, 1);
  assert.equal(requests.filter((request) => request.path === "/api/explicit-preview").length, 1);
});

test("a refreshed different user is rejected without retry and clears the stale session", async () => {
  let userId = "first-user";
  let revision = "first-revision";
  let switched = false;
  const { result, requests } = await runWithHttpMock(async (request, _body, response) => {
    if (request.path === "/api/auth/csrf") {
      response.writeHead(200, {
        "Content-Type": "application/json",
        "Set-Cookie": csrfSetCookie(request.accept, "first"),
      });
      response.end(JSON.stringify({ headerName: "X-Smoke-CSRF", token: "csrf" }));
      return;
    }
    if (request.path === "/api/auth/login") {
      response.writeHead(200);
      response.end();
      return;
    }
    if (request.path === "/api/auth/me") {
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(
        JSON.stringify({
          userId,
          authorizationContext: { rolePreviewSupported: true, revision },
        }),
      );
      return;
    }
    if (request.path === "/api/switch") {
      if (!switched) {
        switched = true;
        userId = "unexpected-user";
        revision = "unexpected-revision";
        response.writeHead(409, { "Content-Type": "application/problem+json" });
        response.end(JSON.stringify({ detail: "authorizationContextChanged" }));
      } else {
        response.writeHead(200);
        response.end();
      }
      return;
    }
    response.writeHead(200);
    response.end();
  }, [
    "import json",
    "from smoke_support import call, client, cookie_jar, login",
    "connection = client()",
    "login_status = login(connection, 'fixture-user', 'fixture-password')",
    "stale = call(connection, '/switch')",
    "after = call(connection, '/after-switch')",
    "print(json.dumps({'login': login_status, 'stale': stale[0], 'after': after[0], 'cookies': len(cookie_jar(connection))}))",
  ].join("\n"));

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), { login: 200, stale: 409, after: 200, cookies: 0 });
  assert.equal(requests.filter((request) => request.path === "/api/auth/me").length, 2);
  assert.deepEqual(
    requests.filter((request) => request.path === "/api/switch").map((request) => request.revision),
    ["first-revision"],
  );
  const after = requests.find((request) => request.path === "/api/after-switch");
  assert.equal(after.revision, null);
  assert.equal(after.cookie, null);
});

test("switching login identity refreshes revision and logout clears cached identity", async () => {
  let loginCount = 0;
  let userId = null;
  let username = null;
  const { result, requests } = await runWithHttpMock(async (request, body, response) => {
    if (request.path === "/api/auth/csrf") {
      loginCount += 1;
      response.writeHead(200, {
        "Content-Type": "application/json",
        "Set-Cookie": csrfSetCookie(request.accept, `csrf-${loginCount}`),
      });
      response.end(JSON.stringify({ headerName: "X-Smoke-CSRF", token: `csrf-${loginCount}` }));
      return;
    }
    if (request.path === "/api/auth/login") {
      username = new URLSearchParams(body).get("username");
      userId = `${username}-id`;
      response.writeHead(200, {
        "Set-Cookie": `SMOKE_SESSION=session-${username}; Path=/; HttpOnly`,
      });
      response.end();
      return;
    }
    if (request.path === "/api/auth/me") {
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(
        JSON.stringify({
          userId,
          authorizationContext: {
            rolePreviewSupported: true,
            revision: `revision-${username}`,
          },
        }),
      );
      return;
    }
    if (request.path === "/api/auth/logout") {
      response.writeHead(204, { "Set-Cookie": "SMOKE_SESSION=; Max-Age=0; Path=/; HttpOnly" });
      response.end();
      return;
    }
    response.writeHead(200);
    response.end();
  }, [
    "import json",
    "from smoke_support import call, client, cookie_jar, login",
    "connection = client()",
    "first = login(connection, 'first-user', 'password')",
    "first_call = call(connection, '/who-first')",
    "second = login(connection, 'second-user', 'password')",
    "second_call = call(connection, '/who-second')",
    "logout = call(connection, '/auth/logout', 'POST')",
    "after_logout = call(connection, '/after-logout')",
    "print(json.dumps({'first': first, 'firstCall': first_call[0], 'second': second, 'secondCall': second_call[0], 'logout': logout[0], 'afterLogout': after_logout[0], 'cookies': len(cookie_jar(connection))}))",
  ].join("\n"));

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), {
    first: 200,
    firstCall: 200,
    second: 200,
    secondCall: 200,
    logout: 204,
    afterLogout: 200,
    cookies: 0,
  });
  const protectedCalls = requests.filter((request) => ["/api/who-first", "/api/who-second"].includes(request.path));
  assert.deepEqual(protectedCalls.map((request) => request.revision), ["revision-first-user", "revision-second-user"]);
  const csrfCalls = requests.filter((request) => request.path === "/api/auth/csrf" && request.accept !== "application/json");
  assert.equal(csrfCalls.length, 2);
  assert.equal(csrfCalls[0].cookie, null);
  assert.equal(csrfCalls[1].cookie, null);
  const logout = requests.find((request) => request.path === "/api/auth/logout");
  assert.equal(logout.revision, null);
  assert.equal(logout.cookie, "SMOKE_SESSION=session-second-user");
  const afterLogout = requests.find((request) => request.path === "/api/after-logout");
  assert.equal(afterLogout.revision, null);
  assert.equal(afterLogout.cookie, null);
});

test("smoke support refuses to fall back to the legacy local config", async () => {
  const env = { ...process.env, PYTHONPATH: scriptsPath };
  delete env.LUMIRA_SMOKE_CONFIG;
  const result = await runPython("import smoke_support", env);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /explicit private QA config; refusing the legacy local config/);
});
