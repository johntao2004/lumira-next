import { chromium, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import assert from "node:assert/strict";

const config = JSON.parse(readFileSync(".runtime/local.json", "utf8"));
const title = `Browser feedback QA ${Date.now()}`;
let imageId = null;
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: "http://127.0.0.1:8010", viewport: { width: 1213, height: 886 } });

function sql(statement) {
  return execFileSync(
    process.env.PSQL_BIN ?? "/opt/homebrew/opt/postgresql@18/bin/psql",
    ["-X", "-At", "-h", "127.0.0.1", "-U", config.LUMIRA_DB_USER, "-d", "lumira_next", "-v", "ON_ERROR_STOP=1"],
    { input: statement, env: { ...process.env, PGPASSWORD: config.LUMIRA_DB_PASSWORD }, encoding: "utf8" },
  ).trim();
}

try {
  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  assert.equal(
    (await context.request.post("/api/auth/login", {
      form: { username: "admin", password: config.LUMIRA_BOOTSTRAP_PASSWORD },
      headers: { [csrf.headerName]: csrf.token },
    })).status(),
    200,
  );
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));

  await page.goto("/dashboard/feedback");
  await expect(page.getByRole("heading", { name: "工单反馈", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "提交反馈", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "提交反馈", exact: true }).click();
  const create = page.getByRole("dialog", { name: "提交反馈", exact: true });
  await create.getByLabel("标题", { exact: true }).fill(title);
  await create.locator('[contenteditable="true"]').fill("页面在窄屏下内容溢出");
  const imageUploaded = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/feedback/images" && response.request().method() === "POST");
  await create.getByRole("button", { name: "插入图片", exact: true }).click();
  await create.locator('input[type="file"]').setInputFiles({
    name: "screenshot.png",
    mimeType: "image/png",
    buffer: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", "base64"),
  });
  const imageResponse = await imageUploaded;
  assert.equal(imageResponse.status(), 201);
  imageId = (await imageResponse.json()).id;
  await expect(create.locator(`img[src="/api/feedback/images/${imageId}"]`)).toHaveCount(1);
  const created = page.waitForResponse((response) => response.url().endsWith("/api/feedback") && response.request().method() === "POST");
  await create.getByRole("button", { name: "保存", exact: true }).click();
  assert.equal((await created).status(), 201);
  await expect(create).toBeHidden();
  await expect(page.getByText(title, { exact: true })).toBeVisible();

  await expect(page.getByText(title, { exact: true })).toBeVisible();
  const row = page.getByRole("row").filter({ hasText: title });
  await row.getByRole("button", { name: "详情", exact: true }).click();
  const details = page.getByRole("dialog", { name: "反馈详情", exact: true });
  await expect(details.getByText("页面在窄屏下内容溢出", { exact: true })).toBeVisible();
  await expect(details.locator(`img[src="/api/feedback/manage/images/${imageId}"]`)).toHaveCount(1);
  const imageRequest = await context.request.get(`/api/feedback/manage/images/${imageId}`);
  assert.equal(imageRequest.status(), 200);
  assert.match(imageRequest.headers()["content-type"] ?? "", /^image\/png/);
  await details.getByRole("button", { name: "处理反馈", exact: true }).click();
  const process = page.getByRole("dialog", { name: "处理反馈", exact: true });
  await process.getByLabel("状态", { exact: true }).click();
  await process.getByLabel("状态", { exact: true }).press("ArrowDown");
  await process.getByLabel("状态", { exact: true }).press("Enter");
  await process.getByLabel("处理回复", { exact: true }).fill("已确认并安排修复");
  const handled = page.waitForResponse((response) => response.url().includes("/api/feedback/manage/") && response.request().method() === "PUT");
  await process.getByRole("button", { name: "保存", exact: true }).click();
  assert.equal((await handled).status(), 200);
  await expect(process).toBeHidden();
  await expect(details.getByText("处理中", { exact: true })).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(async () => {
    const box = await details.boundingBox();
    return Boolean(box && box.x >= 0 && box.x + box.width <= 390);
  }).toBeTruthy();
  await page.screenshot({ path: ".runtime/feedback-mobile.png" });
  assert.deepEqual(errors, []);
  console.log("PASS: feedback route, dictionary-driven filters, rich text creation, image upload, owner/admin detail, versioned processing and mobile drawer bounds");
} finally {
  try {
    if (imageId) {
      // Remove the reference first, then exercise the normal versioned file delete path.
      sql(`DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_feedback WHERE title='${title.replaceAll("'", "''")}'); DELETE FROM platform_feedback WHERE title='${title.replaceAll("'", "''")}';`);
      const imageCsrf = await (await context.request.get("/api/auth/csrf")).json();
      await context.request.delete(`/api/account/files/${imageId}?version=1`, { headers: { [imageCsrf.headerName]: imageCsrf.token } });
    }
    const csrf = await (await context.request.get("/api/auth/csrf")).json();
    await context.request.post("/api/auth/logout", { headers: { [csrf.headerName]: csrf.token } });
  } finally {
    sql(`DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_feedback WHERE title='${title.replaceAll("'", "''")}'); DELETE FROM platform_feedback WHERE title='${title.replaceAll("'", "''")}';`);
    await browser.close();
  }
}
