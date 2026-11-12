import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';

const { config, baseURL } = loadQaRunContext();
const fixture = JSON.parse(readFileSync('.runtime/architecture-fix/file-console-fixture.json', 'utf8'));
assert.equal(fixture.runToken, config.LUMIRA_QA_RUN_TOKEN);
const t = await loadLocaleMessages('zh-CN');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
async function quota(method = 'GET', data) {
  return page.evaluate(async ({ method, data }) => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    return method === 'GET' ? client.api('/platform/settings/file-quota')
      : client.mutate('/platform/settings/file-quota', method, data);
  }, { method, data });
}
async function operate(action) {
  const result = await page.evaluate(async action => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const base = '/platform/modules/plugins/file-console/';
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
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
  await page.goto('/dashboard/files-all');
  const search = page.getByRole('textbox', { name: new RegExp('^' + t.fileName + '\\s*[:：]?$') });
  await expect(search).toBeVisible({ timeout: 30000 });
  await search.fill(fixture.filename);
  await page.getByRole('button', { name: /查\s*询/ }).click();
  const row = page.getByRole('row').filter({ hasText: fixture.filename });
  await expect(row).toBeVisible({ timeout: 30000 });
  await row.getByRole('button', { name: fixture.filename, exact: true }).click();
  await expect(page.locator('.ant-drawer').getByText(fixture.content, { exact: true })).toBeVisible({ timeout: 30000 });
  await page.keyboard.press('Escape');
  await expect(page.locator('.ant-drawer-open')).toHaveCount(0);
  await row.getByRole('button', { name: t.downloadFile, exact: true }).click();
  const pending = page.waitForEvent('download');
  await page.locator('.ant-popconfirm').getByRole('button', { name: new RegExp(t.downloadFile.split('').join('\\s*')) }).click();
  const download = await pending;
  assert.equal(download.suggestedFilename(), fixture.filename);
  const stream = await download.createReadStream();
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  assert.equal(Buffer.concat(chunks).toString(), fixture.content);

  await page.goto('/dashboard/files-all#tab=file-quota');
  const count = page.getByRole('spinbutton', { name: new RegExp('^' + t.fileQuotaFileCountLimit + '\\s*[:：]?$') });
  await expect(count).toBeVisible({ timeout: 30000 });
  const original = await quota();
  const nextCount = original.data.maximumFiles > 0 ? original.data.maximumFiles + 1 : 2345;
  const unlimited = page.getByRole('checkbox', { name: t.fileQuotaFileCountUnlimited, exact: true });
  if (await unlimited.isChecked()) await unlimited.uncheck();
  await count.fill(String(nextCount));
  await count.blur();
  const saved = page.waitForResponse(response => response.url().endsWith('/api/platform/settings/file-quota') && response.request().method() === 'PUT');
  await page.getByRole('button', { name: t.save, exact: true }).click();
  assert.equal((await saved).status(), 200);
  const current = await quota();
  assert.equal(current.data.maximumFiles, nextCount);
  assert.equal(current.data.maximumBytes, original.data.maximumBytes);
  await page.reload();
  await expect(count).toHaveValue(String(nextCount), { timeout: 30000 });
  await quota('PUT', { version: current.version, data: original.data });
  await operate('uninstall');
  await expect(count).toHaveCount(0, { timeout: 20000 });
  assert.equal(new URL(page.url()).pathname, '/dashboard/files-all');
  assert.equal(new URL(page.url()).hash, '#tab=file-quota');
  await operate('install');
  await expect(count).toBeVisible({ timeout: 30000 });
  assert.deepEqual((await quota()).data, original.data);
  for (const width of [1213, 390]) {
    await page.setViewportSize({ width, height: 886 });
    await page.reload();
    await expect(count).toBeVisible({ timeout: 30000 });
    if (original.data.maximumFiles > 0) await expect(count).toHaveValue(String(original.data.maximumFiles));
    else await expect(unlimited).toBeChecked();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  }
  assert.deepEqual(errors, []);
  console.log('PASS: independently loaded file console searches retained file, previews and downloads exact bytes; quota UI saves and survives reload; live uninstall/reinstall restores mounted quota route and defaults; desktop/mobile and no uncaught page errors');
} catch (error) {
  mkdirSync('.runtime/browser-failures', { recursive: true });
  await page.screenshot({ path: '.runtime/browser-failures/file-console-plugin.png', fullPage: true });
  writeFileSync('.runtime/browser-failures/file-console-plugin.txt', await page.locator('body').innerText());
  throw error;
} finally {
  await browser.close();
}
