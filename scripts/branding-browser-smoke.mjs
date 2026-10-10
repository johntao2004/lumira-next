import { observeResponse } from "./browser-support.mjs";
import { chromium } from "@playwright/test";
import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const config = JSON.parse(readFileSync(".runtime/local.json", "utf8"));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: "http://127.0.0.1:8010", viewport: { width: 1213, height: 886 } });
const brandingPath = "/api/platform/settings/branding";
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", "base64");
let authorizationHeaders = {};
let initial = null;
let lastOwnedVersion = null;
let anonymous = null;

async function restoreOriginalBranding() {
  if (lastOwnedVersion === null || !initial) return;
  const currentResponse = await context.request.get(brandingPath, { headers: authorizationHeaders });
  assert.equal(currentResponse.status(), 200, "Could not verify the latest branding version before baseline restore");
  const current = await currentResponse.json();
  if (current.version !== lastOwnedVersion) {
    console.warn(`Skipped branding baseline restore: version advanced from smoke-owned ${lastOwnedVersion} to ${current.version}`);
    return;
  }
  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  const restored = await context.request.put(brandingPath, {
    data: { version: current.version, data: initial.data, changeReason: "Restore browser smoke baseline" },
    headers: { ...authorizationHeaders, [csrf.headerName]: csrf.token },
  });
  assert.equal(restored.status(), 200, "Could not restore the branding values captured before the smoke");
}

try {
  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  assert.equal((await context.request.post("/api/auth/login", {
    form: { username: "admin", password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 200);

  // Match the revision header added by the browser API client when role-preview context is active.
  const authMeResponse = await context.request.get("/api/auth/me");
  assert.equal(authMeResponse.status(), 200);
  const authMe = await authMeResponse.json();
  const authorization = authMe.authorizationContext;
  if (authorization?.rolePreviewSupported && authorization.revision) {
    authorizationHeaders = { "X-Lumira-Authorization-Context-Revision": authorization.revision };
  }

  const initialResponse = await context.request.get(brandingPath, { headers: authorizationHeaders });
  assert.equal(initialResponse.status(), 200);
  initial = await initialResponse.json();

  const page = await context.newPage();
  await page.goto("/dashboard/branding");
  const name = page.locator("#data_websiteName");
  await name.waitFor();
  assert.equal(await page.getByRole("main").getByRole("button", { name: "配置历史", exact: true }).count(), 0);
  assert.equal(await page.getByRole("main").getByRole("button", { name: "重新载入配置", exact: true }).count(), 0);
  assert.equal(await name.inputValue(), initial.data.websiteName);

  const other = await context.newPage();
  await other.goto("/dashboard/branding");
  await other.locator("#data_websiteName").waitFor();
  assert.equal(await other.getByRole("main").getByRole("button", { name: "配置历史", exact: true }).count(), 0);
  assert.equal(await other.getByRole("main").getByRole("button", { name: "重新载入配置", exact: true }).count(), 0);

  const uploadedAssets = {};
  async function uploadImage(field, slot) {
    const control = page.getByTestId(`branding-image-${field}`);
    const uploaded = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname === "/api/platform/settings/branding/assets"
        && url.searchParams.get("slot") === slot
        && response.request().method() === "POST";
    });
    await control.locator('input[type="file"]').setInputFiles({
      name: "branding-smoke.png",
      mimeType: "image/png",
      buffer: png,
    });
    const response = await uploaded;
    assert.equal(response.status(), 200, `Could not upload ${field}`);
    const asset = await response.json();
    assert.match(asset.url, /^\/api\/public\/branding\/assets\/[0-9a-f-]+$/i);
    const preview = control.locator("img.branding-image-preview");
    await preview.waitFor();
    assert.match(await preview.getAttribute("src"), /^blob:/, `${field} should show its selected image before save`);
    return asset.url;
  }

  uploadedAssets.websiteFaviconUrl = await uploadImage("websiteFaviconUrl", "FAVICON");
  uploadedAssets.websiteLogoUrl = await uploadImage("websiteLogoUrl", "LOGO");
  uploadedAssets.loginBackgroundUrl = await uploadImage("loginBackgroundUrl", "LOGIN_BACKGROUND");
  await name.fill("Browser Branding QA");
  const firstSave = observeResponse(page, (response) => response.url().endsWith(brandingPath) && response.request().method() === "PUT");
  await page.locator('button[type="submit"]').click();
  const firstSaveResponse = await firstSave;
  assert.equal(firstSaveResponse.status(), 200);
  const firstSnapshot = await firstSaveResponse.json();
  lastOwnedVersion = firstSnapshot.version;
  assert.deepEqual(
    Object.fromEntries(Object.keys(uploadedAssets).map((field) => [field, firstSnapshot.data[field]])),
    uploadedAssets,
    "Explicit save should publish all three uploaded image URLs",
  );
  await page.waitForFunction(() => document.title.endsWith("Browser Branding QA"));

  anonymous = await browser.newContext({ baseURL: "http://127.0.0.1:8010" });
  const publicData = await (await anonymous.request.get("/api/public/branding")).json();
  assert.equal(publicData.websiteName, "Browser Branding QA");
  assert.equal(publicData.version, undefined);
  for (const field of Object.keys(uploadedAssets)) assert.equal(publicData[field], uploadedAssets[field]);
  const publicImage = await anonymous.request.get(uploadedAssets.loginBackgroundUrl);
  assert.equal(publicImage.status(), 200);
  assert.match(publicImage.headers()["content-type"] ?? "", /^image\/png/);
  assert.equal(publicImage.headers()["x-content-type-options"], "nosniff");

  const loginPage = await anonymous.newPage();
  await loginPage.goto("/user/login");
  await loginPage.getByRole("heading").filter({ hasText: "Browser Branding QA" }).waitFor();
  assert.equal(await loginPage.title(), "Browser Branding QA");
  const hero = loginPage.locator(".login-hero");
  await hero.waitFor();
  assert.equal(await hero.isVisible(), true);
  assert.ok((await hero.evaluate((element) => getComputedStyle(element).backgroundImage)).includes("/api/public/branding/assets/"));
  await anonymous.close();
  anonymous = null;

  await page.reload();
  await name.waitFor();
  assert.equal(await name.inputValue(), "Browser Branding QA");
  for (const [field, url] of Object.entries(uploadedAssets)) {
    await page.getByTestId(`branding-image-${field}`).locator("img.branding-image-preview").waitFor();
    assert.equal(await page.getByTestId(`branding-image-${field}`).locator("img.branding-image-preview").getAttribute("src"), url);
  }

  await other.locator("#data_websiteName").fill("Stale edit");
  const rejected = observeResponse(other, (response) => response.url().endsWith(brandingPath) && response.request().method() === "PUT");
  await other.locator('button[type="submit"]').click();
  assert.equal((await rejected).status(), 409);
  assert.equal(await other.locator("#data_websiteName").inputValue(), "Stale edit");
  console.log("PASS: all three image uploads and previews, explicit save, anonymous public image and login background, refresh persistence, absent history/reload actions, and stale editor conflict");
} finally {
  try {
    await restoreOriginalBranding();
  } finally {
    if (anonymous) await anonymous.close();
    await browser.close();
  }
}
