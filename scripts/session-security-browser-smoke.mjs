import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomBytes, randomUUID } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium, expect } from "@playwright/test";
import { assertQaRunGuard } from "./qa-run-guard.mjs";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const configuredPath = process.env.LUMIRA_SMOKE_CONFIG;
const baseURL = process.env.BASE_URL;
if (!configuredPath || !baseURL) {
  throw new Error("Set LUMIRA_SMOKE_CONFIG to a disposable local QA config and BASE_URL to its local web origin.");
}
const configPath = path.isAbsolute(configuredPath) ? configuredPath : path.resolve(projectRoot, configuredPath);
const legacyConfigPath = path.join(projectRoot, ".runtime", "local.json");
assert.notEqual(configPath, legacyConfigPath, "Refusing to read or write using the real local.json config");
assert.ok(existsSync(configPath), "The explicitly configured QA file must exist");
const parsedBase = new URL(baseURL);
assert.ok(["127.0.0.1", "localhost", "::1"].includes(parsedBase.hostname), "Browser smoke only targets a local app");

const config = JSON.parse(readFileSync(configPath, "utf8"));
assertQaRunGuard({ projectRoot, configPath, config, baseURL });
const databaseUrl = new URL(String(config.LUMIRA_DB_URL ?? "").replace(/^jdbc:/, ""));
const databaseName = decodeURIComponent(databaseUrl.pathname.replace(/^\//, ""));
assert.ok(["127.0.0.1", "localhost", "::1"].includes(databaseUrl.hostname), "Fixture SQL only targets a local PostgreSQL host");
assert.match(databaseName, /^lumira_(?:security|qa|test|smoke)_[a-z0-9_]+$/, "Fixture SQL requires the dedicated Lumira QA database namespace");

const username = process.env.LUMIRA_SMOKE_USERNAME
  ?? config.LUMIRA_SMOKE_USERNAME
  ?? config.LUMIRA_BOOTSTRAP_USERNAME
  ?? config.username
  ?? "admin";
const password = config.LUMIRA_BOOTSTRAP_PASSWORD ?? config.bootstrapPassword ?? config.password;
if (typeof password !== "string" || !password) throw new Error("The QA config must provide a bootstrap password.");
const suffix = randomBytes(5).toString("hex");
const fixtureId = randomUUID();
const fixtureUsername = `qa_session_${suffix}`;
const fixtureDisplayName = `QA Session B ${suffix}`;
const draftUsername = `qa_draft_${suffix}`;
const draftDisplayName = `Unsubmitted A draft ${suffix}`;
const sqlQuote = value => `'${String(value).replaceAll("'", "''")}'`;

function qaSql(statement) {
  return execFileSync("python3", ["-c", "import sys; from smoke_support import sql; print(sql(sys.stdin.read()))"], {
    cwd: path.join(projectRoot, "scripts"),
    env: { ...process.env, LUMIRA_SMOKE_CONFIG: configPath, BASE_URL: baseURL },
    input: statement,
    encoding: "utf8",
    stdio: ["pipe", "pipe", "pipe"],
  }).trim();
}

async function loginThroughUi(page, accountName, accountPassword) {
  await page.goto("/user/login");
  await page.getByLabel("用户名", { exact: true }).fill(accountName);
  await page.getByLabel("密码", { exact: true }).fill(accountPassword);
  const identityResponse = page.waitForResponse(response => new URL(response.url()).pathname === "/api/auth/me" && response.status() === 200);
  await Promise.all([
    page.waitForURL("**/dashboard/**", { timeout: 30_000 }),
    page.getByRole("button", { name: /登\s*录/ }).click(),
  ]);
  await expect(page.locator(".pro-account-trigger")).toBeVisible();
  return (await identityResponse).json();
}

let fixtureCreated = false;
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
let pageA;
let pageB;
const pageErrors = [];
const accountAWrites = [];

try {
  await context.addCookies([{
    name: "lumira-next.preferences",
    value: encodeURIComponent(JSON.stringify({ dark: true, locale: "zh-CN" })),
    url: baseURL,
  }]);
  await context.addInitScript(() => {
    const state = {
      armedPaths: new Set(),
      heldSummaries: Object.create(null),
      heldSignals: Object.create(null),
      releaseResolvers: Object.create(null),
      releasedResponses: Object.create(null),
      requestCounts: Object.create(null),
      storageNonceEvents: 0,
      sessionRevalidateEvents: 0,
      requestCountAtRevalidate: 0,
      arm(path) { this.armedPaths.add(path); this.heldSummaries[path] = null; return true; },
      release(path) {
        const resolve = this.releaseResolvers[path];
        if (!resolve) return false;
        delete this.releaseResolvers[path];
        resolve();
        return true;
      },
    };
    Object.defineProperty(window, "__lumiraSessionSmoke", { value: state });
    window.addEventListener("storage", event => {
      if (event.key === "lumira:session-invalidated" && event.newValue) state.storageNonceEvents++;
    });
    const originalDispatchEvent = window.dispatchEvent.bind(window);
    window.dispatchEvent = event => {
      if (event.type === "lumira:session-revalidate") {
        state.sessionRevalidateEvents++;
        state.requestCountAtRevalidate = state.requestCounts["/api/auth/me"] ?? 0;
      }
      return originalDispatchEvent(event);
    };
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (input, init = {}) => {
      const requestUrl = new URL(typeof input === "string" ? input : input.url, window.location.href);
      const method = String(init.method ?? (typeof input === "object" && "method" in input ? input.method : "GET")).toUpperCase();
      const holdKey = requestUrl.pathname;
      state.requestCounts[holdKey] = (state.requestCounts[holdKey] ?? 0) + 1;
      if (!state.armedPaths.has(holdKey) || method !== "GET") return originalFetch(input, init);
      state.armedPaths.delete(holdKey);
      state.heldSignals[holdKey] = init.signal ?? null;
      // The browser gets a genuine QA API Response. Only this wrapper ignores the
      // app's AbortSignal so the application must reject the late result itself.
      const response = await originalFetch(input, { ...init, signal: undefined });
      let payload;
      try { payload = await response.clone().json(); } catch { payload = null; }
      state.heldSummaries[holdKey] = {
        status: response.status,
        userId: typeof payload?.userId === "string" ? payload.userId : null,
        usernames: Array.isArray(payload?.items) ? payload.items.map(item => item?.username).filter(value => typeof value === "string") : [],
      };
      window.dispatchEvent(new CustomEvent("lumira:session-smoke-response-held", { detail: holdKey }));
      await new Promise(resolve => { state.releaseResolvers[holdKey] = resolve; });
      state.releasedResponses[holdKey] = true;
      return response;
    };
  });

  const createFixture = `INSERT INTO platform_user(id,username,display_name,password_hash,enabled)
    SELECT ${sqlQuote(fixtureId)},${sqlQuote(fixtureUsername)},${sqlQuote(fixtureDisplayName)},password_hash,true
    FROM platform_user WHERE username=${sqlQuote(username)} RETURNING id;`;
  const fixtureOutput = qaSql(createFixture);
  fixtureCreated = fixtureOutput.split(/\r?\n/).includes(fixtureId);
  assert.equal(fixtureCreated, true, "QA B fixture must be cloned from the configured bootstrap identity");

  pageA = await context.newPage();
  pageA.on("pageerror", error => pageErrors.push(error.message));
  pageA.on("request", request => {
    const requestUrl = new URL(request.url());
    if (requestUrl.pathname === "/api/platform/users" && request.method() !== "GET") {
      accountAWrites.push(`${request.method()} ${requestUrl.pathname}`);
    }
  });
  const identityA = await loginThroughUi(pageA, username, password);
  assert.equal(typeof identityA.displayName, "string", "/auth/me must return the display name shown in the account menu");
  await pageA.goto("/dashboard/users");
  assert.equal(typeof identityA.userId, "string", "/auth/me must return a stable userId");
  assert.ok(identityA.userId.length > 0);
  await expect(pageA.getByRole("row").filter({ hasText: username })).toBeVisible();
  await expect.poll(() => pageA.evaluate(() => ({
    language: document.documentElement.lang,
    theme: document.documentElement.dataset.theme,
  }))).toEqual({ language: "zh-CN", theme: "dark" });

  const sameUserRefresh = pageA.waitForResponse(response => new URL(response.url()).pathname === "/api/auth/me" && response.status() === 200);
  await pageA.reload();
  const refreshedIdentity = await (await sameUserRefresh).json();
  assert.equal(refreshedIdentity.userId, identityA.userId, "same-account refresh must preserve the stable identity key");
  await expect(pageA.getByRole("row").filter({ hasText: username })).toBeVisible();
  await expect.poll(() => pageA.evaluate(() => ({
    language: document.documentElement.lang,
    theme: document.documentElement.dataset.theme,
  }))).toEqual({ language: "zh-CN", theme: "dark" });

  pageB = await context.newPage();
  pageB.on("pageerror", error => pageErrors.push(error.message));
  await pageB.goto("/dashboard/home");
  await expect(pageB.locator(".pro-account-trigger")).toContainText(identityA.displayName);

  // Hold an actual A /auth/me refresh and users-list response at the browser
  // transport after the local server has produced each real Response.
  const heldMePath = "/api/auth/me";
  const heldUsersPath = "/api/platform/users";
  await pageA.evaluate(path => window.__lumiraSessionSmoke.arm(path), heldMePath);
  await pageA.evaluate(() => window.dispatchEvent(new Event("lumira:session-revalidate")));
  await pageA.waitForFunction(path => window.__lumiraSessionSmoke.heldSummaries[path] !== null, heldMePath, { timeout: 30_000 });
  const heldMeResponse = await pageA.evaluate(path => window.__lumiraSessionSmoke.heldSummaries[path], heldMePath);
  assert.equal(heldMeResponse.status, 200, "held /auth/me must be a real A-authenticated response");
  assert.equal(heldMeResponse.userId, identityA.userId, "held /auth/me response must capture A before the cookie changes");

  await pageA.evaluate(path => window.__lumiraSessionSmoke.arm(path), heldUsersPath);
  await pageA.locator(".ant-pro-table-list-toolbar .anticon-reload").click();
  await pageA.waitForFunction(path => window.__lumiraSessionSmoke.heldSummaries[path] !== null, heldUsersPath, { timeout: 30_000 });
  const heldUsersResponse = await pageA.evaluate(path => window.__lumiraSessionSmoke.heldSummaries[path], heldUsersPath);
  assert.equal(heldUsersResponse.status, 200, "held users result must be the real A-authenticated response");
  assert.ok(heldUsersResponse.usernames.includes(username), "held users response must contain A's user-list data");

  await expect(pageA.getByRole("button", { name: "新增用户", exact: true })).toBeVisible();
  await pageA.getByRole("button", { name: "新增用户", exact: true }).click();
  const draft = pageA.locator("#user-editor");
  await expect(draft).toBeVisible();
  await draft.locator('input[id$="_username"]').fill(draftUsername);
  await draft.locator('input[id$="_displayName"]').fill(draftDisplayName);
  assert.equal(await draft.locator('input[id$="_displayName"]').inputValue(), draftDisplayName);

  await pageB.locator(".pro-account-trigger").click();
  await Promise.all([
    pageB.waitForURL(url => url.pathname === "/user/login", { timeout: 30_000 }),
    pageA.waitForURL(url => url.pathname === "/user/login", { timeout: 30_000 }),
    pageB.getByRole("menuitem", { name: "退出", exact: true }).click(),
  ]);
  await pageA.waitForFunction(() => {
    const state = window.__lumiraSessionSmoke;
    return state.sessionRevalidateEvents >= 2
      && (state.requestCounts["/api/auth/me"] ?? 0) > state.requestCountAtRevalidate;
  }, { timeout: 30_000 });
  assert.equal(await pageA.evaluate(() => window.__lumiraSessionSmoke.heldSummaries["/api/auth/me"]?.userId), identityA.userId,
    "storage revalidation must issue a fresh /auth/me request instead of reusing the held A query promise");
  await expect(pageA.locator("#user-editor")).toHaveCount(0);
  assert.equal(await pageA.locator(".ant-table-row").count(), 0, "A-only rows must be gone after cross-tab logout");
  const abortedAtLogout = await pageA.evaluate(paths => paths.map(path => window.__lumiraSessionSmoke.heldSignals[path]?.aborted === true), [heldMePath, heldUsersPath]);
  assert.deepEqual(abortedAtLogout, [true, true], "session boundary must abort both old app request signals");
  const logoutNonceCount = await pageA.evaluate(() => window.__lumiraSessionSmoke.storageNonceEvents);
  assert.ok(logoutNonceCount >= 1, "A must observe the real logout nonce from the other tab");
  assert.deepEqual(accountAWrites, [], "the unsaved A draft must not have issued a write");

  await loginThroughUi(pageB, fixtureUsername, password);
  await expect.poll(() => pageA.evaluate(() => window.__lumiraSessionSmoke.storageNonceEvents)).toBeGreaterThan(logoutNonceCount);
  for (const path of [heldMePath, heldUsersPath]) {
    assert.equal(await pageA.evaluate(path => window.__lumiraSessionSmoke.release(path), path), true, `late A response ${path} must be released after B login`);
  }
  for (const path of [heldMePath, heldUsersPath]) {
    await pageA.waitForFunction(path => window.__lumiraSessionSmoke.releasedResponses[path] === true, path, { timeout: 10_000 });
  }
  assert.equal(new URL(pageA.url()).pathname, "/user/login", "late A response must not restore protected content after logout");
  assert.equal(await pageA.locator(".ant-table-row").count(), 0);
  assert.deepEqual(accountAWrites, [], "late A response must not submit the discarded draft");

  const identityBResponse = pageA.waitForResponse(response => new URL(response.url()).pathname === "/api/auth/me" && response.status() === 200);
  await pageA.goto("/dashboard/home");
  const identityB = await (await identityBResponse).json();
  assert.equal(identityB.userId, fixtureId, "the app must establish B from the server identity after cross-tab relogin");
  assert.notEqual(identityB.userId, identityA.userId, "account switch must change the session identity key");
  await expect(pageA.locator(".pro-account-trigger")).toContainText(fixtureDisplayName);

  await pageA.goto("/dashboard/users");
  await expect(pageA.getByText("403", { exact: true })).toBeVisible();
  assert.equal(await pageA.locator(".ant-table-row").count(), 0, "B without users permission must not receive or display A's table data");
  await expect(pageA.locator("#user-editor")).toHaveCount(0);
  assert.deepEqual(accountAWrites, [], "account B must not submit the old A form draft");
  assert.deepEqual(pageErrors, []);
  console.log("PASS: real app double-tab A logout to anonymous to B login, stable same-user reload, preference persistence, stale real users response suppression, A draft discarded, B denied A-only users data");
} finally {
  if (pageA) {
    try {
      await pageA.evaluate(() => {
        for (const path of Object.keys(window.__lumiraSessionSmoke.releaseResolvers)) {
          window.__lumiraSessionSmoke.release(path);
        }
      });
    } catch {}
  }
  await context.close();
  await browser.close();
  if (fixtureCreated) {
    qaSql(`DELETE FROM platform_audit WHERE actor=${sqlQuote(fixtureUsername)} OR target_id=${sqlQuote(fixtureId)};
      DELETE FROM spring_session WHERE principal_name=${sqlQuote(fixtureUsername)};
      DELETE FROM platform_user WHERE id=${sqlQuote(fixtureId)} AND username=${sqlQuote(fixtureUsername)};`);
  }
}
