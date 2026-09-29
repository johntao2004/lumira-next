import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { chromium, expect } from "@playwright/test";
import { messages } from "../apps/web/src/platform/i18n/messages.ts";

const config = JSON.parse(readFileSync(".runtime/local.json", "utf8"));
const t = messages["zh-CN"];
const title = `QA platform notice ${Date.now()}`;
const body = "This notification is created by the platform browser smoke.";
const sql = (statement) => execFileSync("python3", ["-c", "import sys; from smoke_support import sql; sql(sys.stdin.read())"], { cwd: "scripts", input: statement, stdio: ["pipe", "pipe", "pipe"] });
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: "http://127.0.0.1:8010", viewport: { width: 1213, height: 886 } });
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
  await page.getByLabel(t.notificationTitle, { exact: true }).fill(title);
  await page.getByLabel(t.notificationBody, { exact: true }).fill(body);
  const publish = page.waitForResponse((response) => response.url().endsWith("/api/platform/notifications") && response.request().method() === "POST");
  await page.getByRole("button", { name: t.save, exact: true }).click();
  assert.equal((await publish).status(), 201);
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
  sql(`DELETE FROM platform_notification WHERE params->>'title' = '${title.replaceAll("'", "''")}'; DELETE FROM platform_audit WHERE action='notification.broadcast' AND target_id NOT IN (SELECT id FROM platform_notification);`);
  await context.close();
  await browser.close();
}
