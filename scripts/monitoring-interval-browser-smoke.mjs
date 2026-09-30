import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve, join } from 'node:path';

// Real page, normal UI login, and real summary responses. Only the page clock
// and the failure response are controlled; long intervals are virtual time.
const baseURL = process.env.LUMIRA_QA_URL ?? 'http://127.0.0.1:8010';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(baseURL).hostname), 'Local QA only');
const config = JSON.parse(readFileSync(process.env.LUMIRA_QA_CONFIG ?? '.runtime/local.json', 'utf8'));
const evidenceDir = resolve(process.env.LUMIRA_QA_EVIDENCE ?? '.runtime/monitoring-intervals');
mkdirSync(evidenceDir, { recursive: true });
const browser = await chromium.launch({ headless: true, channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const endpoint = '**/api/platform/monitoring/summary';
const evidence = { mode: 'real UI with Playwright controlled page clock', cases: [], errors: [] };
let requests = 0;
page.on('request', request => { if (request.url().endsWith('/api/platform/monitoring/summary')) requests++; });
page.on('pageerror', error => evidence.errors.push(error.message));
const save = () => writeFileSync(join(evidenceDir, 'result.json'), JSON.stringify(evidence, null, 2));
const settle = () => page.waitForTimeout(200);
const summaryResponse = () => page.waitForResponse(response => response.url().endsWith('/api/platform/monitoring/summary'));
try {
  await page.goto(`${baseURL}/user/login`);
  await page.getByLabel('用户名', { exact: true }).fill('admin');
  await page.getByLabel('密码', { exact: true }).fill(config.LUMIRA_BOOTSTRAP_PASSWORD);
  await page.getByRole('button', { name: /^登\s*录$/ }).click();
  await page.waitForURL('**/dashboard/**');
  const initial = summaryResponse();
  await page.goto(`${baseURL}/dashboard/monitoring`);
  assert.equal((await initial).status(), 200);
  await expect(page.getByRole('heading', { name: '运行监控', exact: true })).toBeVisible();
  const main = page.getByRole('main');
  const select = main.getByRole('combobox').first();
  const choose = async label => {
    await select.click();
    await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText(label, { exact: true }).click();
    await settle();
  };
  // Let the Umi loader and React mount on the native clock first, then cancel
  // existing polling before replacing timers. Freezing before navigation would
  // also freeze module-loader timers and would not exercise the mounted page.
  await choose('手动');
  const now = new Date();
  await page.clock.install({ time: now });
  await page.clock.pauseAt(new Date(now.getTime() + 1000));
  const refresh = async () => {
    const response = summaryResponse();
    await main.getByRole('button', { name: '刷新页面', exact: true }).click();
    const status = (await response).status();
    await settle();
    return status;
  };
  const interval = async (label, milliseconds, status = 200) => {
    const before = requests;
    await page.clock.runFor(milliseconds - 1);
    await settle();
    assert.equal(requests, before, `${label}: no early poll`);
    const response = summaryResponse();
    await page.clock.runFor(1);
    assert.equal((await response).status(), status, `${label}: response at interval`);
    await settle();
    assert.equal(requests, before + 1, `${label}: one poll per interval`);
    evidence.cases.push({ label, milliseconds, noEarlyRequest: true, requests: 1, status });
    save();
  };
  for (const [label, milliseconds] of [
    ['实时（约 3 秒轮询）', 3000], ['每 5 秒', 5000], ['每 10 秒', 10000],
    ['每 30 秒', 30000], ['每 1 分钟', 60000], ['每 5 分钟', 300000],
    ['每 15 分钟', 900000], ['每 1 小时', 3600000],
  ]) {
    await choose(label);
    assert.equal(await refresh(), 200);
    await interval(label, milliseconds);
  }
  await choose('手动');
  const manualBefore = requests;
  await page.clock.runFor(3600000);
  await settle();
  assert.equal(requests, manualBefore, 'Manual: no automatic polling for a virtual hour');
  assert.equal(await refresh(), 200);
  evidence.cases.push({ label: '手动', milliseconds: 3600000, automaticRequests: 0, manualRefreshStatus: 200 });
  await choose('每 5 秒');
  await page.route(endpoint, route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"unavailable"}' }));
  assert.equal(await refresh(), 503);
  for (const milliseconds of [10000, 20000, 40000, 60000, 60000]) {
    await interval('连续失败退避', milliseconds, 503);
  }
  await page.unroute(endpoint);
  await interval('成功恢复前的退避周期', 60000);
  await interval('成功后恢复每 5 秒', 5000);
  let heldRoute;
  await page.route(endpoint, route => { heldRoute = route; });
  const slowStart = requests;
  await page.clock.runFor(5000);
  await expect.poll(() => Boolean(heldRoute)).toBe(true);
  await page.clock.runFor(120000);
  await settle();
  assert.equal(requests, slowStart + 1, 'In-flight request must not create overlapping polls');
  const slowResponse = summaryResponse();
  await heldRoute.continue();
  assert.equal((await slowResponse).status(), 200);
  await page.unroute(endpoint);
  await settle();
  evidence.cases.push({ label: '请求未完成时暂停轮询', milliseconds: 120000, requests: 1 });
  await interval('慢请求结束后恢复每 5 秒', 5000);
  assert.deepEqual(evidence.errors, []);
  evidence.result = 'PASS';
  await page.screenshot({ path: join(evidenceDir, 'returned.png') });
  save();
  console.log(`PASS: ${evidence.cases.length} controlled-clock UI cases; all intervals, manual mode, failure backoff, and recovery`);
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
