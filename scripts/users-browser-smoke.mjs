import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { chromium } from "@playwright/test";

const config = JSON.parse(readFileSync(".runtime/local.json", "utf8"));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: "http://127.0.0.1:8010" });
try {
  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  const login = await context.request.post("/api/auth/login", {
    form: { username: "admin", password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(login.status(), 200);

  const response = await context.request.get("/api/platform/users/export?search=admin");
  assert.equal(response.status(), 200);
  assert.match(response.headers()["content-type"] ?? "", /^text\/csv/i);
  assert.match(response.headers()["content-disposition"] ?? "", /users\.csv/);
  const body = await response.body();
  assert.deepEqual([...body.subarray(0, 3)], [0xef, 0xbb, 0xbf]);
  const csv = body.toString("utf8");
  assert.match(csv, /id,username,display_name,enabled,version\r?\n/);
  assert.match(csv, /admin/);
  assert.equal((csv.match(/\r?\n/g) ?? []).length, 2);

  const empty = await context.request.get("/api/platform/users/export?search=does-not-exist");
  assert.equal(empty.status(), 200);
  assert.equal((await empty.body()).toString("utf8").split(/\r?\n/).filter(Boolean).length, 1);

  const page = await context.newPage();
  await page.goto("/dashboard/users");
  const exportButton = page.getByRole("link", { name: "导出用户", exact: true });
  await exportButton.waitFor();
  const downloadPromise = page.waitForEvent("download");
  await exportButton.click();
  const download = await downloadPromise;
  assert.equal(await download.failure(), null);
  assert.equal(download.suggestedFilename(), "users.csv");
  console.log("users export PASS: authenticated CSV, UTF-8 BOM, filtered visibility, empty result and UI download");
} finally {
  await browser.close();
}
