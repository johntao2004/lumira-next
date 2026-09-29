import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { observeResponse } from './browser-support.mjs';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
try {
  const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/dashboard/monitoring');
  const main = page.getByRole('main');
  await expect(main.getByRole('heading', { name: '运行监控', exact: true })).toBeVisible();
  await expect(main.getByText('用户总数', { exact: true })).toBeVisible();
  await expect(main.getByText('在线会话', { exact: true })).toBeVisible();
  await expect(main.getByText('事件队列', { exact: true })).toBeVisible();
  await expect(main.getByText('投递队列', { exact: true })).toBeVisible();
  await page.route('**/api/platform/monitoring/summary', (route) => route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"unavailable"}' }));
  const failedRefresh = observeResponse(page, (response) => response.url().endsWith('/api/platform/monitoring/summary'));
  await main.getByRole('button', { name: '刷新页面', exact: true }).click();
  assert.equal((await failedRefresh).status(), 503);
  await expect(page.locator('.ant-notification-notice').filter({ hasText: '服务暂时不可用，请稍后重试' })).toBeVisible();
  await page.unroute('**/api/platform/monitoring/summary');
  await main.getByRole('button', { name: '刷新页面', exact: true }).click();
  await expect(page.locator('.ant-notification-notice')).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log('PASS: monitoring cards, queue health, failed refresh/retry, mobile no-overflow and no pageerror');
  await context.close();
} finally {
  await browser.close();
}
