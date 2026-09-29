import assert from "node:assert/strict";
import { chromium, expect } from "@playwright/test";

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({
  baseURL: "http://127.0.0.1:8010",
  viewport: { width: 1213, height: 886 },
});

try {
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/user/login");
  await page.getByLabel("用户名", { exact: true }).fill("feedback-bubble-invalid");
  await page.getByLabel("密码", { exact: true }).fill("invalid-password");
  await page.locator("button[type=submit]").click();
  const bubble = page.locator(".ant-message-notice");
  await expect(bubble).toContainText("用户名或密码错误");
  await expect(page.locator(".login-content .ant-alert-error")).toHaveCount(0);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log("PASS: authentication failures use a transient bubble message, do not reserve form layout space, and fit the viewport");
} finally {
  await browser.close();
}
