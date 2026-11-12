import assert from 'node:assert/strict';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
const { config, baseURL } = loadQaRunContext();
const t = await loadLocaleMessages('zh-CN');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
async function operate(action) {
  const result = await page.evaluate(async ({ action }) => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const base = '/platform/modules/plugins/file-retention/';
    const plan = await client.mutate(base + action + '-plan', 'POST', {});
    if (plan.blockers.length) throw new Error(JSON.stringify(plan.blockers));
    return client.mutate(base + action, 'POST', { revision: plan.revision, dataMode: 'PRESERVE' });
  }, { action });
  let operation = result;
  for (let attempt = 0; attempt < 240 && !['SUCCEEDED', 'FAILED'].includes(operation.status); attempt++) {
    await page.waitForTimeout(250);
    operation = await page.evaluate(async id => window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/modules/plugins/operations/' + id), result.id);
  }
  assert.equal(operation.status, 'SUCCEEDED', JSON.stringify(operation));
  await page.evaluate(() => window.dispatchEvent(new Event('lumira:modules-changed')));
}
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
  const editor = () => page.locator('.settings-form .ant-select').first();
  await page.goto('/dashboard/files-all#tab=file-retention');
  await expect(editor()).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole('button', { name: t.save, exact: true })).toBeEnabled();
  await operate('uninstall');
  await expect(editor()).toHaveCount(0, { timeout: 20000 });
  assert.equal(new URL(page.url()).hash, '#tab=file-retention');
  await operate('install');
  await expect(editor()).toBeVisible({ timeout: 30000 });
  // Reloading proves the new module is served from the installed package as well.
  await page.goto('/dashboard/files-all#tab=file-retention');
  await expect(editor()).toBeVisible({ timeout: 30000 });
  for (const width of [1213, 390]) {
    await page.setViewportSize({ width, height: 886 });
    await page.goto('/dashboard/file-retention');
    await expect(editor()).toBeVisible({ timeout: 30000 });
    await page.reload();
    await expect(editor()).toBeVisible();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  }
  assert.deepEqual(errors, []);
  console.log('PASS: independent retention page and embedded file-center editor; live uninstall withdraws mounted editor; reinstall/reload restores it; desktop/mobile layout and no uncaught page errors');
} finally {
  await browser.close();
}
