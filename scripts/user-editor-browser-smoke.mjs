import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { chromium, expect } from "@playwright/test";
import { observeResponse } from "./browser-support.mjs";

const config = JSON.parse(readFileSync(process.env.LUMIRA_QA_CONFIG ?? ".runtime/local.json", "utf8"));
const baseURL = process.env.LUMIRA_BASE_URL ?? "http://127.0.0.1:8010";
const username = `qa_editor_${randomBytes(5).toString("hex")}`;
const password = `Aa!9${randomBytes(20).toString("base64url")}`;
const nextPassword = `Bb!8${randomBytes(20).toString("base64url")}`;
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const admin = await browser.newContext({ baseURL });
let userId;
async function login(context, name, secret) {
  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  assert.equal((await context.request.post("/api/auth/login", { form: { username: name, password: secret }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
}
try {
  await login(admin, "admin", config.LUMIRA_BOOTSTRAP_PASSWORD);
  const page = await admin.newPage();
  await page.goto("/dashboard/users");
  await page.getByRole("button", { name: "新增用户", exact: true }).click();
  const form = page.locator("#user-editor");
  await form.locator('[id$="_username"]').fill(username);
  await form.locator('[id$="_displayName"]').fill(username);
  await form.locator('[id$="_password"]').fill(password);
  const create = observeResponse(page, response => new URL(response.url()).pathname === "/api/platform/users" && response.request().method() === "POST");
  await page.locator('button[form="user-editor"]').click();
  const created = await create;
  assert.equal(created.status(), 201);
  userId = (await created.json()).id;
  await form.waitFor({ state: "hidden" });
  await page.reload();
  const row = page.getByRole("row").filter({ hasText: username });
  await row.getByRole("button", { name: "编辑", exact: true }).click();
  await form.locator('[id$="_displayName"]').fill(`${username}_updated`);
  const update = observeResponse(page, response => new URL(response.url()).pathname === `/api/platform/users/${userId}/editor` && response.request().method() === "PUT");
  await page.locator('button[form="user-editor"]').click();
  assert.equal((await update).status(), 200);
  await form.waitFor({ state: "hidden" });
  await page.reload();
  await expect(page.getByRole("row").filter({ hasText: `${username}_updated` })).toBeVisible();

  const account = await browser.newContext({ baseURL });
  await login(account, username, password);
  const own = await account.newPage();
  await own.goto("/dashboard/home");
  await own.locator(".pro-account-trigger").click();
  await own.getByRole("menuitem", { name: "个人资料", exact: true }).click();
  await own.locator('#account-profile input[id="account-profile_displayName"]').fill(`${username}_profile`);
  const profile = observeResponse(own, response => new URL(response.url()).pathname === "/api/account/profile" && response.request().method() === "PUT");
  await own.locator('#account-profile button[type="submit"]').click();
  assert.equal((await profile).status(), 200);
  await expect(own.locator("#account-profile")).toBeVisible();
  await expect(own.locator("#account-profile_displayName")).toHaveValue(`${username}_profile`);
  await own.waitForURL("**/dashboard/account-profile");
  await own.reload();
  await expect(own.locator(".pro-account-trigger")).toContainText(`${username}_profile`);

  await own.locator(".pro-account-trigger").click();
  await own.getByRole("menuitem", { name: "修改密码", exact: true }).click();
  await own.waitForURL("**/dashboard/account-password");
  const secretForm = own.locator("#account-password");
  await secretForm.locator('#account-password_currentPassword').fill("WrongCurrentPassword!9");
  await secretForm.locator('#account-password_newPassword').fill(nextPassword);
  await secretForm.locator('#account-password_confirmation').fill(nextPassword);
  const rejected = observeResponse(own, response => new URL(response.url()).pathname === "/api/account/password" && response.request().method() === "POST");
  const passwordSubmit = secretForm.locator('button[type="submit"]');
  await passwordSubmit.click();
  assert.equal((await rejected).status(), 400);
  await expect(passwordSubmit).toBeEnabled();
  await secretForm.locator('#account-password_currentPassword').fill(password);
  const changed = observeResponse(own, response => new URL(response.url()).pathname === "/api/account/password" && response.request().method() === "POST");
  await passwordSubmit.click();
  assert.equal((await changed).status(), 204);
  await own.waitForURL("**/user/login");
  await login(account, username, nextPassword);
  console.log("user editor/account PASS: create/edit/profile persistence, password error retry, session invalidation and new-password login");
  await account.close();
} finally {
  await browser.close();
  if (!process.env.LUMIRA_QA_CONFIG && userId) {
    assert.match(userId, /^[a-f0-9-]{36}$/);
    execFileSync("python3", ["-c", "import sys; from smoke_support import sql; sql(sys.stdin.read())"], { cwd: "scripts", input: `DELETE FROM platform_audit WHERE target_id='${userId}'; DELETE FROM spring_session WHERE principal_name='${username}'; DELETE FROM platform_user WHERE id='${userId}';`, stdio: ["pipe", "pipe", "pipe"] });
  }
}
