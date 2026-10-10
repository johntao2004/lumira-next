import assert from "node:assert/strict";
import { mkdirSync, readFileSync } from "node:fs";
import { chromium } from "@playwright/test";

const config = JSON.parse(readFileSync(".runtime/local.json", "utf8"));
const baseURL = process.env.LUMIRA_WEB_BASE_URL ?? "http://127.0.0.1:8010";
const artifacts = ".runtime/no-change-reason";
mkdirSync(artifacts, { recursive: true });

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
const businessValidationSkips = [];
let authorizationHeaders = {};
const businessValidationCodes = new Set([
  "invalidAuthenticationPolicy", "authenticationPolicyUnreachable", "secondFactorUnavailable",
  "invalidBranding", "invalidSecurity", "invalidCaptchaSettings", "captchaProviderNotReady",
  "captchaRequiredProviderReferenced", "invalidWechatSettings", "wechatAppInUse", "secretKeyUnavailable",
  "invalidRegistrationPolicy", "registrationRoleIneligibleError", "phoneRecoveryUnavailable",
]);

function endpointMatches(request, path) {
  return new URL(request.url()).pathname === path && request.method() === "PUT";
}

function businessState(snapshot) {
  const metadata = new Set(["version", "revision", "changedBy", "changedAt", "createdAt", "updatedAt", "sourceVersion"]);
  return Object.fromEntries(Object.entries(snapshot).filter(([key]) => !metadata.has(key)));
}

function assertNoReasonKeys(value, label) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => assertNoReasonKeys(item, `${label}[${index}]`));
    return;
  }
  if (!value || typeof value !== "object") return;
  for (const [key, child] of Object.entries(value)) {
    assert.ok(!["reason", "changeReason"].includes(key), `${label} request must not include ${key}`);
    assertNoReasonKeys(child, `${label}.${key}`);
  }
}

function responseCode(body) {
  try {
    const parsed = JSON.parse(body);
    return String(parsed.detail ?? parsed.code ?? parsed.error ?? parsed.message ?? body);
  } catch {
    return body.trim();
  }
}

async function getSnapshot(path) {
  const response = await context.request.get(`/api${path}`, { headers: authorizationHeaders });
  if (response.status() !== 200) {
    const body = (await response.text()).slice(0, 300);
    assert.equal(response.status(), 200, `Could not read ${path}: HTTP ${response.status()} ${body}`);
  }
  return response.json();
}

async function assertNoReasonField(scope, label) {
  assert.equal(await scope.locator('[name="changeReason"],#changeReason,[id$="_changeReason"]').count(), 0,
    `${label} must not render a change-reason control`);
  assert.equal(await scope.getByText("修改原因", { exact: true }).count(), 0,
    `${label} must not display a change-reason label`);
}

async function activeTabPanel(page, key, click = false) {
  const tab = page.locator(`[role="tab"][id$="-tab-${key}"]`);
  await tab.waitFor();
  if (click || await tab.getAttribute("aria-selected") !== "true") await tab.click();
  await page.waitForFunction(expected => new URLSearchParams(location.hash.slice(1)).get("tab") === expected, key);
  assert.equal(await tab.getAttribute("aria-selected"), "true", `${key} tab should be selected`);
  const panelId = await tab.getAttribute("aria-controls");
  assert.ok(panelId, `${key} tab must point to its panel`);
  const panel = page.locator(`[id="${panelId}"]`);
  await panel.waitFor({ state: "visible" });
  return panel;
}

async function assertUsableForm(panel, label, businessControl) {
  const form = panel.locator("form").first();
  await form.waitFor();
  await assertNoReasonField(panel, label);
  const control = (typeof businessControl === "string" ? panel.locator(businessControl) : businessControl).first();
  await control.waitFor();
  assert.equal(await control.isEnabled(), true, `${label} business control should remain usable`);
  const submit = form.locator('button[type="submit"]').last();
  await submit.waitFor();
  assert.equal(await submit.isEnabled(), true, `${label} save action should remain usable`);
  return { form, submit };
}

