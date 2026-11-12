import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
const { config, baseURL } = loadQaRunContext();
const fixture = JSON.parse(readFileSync('.runtime/architecture-fix/personal-files-fixture.json', 'utf8'));
assert.equal(fixture.runToken, config.LUMIRA_QA_RUN_TOKEN);
const t = await loadLocaleMessages('zh-CN');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const adminContext = await browser.newContext({ baseURL, locale: 'zh-CN' });
const ownerContext = await browser.newContext({ baseURL, locale: 'zh-CN', viewport: { width: 1213, height: 886 } });
const admin = await adminContext.newPage();
const page = await ownerContext.newPage();
const home = await ownerContext.newPage();
const errors = [];
for (const target of [admin, page, home]) target.on('pageerror', error => errors.push(error.message));
async function login(context, username, password) {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username, password }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
}
async function operate(action) {
  let operation = await admin.evaluate(async action => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const base = '/platform/modules/plugins/personal-files/';
    const plan = await client.mutate(base + action + '-plan', 'POST', {});
    if (plan.blockers.length) throw new Error(JSON.stringify(plan.blockers));
    return client.mutate(base + action, 'POST', { revision: plan.revision, dataMode: 'PRESERVE' });
  }, action);
  for (let attempt = 0; attempt < 240 && !['SUCCEEDED', 'FAILED'].includes(operation.status); attempt++) {
    await admin.waitForTimeout(250);
    operation = await admin.evaluate(async id => window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/modules/plugins/operations/' + id), operation.id);
  }
  assert.equal(operation.status, 'SUCCEEDED', JSON.stringify(operation));
  for (const target of [admin, page, home]) await target.evaluate(() => window.dispatchEvent(new Event('lumira:modules-changed')));
}
try {
  await login(adminContext, 'admin', config.LUMIRA_BOOTSTRAP_PASSWORD);
  await login(ownerContext, fixture.username, fixture.password);
  await admin.goto('/dashboard/modules');
  await page.goto('/dashboard/files');
  const row = page.getByRole('row').filter({ hasText: fixture.filename });
  await expect(row).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole('button', { name: t.uploadFile, exact: true })).toBeVisible();
  await home.goto('/dashboard/home');
  const summary = home.getByRole('region', { name: t.fileUsage, exact: true });
  await expect(summary).toBeVisible({ timeout: 30000 });
  await expect(home.getByRole('button', { name: t.uploadFile, exact: true })).toHaveCount(0);
  await operate('uninstall');
  await expect(row).toHaveCount(0, { timeout: 20000 });
  await expect(page.getByRole('button', { name: t.uploadFile, exact: true })).toHaveCount(0);
  await expect(summary).toHaveCount(0, { timeout: 20000 });
  assert.equal(new URL(page.url()).pathname, '/dashboard/files');
  const downloaded = await admin.evaluate(async id => {
    const blob = await window.__lumiraPluginSdk['@/platform/api/client'].apiBlob('/platform/files/' + id + '/content');
    return blob.text();
  }, fixture.fileId);
  assert.equal(downloaded, fixture.content);
  await operate('install');
  await expect(row).toBeVisible({ timeout: 30000 });
  await expect(summary).toBeVisible({ timeout: 30000 });
  for (const width of [1213, 390]) {
    await page.setViewportSize({ width, height: 886 });
    await page.reload();
    await expect(row).toBeVisible({ timeout: 30000 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false, 'Personal files document overflow ' + width);
  }
  assert.deepEqual(errors, []);
  console.log('PASS: independently loaded personal file UI withdraws/reinstalls while mounted; retained file reappears after reinstall and reload; independent administration downloads original bytes while personal UI is absent; desktop/mobile no document overflow or page errors');
} catch (error) {
  mkdirSync('.runtime/browser-failures', { recursive: true });
  await page.screenshot({ path: '.runtime/browser-failures/personal-files-plugin.png', fullPage: true });
  writeFileSync('.runtime/browser-failures/personal-files-plugin.txt', await page.locator('body').innerText());
  throw error;
} finally {
  await browser.close();
}
