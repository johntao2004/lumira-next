import { loadQaRunContext } from './qa-run-guard.mjs';
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { chromium, expect } from "@playwright/test";
import { loadLocaleMessages } from "../apps/web/src/platform/i18n/messages.ts";

const { config, baseURL } = loadQaRunContext();
const t = await loadLocaleMessages("zh-CN");
const title = `QA platform notice ${Date.now()}`;
let broadcastId;
const body = "This notification is created by the platform browser smoke.";
const sql = (statement) => execFileSync("python3", ["-c", "import sys; from smoke_support import sql; sql(sys.stdin.read())"], { cwd: "scripts", input: statement, stdio: ["pipe", "pipe", "pipe"] });
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: baseURL, viewport: { width: 1213, height: 886 } });
try {
  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  const login = await context.request.post("/api/auth/login", {
    form: { username: "admin", password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(login.status(), 200, await login.text());

  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/dashboard/notifications");
  await expect(page.getByRole("heading", { name: t.notificationCenter, exact: true })).toBeVisible();
  await page.getByRole("button", { name: t.publishNotification, exact: true }).click();
  const editor = page.locator("#notification-editor");
  await editor.getByLabel(t.notificationTarget, { exact: true }).click();
  await page.getByText(t.notificationSelectedUsers, { exact: true }).last().click();
  await editor.getByLabel(t.notificationRecipients, { exact: true }).click();
  await page.locator(".ant-select-item-option").filter({ hasText: "(admin)" }).click();
  await editor.getByLabel(t.notificationTitle, { exact: true }).fill(title);
  await editor.getByLabel(t.notificationBody, { exact: true }).fill(body);
  const publish = page.waitForResponse((response) => response.url().endsWith("/api/platform/notifications") && response.request().method() === "POST");
  await page.getByRole("button", { name: t.save, exact: true }).click();
  const published = await publish;
  assert.equal(published.status(), 201);
  const result = await published.json();
  broadcastId = result.broadcastId;
  assert.match(broadcastId, /^[a-f0-9-]{36}$/);
  assert.equal(result.recipientCount, 1);
  const row = page.getByRole("row").filter({ hasText: title });
  await expect.poll(() => row.count()).toBeGreaterThan(0);
  for (const notificationRow of await row.all()) await expect(notificationRow).toContainText(body);
  const list = await context.request.get("/api/platform/notifications?page=1&pageSize=20&search=");
  assert.equal(list.status(), 200, await list.text());
  const entries = await list.json();
  assert.ok(entries.items.some((item) => item.params.includes(title)));
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log("PASS: admin notification publish uses shared form/table, reaches the authenticated inbox store, and fits desktop viewport");
} finally {
  sql(`DELETE FROM platform_notification WHERE params->>'title' = '${title.replaceAll("'", "''")}'; ${broadcastId ? `DELETE FROM platform_audit WHERE action='notification.broadcast' AND target_id='${broadcastId}';` : ''}`);
  await context.close();
  await browser.close();
}
