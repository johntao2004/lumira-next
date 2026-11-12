import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
const { config, baseURL } = loadQaRunContext();
const fixture = JSON.parse(readFileSync('.runtime/architecture-fix/delivery-settings-fixture.json', 'utf8'));
assert.equal(fixture.runToken, config.LUMIRA_QA_RUN_TOKEN);
const t = await loadLocaleMessages('zh-CN');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
async function operate(action) {
  const result = await page.evaluate(async action => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const base = '/platform/modules/plugins/delivery-settings/';
    const plan = await client.mutate(base + action + '-plan', 'POST', {});
    if (plan.blockers.length) throw new Error(JSON.stringify(plan.blockers));
    return client.mutate(base + action, 'POST', { revision: plan.revision, dataMode: 'PRESERVE' });
  }, action);
  let operation = result;
  for (let attempt = 0; attempt < 240 && !['SUCCEEDED', 'FAILED'].includes(operation.status); attempt++) {
    await page.waitForTimeout(250);
    operation = await page.evaluate(async id => window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/modules/plugins/operations/' + id), result.id);
  }
  assert.equal(operation.status, 'SUCCEEDED', JSON.stringify(operation));
  await page.evaluate(() => window.dispatchEvent(new Event('lumira:modules-changed')));
}
async function restoreDisabledSmtp(expectedName) {
  await page.evaluate(async ({ expectedName, originalName }) => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const current = await client.api('/platform/settings/smtp');
    if (current.data.fromName !== expectedName) throw new Error('Competing SMTP edit');
    return client.mutate('/platform/settings/smtp', 'PUT', { version: current.version, data: { ...current.data, fromName: originalName, enabled: false }, credentials: { action: 'KEEP' } });
  }, { expectedName, originalName: fixture.data.smtp.fromName });
}
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
  await page.goto('/dashboard/smtp');
  const form = page.locator('.settings-form:visible');
  const smtpEnabled = page.getByRole('switch', { name: t.smtpEnabled });
  await expect(smtpEnabled).toBeVisible({ timeout: 30000 });
  await page.evaluate(async original => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const current = await client.api('/platform/settings/smtp');
    if (![original.fromName, original.fromName + ' UI'].includes(current.data.fromName)) throw new Error('Unowned SMTP fixture');
    for (const [key, value] of Object.entries(original)) {
      if (!['enabled', 'fromName'].includes(key) && JSON.stringify(current.data[key]) !== JSON.stringify(value)) throw new Error('Competing SMTP fixture edit');
    }
    if (current.data.enabled || current.data.fromName !== original.fromName) await client.mutate('/platform/settings/smtp', 'PUT', { version: current.version, data: { ...current.data, enabled: false, fromName: original.fromName }, credentials: { action: 'KEEP' } });
  }, fixture.data.smtp);
  await page.reload();
  await expect(smtpEnabled).not.toBeChecked({ timeout: 30000 });
  await operate('uninstall');
  await expect(smtpEnabled).toHaveCount(0, { timeout: 20000 });
  assert.equal(new URL(page.url()).pathname, '/dashboard/smtp');
  await operate('install');
  await expect(smtpEnabled).not.toBeChecked({ timeout: 30000 });
  await smtpEnabled.check();
  const name = page.getByRole('textbox', { name: t.smtpFromName });
  await expect(name).toHaveValue(fixture.data.smtp.fromName);
  const next = fixture.data.smtp.fromName + ' UI';
  await name.fill(next);
  const saving = page.waitForResponse(response => response.url().endsWith('/api/platform/settings/smtp') && response.request().method() === 'PUT');
  await form.getByRole('button', { name: t.save, exact: true }).click();
  assert.equal((await saving).status(), 200);
  await page.reload();
  await expect(name).toHaveValue(next, { timeout: 30000 });
  await restoreDisabledSmtp(next);

  // The authentication host must render a fresh optional editor and preserve its save callback.
  await page.goto('/dashboard/authentication');
  const emailMethod = page.getByRole('switch', { name: t.emailOtpLogin, exact: true });
  await expect(emailMethod).not.toBeChecked({ timeout: 30000 });
  await emailMethod.check();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('switch', { name: t.smtpEnabled })).toBeVisible({ timeout: 30000 });
  await operate('uninstall');
  await expect(dialog.locator('.settings-form')).toHaveCount(0, { timeout: 20000 });
  await operate('install');
  const embeddedSwitch = dialog.getByRole('switch', { name: t.smtpEnabled });
  await expect(embeddedSwitch).toBeVisible({ timeout: 30000 });
  await embeddedSwitch.check();
  const embeddedSave = page.waitForResponse(response => response.url().endsWith('/api/platform/settings/smtp') && response.request().method() === 'PUT');
  await dialog.getByRole('button', { name: t.save, exact: true }).click();
  assert.equal((await embeddedSave).status(), 200);
  await expect(dialog).toHaveCount(0, { timeout: 30000 });
  await expect(emailMethod).toBeChecked();
  // The editor saved channel readiness; the policy remains a separate explicit user submission.
  const policy = await page.evaluate(() => window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/settings/authentication'));
  assert.equal(policy.enabledMethods.includes('EMAIL_OTP'), false);
  assert.equal(policy.availableMethods.includes('EMAIL_OTP'), true);
  await restoreDisabledSmtp(fixture.data.smtp.fromName);
  for (const width of [1213, 390]) {
    await page.setViewportSize({ width, height: 886 });
    for (const section of ['smtp', 'sms']) {
      await page.goto('/dashboard/' + section);
      await expect(form).toBeVisible({ timeout: 30000 });
      await page.reload();
      await expect(page.getByRole('switch', { name: section === 'smtp' ? t.smtpEnabled : t.smsEnabled })).not.toBeChecked({ timeout: 30000 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false, section + ' ' + width);
    }
  }
  assert.deepEqual(errors, []);
  console.log('PASS: independent SMTP/SMS pages and mounted unload/reinstall; real SMTP UI save and persisted reload; authentication modal loads the optional editor, withdraws/restores it live and receives the completed-save readiness callback without implicitly saving login policy; desktop/mobile reload and no uncaught page errors');
} catch (error) {
  mkdirSync('.runtime/browser-failures', { recursive: true });
  await page.screenshot({ path: '.runtime/browser-failures/delivery-settings-plugin.png', fullPage: true });
  writeFileSync('.runtime/browser-failures/delivery-settings-plugin.txt', await page.locator('body').innerText());
  throw error;
} finally {
  await browser.close();
}
