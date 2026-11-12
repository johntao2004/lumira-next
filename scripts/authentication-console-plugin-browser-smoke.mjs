import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';

const { config, baseURL } = loadQaRunContext();
const fixture = JSON.parse(readFileSync('.runtime/architecture-fix/authentication-console-fixture.json', 'utf8'));
assert.equal(fixture.runToken, config.LUMIRA_QA_RUN_TOKEN);
const t = await loadLocaleMessages('zh-CN');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL, locale: 'zh-CN', viewport: { width: 1213, height: 886 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
async function login(target, username, password) {
  const csrf = await (await target.request.get('/api/auth/csrf')).json();
  assert.equal((await target.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
}
async function operate(action) {
  let operation = await page.evaluate(async action => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const base = '/platform/modules/plugins/authentication-console/';
    const plan = await client.mutate(base + action + '-plan', 'POST', {});
    if (plan.blockers.length) throw new Error(JSON.stringify(plan.blockers));
    return client.mutate(base + action, 'POST', { revision: plan.revision, dataMode: 'PRESERVE' });
  }, action);
  for (let attempt = 0; attempt < 240 && !['SUCCEEDED', 'FAILED'].includes(operation.status); attempt++) {
    await page.waitForTimeout(250);
    operation = await page.evaluate(async id => window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/modules/plugins/operations/' + id), operation.id);
  }
  assert.equal(operation.status, 'SUCCEEDED', JSON.stringify(operation));
  await page.evaluate(() => window.dispatchEvent(new Event('lumira:modules-changed')));
}
try {
  await login(context, 'admin', config.LUMIRA_BOOTSTRAP_PASSWORD);
  await page.goto('/dashboard/security');
  const editor = page.locator('.security-settings:visible');
  const minimum = editor.getByRole('spinbutton', { name: t.passwordMinLength });
  await expect(minimum).toHaveValue(String(fixture.passwordMinLength), { timeout: 30000 });
  const original = await page.evaluate(() => window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/settings/security'));
  await minimum.fill('20');
  const pending = page.waitForResponse(response => response.url().endsWith('/api/platform/settings/security') && response.request().method() === 'PUT');
  await editor.getByRole('button', { name: t.save, exact: true }).click();
  const response = await pending;
  assert.equal(response.status(), 200);
  const saved = await response.json();
  await page.reload();
  await expect(minimum).toHaveValue('20', { timeout: 30000 });
  await page.evaluate(async ({ original, expectedVersion }) => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const current = await client.api('/platform/settings/security');
    if (current.version !== expectedVersion || current.data.passwordMinLength !== 20) throw new Error('Competing security policy edit');
    return client.mutate('/platform/settings/security', 'PUT', { version: current.version, data: original.data, changeReason: 'QA UI exact-version restore' });
  }, { original, expectedVersion: saved.version });
  const plugin = await page.evaluate(async () => (await window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/modules/plugins')).find(plugin => plugin.id === 'authentication-console'));
  assert.ok(plugin.manifest.files['frontend/plugin.css']);
  const style = page.locator(`link[rel="stylesheet"][href*="/${plugin.digest}/plugin.css"]`);
  await expect(style).toHaveCount(1);
  await operate('uninstall');
  await expect(editor).toHaveCount(0, { timeout: 20000 });
  await expect(style).toHaveCount(0, { timeout: 20000 });
  assert.equal(new URL(page.url()).pathname, '/dashboard/security');
  const account = await context.newPage();
  account.on('pageerror', error => errors.push(error.message));
  await account.goto('/dashboard/account-security');
  await expect(account.getByRole('tab', { name: t.fieldCustomGroup, exact: true })).toBeVisible({ timeout: 30000 });
  await account.close();
  await operate('install');
  await expect(minimum).toHaveValue(String(fixture.passwordMinLength), { timeout: 30000 });
  await expect(style).toHaveCount(1);
  for (const width of [1213, 390]) {
    await page.setViewportSize({ width, height: 886 });
    await page.reload();
    await expect(minimum).toHaveValue(String(fixture.passwordMinLength), { timeout: 30000 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false, 'Security editor overflow ' + width);
  }
  const reader = await browser.newContext({ baseURL, locale: 'zh-CN' });
  await login(reader, fixture.username, fixture.password);
  const readonly = await reader.newPage();
  readonly.on('pageerror', error => errors.push(error.message));
  await readonly.goto('/dashboard/security');
  await expect(readonly.locator('.security-settings:visible').getByRole('spinbutton', { name: t.passwordMinLength })).toHaveValue('18', { timeout: 30000 });
  await expect(readonly.locator('.security-settings:visible').getByRole('button', { name: t.save, exact: true })).toBeDisabled();
  await expect(readonly.locator('.security-settings:visible').getByRole('spinbutton', { name: t.passwordMinLength })).toBeDisabled();
  assert.deepEqual(errors, []);
  console.log('PASS: optional authentication editor owns UI/CSS, actual save/reload and exact-version restore; mounted editor and style withdraw/reinstall with retained policy; core account page stays available; global reader has disabled inputs and save action; desktop/mobile no overflow or page errors');
} catch (error) {
  mkdirSync('.runtime/browser-failures', { recursive: true });
  await page.screenshot({ path: '.runtime/browser-failures/authentication-console-plugin.png', fullPage: true });
  writeFileSync('.runtime/browser-failures/authentication-console-plugin.txt', await page.locator('body').innerText());
  throw error;
} finally {
  await browser.close();
}
