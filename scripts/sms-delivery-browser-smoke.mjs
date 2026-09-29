import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { observeResponse } from './browser-support.mjs';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const sql = (statement) => execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'], { cwd: 'scripts', input: statement, stdio: ['pipe', 'pipe', 'pipe'] }).toString().trim();
const ids = [randomUUID(), randomUUID(), randomUUID()];
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
try {
  for (let i = 0; i < ids.length; i++) sql(`INSERT INTO platform_sms_delivery(id,payload,state,attempts,expires_at,last_result) VALUES('${ids[i]}','v1:fixture:abcdef','${['FAILED', 'ACCEPTED', 'UNCERTAIN'][i]}',1,now()+interval '1 hour','${['AUTHENTICATION_FAILED', 'ACCEPTED', 'DELIVERY_UNCERTAIN'][i]}')`);
  const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
  const token = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [token.headerName]: token.token } })).status(), 200);
  const page = await context.newPage(), errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/dashboard/sms-deliveries');
  const main = page.getByRole('main');
  await expect(main.locator(`[data-row-key="${ids[0]}"]`)).toBeVisible();
  await main.getByRole('combobox', { name: '状态', exact: true }).click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText('投递失败', { exact: true }).click();
  await expect(main.locator(`[data-row-key="${ids[1]}"]`)).toHaveCount(0);
  await expect(main.locator(`[data-row-key="${ids[0]}"]`)).toBeVisible();
  const search = main.getByPlaceholder('搜索任务 ID', { exact: true });
  await search.fill(ids[0]); await search.press('Enter');
  await expect(main.locator('tbody tr[data-row-key]')).toHaveCount(1);
  await page.route('**/api/platform/sms-deliveries?**', (route) => route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"unavailable"}' }));
  const failure = observeResponse(page, (response) => response.url().includes('/api/platform/sms-deliveries?'));
  await main.getByRole('button', { name: '刷新页面', exact: true }).click();
  assert.equal((await failure).status(), 503);
  await expect(page.locator('.ant-message-notice')).toBeVisible();
  await expect(main.locator(`[data-row-key="${ids[0]}"]`)).toBeVisible();
  await page.unroute('**/api/platform/sms-deliveries?**');
  await main.getByRole('button', { name: '刷新页面', exact: true }).click();
  await expect(page.locator('.ant-message-notice')).toHaveCount(0);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log('PASS: SMS status rows, filter, task-ID search, failed refresh preserves rows, successful retry, no pageerror or desktop overflow; no messages sent');
} finally {
  await browser.close();
  sql(`DELETE FROM platform_sms_delivery WHERE id IN (${ids.map((id) => `'${id}'`).join(',')})`);
}
