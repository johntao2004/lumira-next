import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
const { config, baseURL } = loadQaRunContext();
const fixture = JSON.parse(readFileSync('.runtime/architecture-fix/profile-console-fixture.json', 'utf8'));
assert.equal(fixture.runToken, config.LUMIRA_QA_RUN_TOKEN);
const t = await loadLocaleMessages('zh-CN');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL, locale: 'zh-CN', viewport: { width: 1213, height: 886 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
async function login(context, username, password) {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
}
async function operate(action) {
  const result = await page.evaluate(async action => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const base = '/platform/modules/plugins/profile-console/';
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
try {
  await login(context, 'admin', config.LUMIRA_BOOTSTRAP_PASSWORD);
  await page.goto('/dashboard/profile-fields');
  const row = page.locator(`[data-field-key="${fixture.textKey}"]`);
  const editorLabel = page.locator('.profile-fields-detail input[id$="_label"]:visible');
  await expect(row).toBeVisible({ timeout: 30000 });
  const original = await page.evaluate(() => window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/settings/profile-fields'));
  await row.locator('.profile-field-select').click();
  await expect(editorLabel).toHaveValue(fixture.textLabel);
  const nextLabel = fixture.textLabel + ' UI';
  await editorLabel.fill(nextLabel);
  const savedResponse = page.waitForResponse(response => response.url().endsWith('/api/platform/settings/profile-fields') && response.request().method() === 'PUT');
  await page.locator('.settings-form:visible').getByRole('button', { name: t.save, exact: true }).click();
  const response = await savedResponse;
  assert.equal(response.status(), 200);
  const saved = await response.json();
  await page.reload();
  await expect(row).toContainText(nextLabel, { timeout: 30000 });
  await row.locator('.profile-field-select').click();
  await expect(editorLabel).toHaveValue(nextLabel);
  await page.evaluate(async ({ original, expectedVersion, key, expectedLabel }) => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const current = await client.api('/platform/settings/profile-fields');
    if (current.version !== expectedVersion || current.data.fields.find(field => field.key === key)?.label !== expectedLabel) throw new Error('Competing profile schema edit');
    return client.mutate('/platform/settings/profile-fields', 'PUT', { version: current.version, data: original.data });
  }, { original, expectedVersion: saved.version, key: fixture.textKey, expectedLabel: nextLabel });
  const plugin = await page.evaluate(async () => (await window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/modules/plugins')).find(plugin => plugin.id === 'profile-console'));
  assert.ok(plugin.manifest.files['frontend/plugin.css'], 'Layout must belong to the independent plugin artifact');
  const style = page.locator(`link[rel="stylesheet"][href*="/${plugin.digest}/plugin.css"]`);
  await expect(style).toHaveCount(1);
  const owner = await browser.newContext({ baseURL, locale: 'zh-CN', viewport: { width: 1213, height: 886 } });
  await login(owner, fixture.username, fixture.password);
  const account = await owner.newPage();
  const ownerLookupStatuses = [];
  account.on('response', response => {
    if (/\/(?:profile-)?dictionaries\/.+\/items$/.test(new URL(response.url()).pathname)) ownerLookupStatuses.push(response.status());
  });
  account.on('pageerror', error => errors.push(error.message));
  await account.goto('/dashboard/account-security');
  await account.getByRole('tab', { name: t.fieldCustomGroup, exact: true }).click();
  await expect(account.getByRole('textbox', { name: fixture.textLabel })).toHaveValue(fixture.value, { timeout: 30000 });
  await expect.poll(() => ownerLookupStatuses.length, { timeout: 20000 }).toBeGreaterThan(0);
  assert.ok(ownerLookupStatuses.includes(200), 'A profile owner must be able to select published dictionary options');
  assert.equal(ownerLookupStatuses.includes(403), false, 'Personal profile lookup must not require management grants');
  await operate('uninstall');
  await expect(row).toHaveCount(0, { timeout: 20000 });
  await expect(style).toHaveCount(0, { timeout: 20000 });
  assert.equal(new URL(page.url()).pathname, '/dashboard/profile-fields');
  await account.reload();
  await account.getByRole('tab', { name: t.fieldCustomGroup, exact: true }).click();
  await expect(account.getByRole('textbox', { name: fixture.textLabel })).toHaveValue(fixture.value, { timeout: 30000 });
  await operate('install');
  await expect(row).toContainText(fixture.textLabel, { timeout: 30000 });
  await expect(style).toHaveCount(1, { timeout: 20000 });
  for (const width of [1213, 390]) {
    await page.setViewportSize({ width, height: 886 });
    await page.reload();
    await expect(row).toBeVisible({ timeout: 30000 });
    await row.locator('.profile-field-select').click();
    await expect(editorLabel).toHaveValue(fixture.textLabel);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false, 'Profile editor overflow ' + width);
    if (width === 390) {
      const list = await page.locator('.profile-fields-list').boundingBox();
      const details = await page.locator('.profile-fields-detail').boundingBox();
      assert.ok(details.y >= list.y + list.height, 'Mobile profile editor must stack below its field list');
    }
  }
  assert.deepEqual(errors, []);
  console.log('PASS: dynamically loaded profile schema editor and own CSS; real UI save/reload and version-guarded restore; mounted editor/styles withdrawn and reinstalled while retained personal profile fields/values remain usable; desktop/mobile reload and no page errors');
} catch (error) {
  mkdirSync('.runtime/browser-failures', { recursive: true });
  await page.screenshot({ path: '.runtime/browser-failures/profile-console-plugin.png', fullPage: true });
  writeFileSync('.runtime/browser-failures/profile-console-plugin.txt', await page.locator('body').innerText());
  throw error;
} finally {
  await browser.close();
}