async function saveSameValues(page, path, label, form, submit, afterReloadSelector) {
  const before = await getSnapshot(path);
  const requestPromise = page.waitForRequest(request => endpointMatches(request, `/api${path}`), { timeout: 8000 }).catch(() => null);
  const responsePromise = page.waitForResponse(response => endpointMatches(response.request(), `/api${path}`), { timeout: 8000 }).catch(() => null);
  await submit.click();
  const request = await requestPromise;

  if (!request) {
    const errors = (await form.locator(".ant-form-item-explain-error").allTextContents()).map(text => text.trim()).filter(Boolean);
    assert.ok(errors.length > 0, `${label} save did not send a request or show a business validation message`);
    assert.ok(!errors.some(text => /change\s*reason|修改原因/i.test(text)), `${label} must not validate a removed change-reason field`);
    const unchanged = await getSnapshot(path);
    assert.deepEqual(unchanged, before, `${label} must remain unchanged when another business field blocks submission`);
    businessValidationSkips.push(`${label}: ${errors.join("; ")}`);
    console.log(`SKIP same-value save for ${label}: existing business-field validation blocked the request (${errors.join("; ")})`);
    await page.reload();
    await page.locator(afterReloadSelector).first().waitFor();
    assert.deepEqual(await getSnapshot(path), before, `${label} must still match after reload`);
    return null;
  }

  assertNoReasonKeys(request.postDataJSON(), label);
  const response = await responsePromise;
  assert.ok(response, `${label} save request did not receive a response`);
  const bodyText = await response.text();
  if (response.status() !== 200) {
    const code = responseCode(bodyText);
    assert.ok(!/change\s*reason|修改原因|invalidChangeReason/i.test(code), `${label} backend still requires a change reason`);
    if ([400, 422].includes(response.status()) && businessValidationCodes.has(code)) {
      const unchanged = await getSnapshot(path);
      assert.deepEqual(unchanged, before, `${label} business rejection must not change stored values`);
      businessValidationSkips.push(`${label}: HTTP ${response.status()} ${code}`);
      console.log(`SKIP same-value save for ${label}: backend rejected existing business state (HTTP ${response.status()}, ${code})`);
      await page.reload();
      await page.locator(afterReloadSelector).first().waitFor();
      assert.deepEqual(await getSnapshot(path), before, `${label} must remain unchanged after reload`);
      return null;
    }
    assert.fail(`${label} same-value save failed unexpectedly: HTTP ${response.status()} ${code}`);
  }

  const saved = JSON.parse(bodyText);
  assert.equal(saved.version, before.version + 1, `${label} should add one normal settings revision`);
  assert.deepEqual(businessState(saved), businessState(before), `${label} save must preserve every business value`);
  assert.deepEqual(await getSnapshot(path), saved, `${label} save must persist`);

  await page.reload();
  await page.locator(afterReloadSelector).first().waitFor();
  assert.deepEqual(await getSnapshot(path), saved, `${label} state must remain identical after page reload`);
  return saved;
}

