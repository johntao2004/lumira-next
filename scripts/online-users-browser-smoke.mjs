import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { observeResponse } from './browser-support.mjs';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const id = randomUUID();
const username = `qa_online_${id.replaceAll('-', '')}`;
const displayName = 'Online user browser QA';
const sql = (statement) => execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'], {
  cwd: 'scripts', input: statement, stdio: ['pipe', 'pipe', 'pipe'],
}).toString().trim();
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });

async function login(context, user, password) {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  return context.request.post('/api/auth/login', {
    form: { username: user, password },
    headers: { [csrf.headerName]: csrf.token },
  });
}

try {
  sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${id}','${username}','${displayName}',password_hash FROM platform_user WHERE username='admin'`);
  const admin = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
  const online = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
  assert.equal((await login(admin, 'admin', config.LUMIRA_BOOTSTRAP_PASSWORD)).status(), 200);
  assert.equal((await login(online, username, config.LUMIRA_BOOTSTRAP_PASSWORD)).status(), 200);

  const page = await admin.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/dashboard/online-users');
  const main = page.getByRole('main');
  await expect(main.getByRole('heading', { name: '在线用户', exact: true })).toBeVisible();
  await expect(main.getByRole('table')).toBeVisible();

  const search = main.getByPlaceholder('搜索用户或账号', { exact: true });
  await search.fill(username);
  await search.press('Enter');
  const row = main.locator('tbody tr[data-row-key]').filter({ hasText: username });
  await expect(row).toHaveCount(1);
  await expect(row.getByText(displayName, { exact: true })).toBeVisible();
  await expect(row.getByText('正常', { exact: true })).toBeVisible();
  await expect(row.getByRole('button', { name: '强制下线', exact: true })).toBeVisible();

  await row.getByRole('button', { name: '强制下线', exact: true }).click();
  const confirmation = page.locator('.ant-popconfirm').filter({ hasText: `确定让“${username}”的这个会话下线？` });
  await expect(confirmation).toBeVisible();
  const revoke = page.waitForResponse((response) => response.url().endsWith('/api/platform/online-users/' + response.url().split('/').pop()) && response.request().method() === 'DELETE');
  await confirmation.getByRole('button', { name: '强制下线', exact: true }).click();
  assert.equal((await revoke).status(), 204);
  await expect(row).toHaveCount(0);
  assert.equal((await online.request.get('/api/auth/me')).status(), 401);

  await page.route('**/api/platform/online-users?**', (route) => route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"unavailable"}' }));
  const failedRefresh = observeResponse(page, (response) => response.url().includes('/api/platform/online-users?'));
  await main.getByRole('button', { name: '刷新页面', exact: true }).click();
  assert.equal((await failedRefresh).status(), 503);
  await expect(page.locator('.ant-message-notice')).toBeVisible();
  await page.unroute('**/api/platform/online-users?**');
  await main.getByRole('button', { name: '刷新页面', exact: true }).click();
  await expect(page.locator('.ant-message-notice')).toHaveCount(0);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log('PASS: online-user route, global navigation access, search and safe projection, admin revoke, revoked-session denial, refresh failure/retry and no overflow/pageerror');
  await Promise.all([admin.close(), online.close()]);
} finally {
  await browser.close();
  sql(`DELETE FROM spring_session WHERE principal_name='${username}'; DELETE FROM platform_audit WHERE actor='${username}' OR target_id='${id}'; DELETE FROM platform_user WHERE id='${id}'`);
}
