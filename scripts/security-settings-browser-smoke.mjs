import {chromium} from '@playwright/test';
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {observeResponse} from './browser-support.mjs';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? {executablePath: process.env.BROWSER_EXECUTABLE} : {}),
});
try {
  const context = await browser.newContext({
    baseURL: 'http://127.0.0.1:8010', viewport: {width: 1134, height: 869},
  });
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: {username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD},
    headers: {[csrf.headerName]: csrf.token},
  })).status(), 200);
  await context.addCookies([{
    name: 'lumira-next.preferences',
    value: encodeURIComponent(JSON.stringify({dark: true, locale: 'zh-CN'})),
    url: 'http://127.0.0.1:8010',
  }]);

  const initial = await (await context.request.get('/api/platform/settings/security')).json();
  const page = await context.newPage();
  const pageErrors = [];
  const removedRequests = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('request', request => {
    if (/\/settings\/(security|passkey)\/(history|rollback)/.test(request.url())) removedRequests.push(request.url());
  });
  const section = page.locator('.security-settings');
  const checkEditor = async () => {
    await section.locator('#data_passwordMinLength').waitFor();
    assert.equal(await section.locator('#changeReason').count(), 0, 'Security form must not ask for a change reason');
    for (const [name, value] of Object.entries(initial.data)) {
      const control = section.locator(`#data_${name}`);
      if (typeof value === 'boolean') assert.equal(await control.getAttribute('aria-checked'), String(value));
      else if (typeof value === 'number') assert.equal(await control.inputValue(), String(value));
    }
    assert.equal(await section.locator('.form-actions button').count(), 1);
    assert.equal(await section.getByRole('button', {name: '配置历史', exact: true}).count(), 0);
    assert.equal(await section.getByRole('button', {name: '重新载入配置', exact: true}).count(), 0);
    assert.equal(await page.locator('#restore-security').count(), 0);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  };
  await page.goto('/dashboard/security');
  await checkEditor();

  // Save the current values unchanged, proving persistence without changing policy
  // or deleting existing settings, revisions, or audit records during cleanup.
  const saved = observeResponse(page, response =>
    response.url().endsWith('/api/platform/settings/security') && response.request().method() === 'PUT');
  await section.getByRole('button', {name: '保存', exact: true}).click();
  const result = await saved;
  assert.equal(result.status(), 200);
  const snapshot = await result.json();
  assert.equal(snapshot.version, initial.version + 1);
  assert.deepEqual(snapshot.data, initial.data);
  await checkEditor();
  await page.reload();
  await checkEditor();
  assert.deepEqual(await (await context.request.get('/api/platform/settings/security')).json(), snapshot);
  await page.evaluate(() => window.scrollTo({top: 0, behavior: 'instant'}));
  await page.screenshot({path: '.runtime/security-layout-qa/actions-desktop.png', fullPage: true});

  await page.setViewportSize({width: 390, height: 844});
  await page.reload();
  await checkEditor();
  await page.screenshot({path: '.runtime/security-layout-qa/actions-mobile.png', fullPage: true});
  assert.deepEqual(removedRequests, [], 'Security page must never request history or rollback');

  assert.equal((await context.request.get('/api/platform/settings/security/history')).status(), 404);
  assert.equal((await context.request.get(`/api/platform/settings/security/history/${Math.max(1, initial.version)}`)).status(), 404);
  const currentCsrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/platform/settings/security/rollback', {
    data: {version: snapshot.version, sourceVersion: 1, changeReason: 'Removed action must fail'},
    headers: {[currentCsrf.headerName]: currentCsrf.token},
  })).status(), 404);
  assert.deepEqual(await (await context.request.get('/api/platform/settings/security')).json(), snapshot);

  // Passkey configuration keeps its fields and save action, but hides history/reload.
  await page.setViewportSize({width: 1134, height: 869});
  await page.getByRole('tab', {name: '通行密钥设置', exact: true}).click();
  await page.locator('#data_rpName').waitFor();
  for (const field of ['enabled', 'passwordlessEnabled', 'selfBindingEnabled', 'rpId', 'rpName', 'allowedOrigins', 'challengeTtlSeconds']) {
    assert.ok(await page.locator(`#data_${field}`).count() > 0, `Passkey field ${field} must remain available`);
  }
  assert.equal(await page.locator('.form-actions button').count(), 1, 'Passkey save action must remain visible');
  assert.equal(await page.getByRole('main').getByRole('button', {name: '配置历史', exact: true}).count(), 0);
  assert.equal(await page.getByRole('main').getByRole('button', {name: '重新载入配置', exact: true}).count(), 0);
  assert.deepEqual(removedRequests, [], 'Security and passkey pages must not request history or rollback');
  assert.deepEqual(pageErrors, []);
  console.log('PASS: security save-only UI, unchanged policy saved and persisted, no history/reload/restore UI or requests, removed endpoints return 404, passkey fields/save remain available, desktop/mobile layout, no page errors');
} finally {
  await browser.close();
}
