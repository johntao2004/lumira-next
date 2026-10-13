import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { observeResponse } from './browser-support.mjs';
import { loadQaRunContext } from './qa-run-guard.mjs';

const { config, baseURL, projectRoot } = loadQaRunContext();
const evidenceDirectory = path.join(projectRoot, '.runtime', 'event-chain-qa');
mkdirSync(evidenceDirectory, { recursive: true });
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
try {
  const context = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const liveFlowResponse = page.waitForResponse(response => response.url().endsWith('/api/platform/monitoring/summary'));
  await page.goto('/dashboard/monitoring');
  const main = page.getByRole('main');
  await expect(main.getByRole('heading', { name: '运行监控', exact: true })).toBeVisible();
  await expect(main.getByText('用户总数', { exact: true })).toBeVisible();
  await expect(main.getByText('在线会话', { exact: true })).toBeVisible();
  await expect(main.getByText('事件队列', { exact: true })).toBeVisible();
  await expect(main.getByText('投递队列', { exact: true })).toBeVisible();

  const liveResponse = await liveFlowResponse;
  assert.equal(liveResponse.status(), 200);
  const liveSnapshot = await liveResponse.json();
  const flow = liveSnapshot.flow;
  assert.ok(flow?.status, 'The new backend must provide an event-flow status');
  assert.ok(['UNCONNECTED', 'IDLE', 'HEALTHY', 'PROCESSING', 'ATTENTION'].includes(flow.status));
  assert.equal(flow.producerTypes, 2);
  assert.equal(flow.consumerTypes, 2);
  assert.equal(flow.connectedTypes, 2);
  assert.equal(flow.unmatchedConsumerTypes, 0);
  assert.deepEqual(flow.missingConsumers, []);
  await expect(main.getByText(/尚未接入|暂无活动|运行正常|处理中|需要关注/, { exact: true }).first()).toBeVisible();
  await page.screenshot({ path: path.join(evidenceDirectory, 'monitoring-live-desktop.png'), fullPage: true });

  await page.route('**/api/platform/monitoring/summary', (route) => route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"unavailable"}' }));
  const failedRefresh = observeResponse(page, (response) => response.url().endsWith('/api/platform/monitoring/summary'));
  await main.getByRole('button', { name: '刷新页面', exact: true }).click();
  assert.equal((await failedRefresh).status(), 503);
  await expect(page.locator('.ant-notification-notice').filter({ hasText: '服务暂时不可用，请稍后重试' })).toBeVisible();
  await page.unroute('**/api/platform/monitoring/summary');
  await main.getByRole('button', { name: '刷新页面', exact: true }).click();
  await expect(page.locator('.ant-notification-notice')).toHaveCount(0);

  // Exercise the rolling-restart window: the old backend flow shape must render
  // as unconnected with a distinct empty-state message instead of throwing.
  await page.route('**/api/platform/monitoring/summary', async (route) => {
    const response = await route.fetch();
    const snapshot = await response.json();
    snapshot.flow = { createdLast5m: 0, deliveredLast5m: 0, recentEvents: [] };
    await route.fulfill({ response, body: JSON.stringify(snapshot) });
  });
  await page.reload();
  await expect(main.getByText('尚未接入', { exact: true }).first()).toBeVisible();
  await expect(main.getByText('业务事件尚未接入可靠投递链路', { exact: true })).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  await page.screenshot({ path: path.join(evidenceDirectory, 'monitoring-legacy-mobile.png'), fullPage: true });
  await page.unroute('**/api/platform/monitoring/summary');
  assert.deepEqual(errors, []);
  console.log('PASS: QA-guarded monitoring status/topology, old-flow fallback, failed refresh/retry, mobile no-overflow and no pageerror');
  await context.close();
} finally {
  await browser.close();
}
