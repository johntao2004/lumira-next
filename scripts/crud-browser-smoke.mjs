import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { chromium, expect } from "@playwright/test";
import { observeResponse } from "./browser-support.mjs";

const config = JSON.parse(readFileSync(process.env.LUMIRA_QA_CONFIG ?? ".runtime/local.json", "utf8"));
const baseURL = process.env.LUMIRA_BASE_URL ?? "http://127.0.0.1:8010";
const prefix = `qa_crud_${randomBytes(5).toString("hex")}`;
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
const page = await context.newPage();
const created = [];
const pageErrors = [];
page.on("pageerror", error => pageErrors.push(error.message));
const csrf = async () => {
  const value = await (await context.request.get("/api/auth/csrf")).json();
  return { [value.headerName]: value.token };
};
const write = async (url, method, data) => context.request.fetch(`/api${url}`, { method, data, headers: await csrf() });
const saved = (endpoint, method) => observeResponse(page, response => new URL(response.url()).pathname === `/api${endpoint}` && response.request().method() === method);

try {
  assert.equal((await context.request.post("/api/auth/login", { form: { username: "admin", password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: await csrf() })).status(), 200);
  for (const item of [
    { route: "dictionaries", endpoint: "/platform/dictionaries", create: "新增字典", form: "dictionary-editor" },
    { route: "organizations", endpoint: "/platform/organizations", create: "新增组织", form: "organization-editor" },
    { route: "roles", endpoint: "/platform/roles", create: "新增角色", form: "role-editor" },
  ]) {
    const code = `${prefix}_${item.route}`;
    await page.goto(`/dashboard/${item.route}`);
    await page.getByRole("button", { name: item.create, exact: true }).click();
    const drawer = page.getByRole("dialog").last();
    const form = page.locator(`#${item.form}`);
    await form.locator('input[id="code"], input[id$="_code"]').fill(code);
    await form.locator('input[id="name"], input[id$="_name"]').fill(code);
    const button = drawer.locator(`button[form="${item.form}"]`).and(drawer.getByRole("button", { name: /^保\s*存$/ }));

    // Fail before committing, then retry the same editor. The entered data must survive.
    const url = `**/api${item.endpoint}`;
    const reject = route => route.request().method() === "POST"
      ? route.fulfill({ status: 409, contentType: "application/json", body: '{"detail":"staleRecord"}' }) : route.continue();
    await page.route(url, reject);
    const failed = saved(item.endpoint, "POST");
    await button.click();
    assert.equal((await failed).status(), 409);
    await expect(button).toBeEnabled();
    assert.equal(await form.locator('input[id="name"], input[id$="_name"]').inputValue(), code);
    await page.unroute(url, reject);

    let release;
    const gate = new Promise(resolve => { release = resolve; });
    let submissions = 0;
    const hold = async route => {
      if (route.request().method() !== "POST") return route.continue();
      submissions++;
      await gate;
      await route.continue();
    };
    await page.route(url, hold);
    const createdResponse = saved(item.endpoint, "POST");
    try {
      await button.click();
      await expect(button).toHaveAttribute("aria-busy", "true");
      await expect(button).toBeDisabled();
      await form.evaluate(element => {
        element.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
        element.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
      });
      await expect(drawer.getByRole("button", { name: /^取\s*消$/ })).toBeDisabled();
    } finally { release(); }
    const response = await createdResponse;
    assert.equal(response.status(), 201);
    const record = await response.json();
    created.push({ endpoint: item.endpoint, id: record.id });
    await drawer.waitFor({ state: "hidden" });
    await page.unroute(url, hold);
    assert.equal(submissions, 1, "duplicate form submissions must share one operation");
    await page.reload();
    const row = page.getByRole("row").filter({ hasText: code });
    await row.waitFor();
    await row.getByRole("button", { name: "编辑", exact: true }).click();
    await form.locator('input[id="name"], input[id$="_name"]').fill(`${code}_updated`);

    // Role-specific confirmation remains cancellable and must not write on cancellation.
    if (item.route === "roles") {
      await form.locator('[id$="fileQuotaStorageGiB"]').fill("1");
      let writes = 0;
      const onRequest = request => { if (request.method() === "PUT" && new URL(request.url()).pathname === `/api${item.endpoint}/${record.id}`) writes++; };
      page.on("request", onRequest);
      await button.click();
      const confirmation = page.locator(".ant-modal-confirm");
      await confirmation.getByRole("button", { name: /^取\s*消$/ }).click();
      await confirmation.waitFor({ state: "hidden" });
      await expect(button).toBeEnabled();
      assert.equal(writes, 0);
      page.off("request", onRequest);
      await form.locator('[id$="fileQuotaStorageGiB"]').fill("");
    }
    const update = saved(`${item.endpoint}/${record.id}`, "PUT");
    await button.click();
    assert.equal((await update).status(), 200);
    await drawer.waitFor({ state: "hidden" });
    await page.reload();
    const updated = page.getByRole("row").filter({ hasText: `${code}_updated` });
    await updated.waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
    await updated.getByRole("button", { name: "删除", exact: true }).click();
    const deleted = saved(`${item.endpoint}/${record.id}`, "DELETE");
    await page.locator(".ant-popconfirm").getByRole("button", { name: "删除", exact: true }).click();
    assert.equal((await deleted).status(), 204);
    await expect(updated).toHaveCount(0);
    console.log(`${item.route} PASS: error/retry, single submission, create/edit/delete, reload persistence${item.route === "roles" ? ", quota confirmation cancellation" : ""}`);
  }
  assert.deepEqual(pageErrors, []);
} catch (error) {
  mkdirSync(".runtime/crud-naming-review/browser-failures", { recursive: true });
  await page.screenshot({ path: ".runtime/crud-naming-review/browser-failures/crud.png", fullPage: true });
  writeFileSync(".runtime/crud-naming-review/browser-failures/crud.txt", await page.locator("body").innerText());
  throw error;
} finally {
  for (const item of created.reverse()) {
    const response = await context.request.get(`/api${item.endpoint}/${item.id}`);
    if (response.ok()) {
      const latest = await response.json();
      assert.equal((await write(`${item.endpoint}/${item.id}?version=${latest.version}`, "DELETE", {})).status(), 204);
    }
  }
  await browser.close();
  // Remove only this run's fixtures when using the ordinary local preview.
  // Disposable QA databases are removed by their owning runner instead.
  if (!process.env.LUMIRA_QA_CONFIG && created.length) {
    const tables = {"/platform/dictionaries":"platform_dictionary","/platform/organizations":"platform_organization","/platform/roles":"platform_role"};
    const statements = created.map(item => {
      assert.match(item.id, /^[a-f0-9-]{36}$/);
      return `DELETE FROM platform_audit WHERE target_id='${item.id}'; DELETE FROM ${tables[item.endpoint]} WHERE id='${item.id}';`;
    }).join("\n");
    execFileSync("python3", ["-c", "import sys; from smoke_support import sql; sql(sys.stdin.read())"], {cwd:"scripts",input:statements,stdio:["pipe","pipe","pipe"]});
  }
}
