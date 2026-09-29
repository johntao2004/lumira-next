import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { chromium, expect } from "@playwright/test";

const config = JSON.parse(readFileSync(".runtime/local.json", "utf8"));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({
  baseURL: "http://127.0.0.1:8010",
  viewport: { width: 800, height: 1517 },
});

async function assertFormSpacing(page, name) {
  await expect(page.locator(".platform-form").first()).toBeVisible();
  await expect(page.locator(".platform-form .ant-form-item").first()).toBeVisible();
  const metrics = await page.evaluate(() => ({
    formCount: document.querySelectorAll(".platform-form").length,
    itemMargins: [...document.querySelectorAll(".platform-form .ant-form-item")].map((item) => getComputedStyle(item).marginBottom),
    actionGaps: [...document.querySelectorAll(".form-actions, .login-actions")].map((element) => getComputedStyle(element).gap),
    fitsViewport: document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
  }));
  assert.ok(metrics.formCount > 0, `${name}: shared form wrapper missing`);
  assert.ok(metrics.itemMargins.length > 0, `${name}: form items missing`);
  assert.ok(metrics.itemMargins.every((margin) => margin === "16px"), `${name}: unexpected item margins ${metrics.itemMargins.join(",")}`);
  assert.ok(metrics.actionGaps.every((gap) => gap === "10px"), `${name}: unexpected action gaps ${metrics.actionGaps.join(",")}`);
  assert.equal(metrics.fitsViewport, true, `${name}: horizontal overflow`);
}

try {
  const page = await context.newPage();
  await page.goto("/user/login");
  await assertFormSpacing(page, "login");

  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  const login = await context.request.post("/api/auth/login", {
    form: { username: "admin", password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(login.status(), 200);

  await page.goto("/dashboard/security");
  await assertFormSpacing(page, "security settings");

  await page.goto("/dashboard/users");
  await page.getByRole("button", { name: "新增用户", exact: true }).click();
  await assertFormSpacing(page, "user editor");
  await page.locator(".form-actions button").first().click();

  await page.goto("/dashboard/dictionaries");
  await page.getByRole("button", { name: "新增字典", exact: true }).click();
  await assertFormSpacing(page, "dictionary editor");
  await page.locator(".form-actions button").first().click();

  console.log("PASS: shared PlatformForm spacing is 16px, action areas are 10px, and login/settings/user/dictionary forms fit the viewport");
} finally {
  await browser.close();
}
