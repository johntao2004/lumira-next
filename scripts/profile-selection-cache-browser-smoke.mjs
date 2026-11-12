import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';

const { config, baseURL } = loadQaRunContext();
const fixture = JSON.parse(readFileSync('.runtime/architecture-fix/profile-console-fixture.json', 'utf8'));
assert.equal(fixture.runToken, config.LUMIRA_QA_RUN_TOKEN);
const t = await loadLocaleMessages('zh-CN');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
let binding;
let admin;
async function login(username, password) {
  const context = await browser.newContext({ baseURL, locale: 'zh-CN', viewport: { width: 1213, height: 886 } });
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
  const page = await context.newPage();
  await page.goto('/dashboard/account-security');
  await expect(page.locator('#account-profile')).toBeVisible({ timeout: 30000 });
  return page;
}
try {
  admin = await login('admin', config.LUMIRA_BOOTSTRAP_PASSWORD);
  const owner = await login(fixture.username, fixture.password);
  await owner.getByRole('tab', { name: t.fieldCustomGroup, exact: true }).click();
  const choice = owner.locator(`#account-profile_${fixture.choiceKey}`);
  await choice.click();
  await expect(owner.locator('.ant-select-item-option-content').filter({ hasText: fixture.choiceLabel })).toBeVisible();
  await owner.keyboard.press('Escape');
  // Client navigation retains the same QueryClient and its still-fresh original option cache.
  await owner.locator('.account-settings-menu a[href="/dashboard/account-sessions"]').click();
  await expect(owner).toHaveURL(/\/dashboard\/account-sessions/);
  binding = await admin.evaluate(async fixture => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const before = await client.api('/platform/settings/profile-fields');
    const dictionary = await client.mutate('/platform/dictionaries', 'POST', {
      code: fixture.dictionaryCode, name: 'QA alternate profile choices', enabled: true, structure: 'FLAT', remark: 'Bound isolated profile cache fixture',
    });
    const item = await client.mutate(`/platform/dictionaries/${dictionary.id}/items`, 'POST', {
      value: 'alternate', label: 'Alternate ' + fixture.choiceLabel, enabled: true, sortOrder: 0, remark: 'Bound isolated profile cache fixture',
    });
    const data = structuredClone(before.data);
    const field = data.fields.find(field => field.key === fixture.choiceKey);
    if (!field || field.label !== fixture.choiceLabel) throw new Error('Unexpected profile fixture');
    field.dictionaryCode = dictionary.code;
    const saved = await client.mutate('/platform/settings/profile-fields', 'PUT', { version: before.version, data, changeReason: 'QA dictionary binding cache validation' });
    return { before, version: saved.version, item, dictionaryCode: dictionary.code };
  }, { choiceKey: fixture.choiceKey, choiceLabel: fixture.choiceLabel, dictionaryCode: 'qa_rebind_' + randomUUID().replaceAll('-', '') });
  const refreshed = owner.waitForResponse(response => new URL(response.url()).pathname === '/api/account/profile-values' && response.request().method() === 'GET');
  await owner.locator('.account-settings-menu a[href="/dashboard/account-security"]').click();
  const response = await refreshed;
  assert.equal(response.status(), 200);
  const projected = await response.json();
  assert.equal(projected.schemaVersion, binding.version);
  assert.equal(projected.fields.find(field => field.key === fixture.choiceKey)?.dictionaryCode, binding.dictionaryCode);
  await owner.getByRole('tab', { name: t.fieldCustomGroup, exact: true }).click();
  await choice.click();
  await expect(owner.locator('.ant-select-item-option-content').filter({ hasText: binding.item.label })).toBeVisible({ timeout: 5000 });
  console.log('PASS: same-browser profile dictionary rebinding immediately loads current choices while the previous binding remains within the option-cache TTL');
} finally {
  if (binding) {
    await admin.evaluate(async binding => {
      const client = window.__lumiraPluginSdk['@/platform/api/client'];
      const current = await client.api('/platform/settings/profile-fields');
      if (current.version !== binding.version) throw new Error('Competing profile binding edit; refusing to overwrite');
      await client.mutate('/platform/settings/profile-fields', 'PUT', { version: current.version, data: binding.before.data, changeReason: 'QA restore original profile binding' });
    }, binding);
  }
  await browser.close();
}