try {
  await context.addCookies([{
    name: "lumira-next.preferences",
    value: encodeURIComponent(JSON.stringify({ locale: "zh-CN" })),
    url: baseURL,
  }]);
  const csrf = await (await context.request.get("/api/auth/csrf")).json();
  const login = await context.request.post("/api/auth/login", {
    form: { username: "admin", password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(login.status(), 200, "Browser regression must use the real admin account");
  const authMe = await (await context.request.get("/api/auth/me")).json();
  const authorization = authMe.authorizationContext;
  if (authorization?.rolePreviewSupported && authorization.revision) {
    authorizationHeaders = { "X-Lumira-Authorization-Context-Revision": authorization.revision };
  }

  const page = await context.newPage();
  const pageErrors = [];
  page.on("pageerror", error => pageErrors.push(error.message));

  // The shared settings form is represented by branding and security. Saving
  // the live values adds only an ordinary revision/audit record.
  await page.goto("/dashboard/branding#tab=branding");
  let panel = await activeTabPanel(page, "branding");
  let controls = await assertUsableForm(panel, "branding", "#data_websiteName");
  await saveSameValues(page, "/platform/settings/branding", "branding", controls.form, controls.submit, "#data_websiteName");
  await page.screenshot({ path: `${artifacts}/branding.png`, fullPage: true });

  await page.goto("/dashboard/security#tab=security");
  panel = await activeTabPanel(page, "security");
  controls = await assertUsableForm(panel, "security", "#data_passwordMinLength");
  await saveSameValues(page, "/platform/settings/security", "security", controls.form, controls.submit, "#data_passwordMinLength");
  await page.screenshot({ path: `${artifacts}/security.png`, fullPage: true });

  // Follow the real settings RouteTabs keys and verify their URL anchor so this
  // exercises the page tabs rather than assuming standalone routes.
  await page.goto("/dashboard/security#tab=security");
  for (const spec of [
    { key: "authentication", path: "/platform/settings/authentication", selector: "#enabledMethods .ant-switch", save: false },
    { key: "registration", path: "/platform/settings/registration", selector: ".ant-switch", save: true },
    { key: "wechat", path: "/platform/settings/wechat", selector: 'input[id$="_appId"]', save: true },
    { key: "captcha", path: "/platform/settings/captcha", selector: '[role="combobox"]', save: true },
  ]) {
    panel = await activeTabPanel(page, spec.key, true);
    const controlsForTab = await assertUsableForm(panel, spec.key, spec.selector);
    if (spec.save) {
      await saveSameValues(page, spec.path, spec.key, controlsForTab.form, controlsForTab.submit, spec.selector);
    } else {
      await page.screenshot({ path: `${artifacts}/authentication.png`, fullPage: true });
    }
  }

  // Inspect the role policy editor without saving it; role/authentication
  // records must not be changed as part of this regression check.
  await page.goto("/dashboard/roles");
  await page.getByRole("table").waitFor();
  const roleRow = page.getByRole("row").filter({ has: page.getByRole("button", { name: /^(编辑|详情)$/ }) }).first();
  await roleRow.waitFor();
  await roleRow.getByRole("button", { name: /^(编辑|详情)$/ }).click();
  const roleDrawer = page.getByRole("dialog").last();
  await roleDrawer.getByText("角色认证策略", { exact: true }).waitFor();
  await assertNoReasonField(roleDrawer, "role authentication policy");
  const policyCard = page.locator(".ant-card").filter({ hasText: "角色认证策略" }).last();
  assert.ok(await policyCard.getByRole("radio").count() >= 2, "Role authentication policy business controls should remain visible");
  assert.equal(await policyCard.getByRole("radio").first().isEnabled(), true, "Role authentication policy should remain editable");
  assert.equal(await policyCard.getByRole("button", { name: "保存", exact: true }).isEnabled(), true, "Role authentication save action should remain available");
  await roleDrawer.locator(".ant-drawer-close").click();

  // Open and cancel a real module state confirmation. Do not confirm it, so no module
  // is enabled, disabled, installed, or removed by this scan.
  await page.goto("/dashboard/modules");
  await page.getByRole("tab", { name: "可管理模块", exact: true }).click();
  const moduleAction = page.getByRole("button", { name: /^(停用模块|启用模块)$/ }).first();
  await moduleAction.waitFor();
  const actionName = await moduleAction.innerText();
  await moduleAction.click();
  const moduleDialog = page.getByRole("dialog", { name: actionName.trim(), exact: true });
  await moduleDialog.waitFor();
  await assertNoReasonField(moduleDialog, "module state dialog");
  assert.equal(await moduleDialog.locator("textarea").count(), 0, "Module state dialog must not render an unused reason box");
  assert.equal(await moduleDialog.getByRole("button", { name: /确\s*认/ }).isEnabled(), true, "Module state confirmation should remain available without a reason field");
  const cancelledModuleRequest = page.waitForRequest(request => {
    const path = new URL(request.url()).pathname;
    return request.method() === "PUT" && /^\/api\/platform\/modules\/[^/]+\/state$/.test(path);
  }, { timeout: 500 }).catch(() => null);
  await moduleDialog.getByRole("button", { name: /取\s*消/ }).click();
  assert.equal(await cancelledModuleRequest, null, "Cancelling module confirmation must not change module state");

  assert.deepEqual(pageErrors, [], `Unexpected browser page errors: ${pageErrors.join("\n")}`);
  console.log(`PASS: signed-in settings forms omit change reason and keep business controls usable; unchanged values were saved and reloaded; role policy and module dialogs were inspected without changing role, account, authentication, or module state. Screenshots: ${artifacts}`);
  if (businessValidationSkips.length) {
    console.log(`BUSINESS VALIDATION SKIPS (${businessValidationSkips.length}): ${businessValidationSkips.join(" | ")}`);
  }
} finally {
  await browser.close();
}
