import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

// This disposable compose project has its own database and loopback edge port.
const baseURL = 'http://127.0.0.1:8026';
const config = JSON.parse(readFileSync('.runtime/docker-closeout-20260930/credentials.json', 'utf8'));
const evidenceDir = resolve('.runtime/qa/compose-closeout');
mkdirSync(evidenceDir, { recursive: true });
const browser = await chromium.launch({ headless: true, channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const evidence = { date: '2026-09-30', environment: 'disposable compose edge 8026', pageErrors: [], consoleErrors: [], checks: [] };
const title = 'QA_COMPOSE_PERSISTENCE_20260930';
const save = () => writeFileSync(join(evidenceDir, 'browser.json'), JSON.stringify(evidence, null, 2));
page.on('pageerror', error => evidence.pageErrors.push(error.message));
page.on('console', message => { if (message.type() === 'error') evidence.consoleErrors.push(message.text()); });
try {
  const response = await page.goto(`${baseURL}/user/login`);
  assert.equal(response.status(), 200);
  const headers = response.headers();
  for (const header of ['content-security-policy', 'x-content-type-options', 'x-frame-options', 'referrer-policy']) assert.ok(headers[header], header);
  evidence.checks.push({ name: 'static route/security headers', status: response.status(), csp: headers['content-security-policy'] });
  await page.getByLabel('用户名', { exact: true }).fill('admin');
  await page.getByLabel('密码', { exact: true }).fill(config.LUMIRA_BOOTSTRAP_PASSWORD);
  await page.getByRole('button', { name: /^登\s*录$/ }).click();
  await page.waitForURL('**/dashboard/**');
  // Anonymous session discovery can emit an expected 401 before login. Record
  // subsequent dashboard console errors separately from that bootstrap check.
  evidence.anonymousConsoleErrors = evidence.consoleErrors;
  evidence.consoleErrors = [];
  evidence.checks.push({ name: 'normal UI login', route: new URL(page.url()).pathname });
  const profileResponse = await page.context().request.get(`${baseURL}/api/account/profile-values`);
  assert.equal(profileResponse.status(), 200);
  assert.equal(typeof (await profileResponse.json()).dictionarySelections, 'object');
  evidence.checks.push({ name: 'current backend profile metadata contract', status: 200 });
  const listResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/feedback/manage' && response.ok());
  await page.goto(`${baseURL}/dashboard/feedback`);
  const list = await (await listResponse).json();
  await expect(page.getByRole('heading', { name: '工单反馈', exact: true })).toBeVisible();
  const existing = page.locator('.ant-table-tbody tr').filter({ hasText: title });
  let expectedRows = list.items.filter(item => item.title === title).length;
  if (expectedRows === 0) {
    await page.getByRole('button', { name: '提交反馈', exact: true }).click();
    await page.locator('#feedback-create input[id="title"]').fill(title);
    await page.locator('[contenteditable="true"]').fill('Disposable compose UI persistence evidence.');
    const posted = page.waitForResponse(response => response.url().endsWith('/api/feedback') && response.request().method() === 'POST');
    await page.locator('button[form="feedback-create"]').click();
    assert.ok((await posted).ok());
    expectedRows = 1;
  }
  await expect(existing).toHaveCount(expectedRows);
  await page.reload();
  await expect(existing).toHaveCount(expectedRows);
  evidence.checks.push({ name: 'feedback UI persistence after reload', title, rows: expectedRows });
  for (const path of ['/actuator', '/actuator/health', '/actuator/prometheus']) {
    const result = await page.request.get(`${baseURL}${path}`);
    assert.equal(result.status(), 404);
    evidence.checks.push({ name: 'edge operational endpoint denied', path, status: result.status() });
  }
  assert.deepEqual(evidence.pageErrors, []);
  assert.deepEqual(evidence.consoleErrors, []);
  await page.screenshot({ path: join(evidenceDir, 'feedback-persistence.png') });
  evidence.result = 'PASS';
  save();
  console.log('PASS: compose production static site, CSP, normal UI login, feedback persistence, operational endpoints denied');
} catch (error) {
  evidence.result = 'FAIL';
  evidence.failure = error.message;
  save();
  throw error;
} finally {
  try {
    const token = await (await page.context().request.get(`${baseURL}/api/auth/csrf`)).json();
    evidence.cleanupLogoutStatus = (await page.context().request.post(`${baseURL}/api/auth/logout`, { headers: { [token.headerName]: token.token } })).status();
  } catch { evidence.cleanupLogoutFailed = true; }
  save();
  await page.context().request.dispose();
  await page.context().close();
  await browser.close();
}
