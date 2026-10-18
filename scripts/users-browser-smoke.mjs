import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { chromium } from "@playwright/test";
import { assertQaRunGuard } from "./qa-run-guard.mjs";

const projectRoot = process.cwd();
if (!process.env.LUMIRA_SMOKE_CONFIG) throw new Error("Set an explicit private QA config before running user export checks.");
const configPath = path.resolve(process.env.LUMIRA_SMOKE_CONFIG);
const config = JSON.parse(readFileSync(configPath, "utf8"));
const baseURL = process.env.BASE_URL ?? "http://127.0.0.1:8017";
assertQaRunGuard({ projectRoot, configPath, config, baseURL });
const username = process.env.LUMIRA_SMOKE_USERNAME ?? config.LUMIRA_SMOKE_USERNAME ?? config.LUMIRA_QA_AUTH_ADMIN_USERNAME ?? "admin";
const password = process.env.LUMIRA_SMOKE_PASSWORD ?? config.LUMIRA_SMOKE_PASSWORD ?? config.LUMIRA_QA_ADMIN_PASSWORD ?? config.LUMIRA_QA_AUTH_ADMIN_PASSWORD;
if (!password) throw new Error("Provide the QA account's current password in the private QA config or environment.");
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL });
try {
  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  const login = await context.request.post("/api/auth/login", {
    form: { username, password },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(login.status(), 200);
  assert.equal((await login.json()).authenticated, true, "Complete the QA account's required login steps first.");
  const identity = await (await context.request.get("/api/auth/me")).json();
  const authorizationHeaders = identity.authorizationContext?.revision
    ? { "X-Lumira-Authorization-Context-Revision": identity.authorizationContext.revision }
    : {};

  const exportToken = await (await context.request.get("/api/auth/csrf")).json();
  const response = await context.request.post("/api/platform/users/export.xlsx", {
    headers: { ...authorizationHeaders, [exportToken.headerName]: exportToken.token },
    data: { scope: "selected", page: 1, pageSize: 10, userIds: [identity.userId], fields: ["username", "displayName"] },
    timeout: 120000,
  });
  assert.equal(response.status(), 200);
  assert.match(response.headers()["content-type"] ?? "", /spreadsheetml/i);
  assert.match(response.headers()["content-disposition"] ?? "", /users\.xlsx/);
  const body = await response.body();
  assert.equal(body.subarray(0, 2).toString("ascii"), "PK", "The output must be an XLSX ZIP package.");
  const empty = await context.request.post("/api/platform/users/export.xlsx", {
    headers: { ...authorizationHeaders, [exportToken.headerName]: exportToken.token },
    data: { scope: "filtered", page: 1, pageSize: 10, search: "qa-no-such-user-40b2b12d", fields: ["username"] },
    timeout: 120000,
  });
  assert.equal(empty.status(), 200);
  assert.equal((await empty.body()).subarray(0, 2).toString("ascii"), "PK");

  const page = await context.newPage();
  await page.goto("/dashboard/users");
  const exportButton = page.getByRole("button", { name: /导出 XLSX|Export XLSX/i, exact: true });
  await exportButton.waitFor({ timeout: 60000 });
  await exportButton.click();
  const drawer = page.getByRole("dialog").filter({ has: page.getByText(/导出用户到 Excel|Export users to Excel/i, { exact: true }) });
  await drawer.waitFor();
  const submit = drawer.getByRole("button", { name: /导出 XLSX|Export XLSX/i, exact: true });
  await submit.waitFor();
  const downloadPromise = page.waitForEvent("download");
  await submit.click();
  const download = await downloadPromise;
  assert.equal(await download.failure(), null);
  assert.equal(download.suggestedFilename(), "users.xlsx");
  console.log("users export PASS: guarded QA target, authenticated XLSX, scoped selection, empty result and UI download");
} finally {
  await browser.close();
}
