import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
  const state = await context.request.get('/api/account/phone'); assert.equal(state.status(), 200);
  const mutationCsrf = await (await context.request.get('/api/auth/csrf')).json();
  const unavailable = await context.request.post('/api/account/phone/request', { data: { version: 0, phone: '+8613800138000', currentPassword: config.LUMIRA_BOOTSTRAP_PASSWORD, locale: 'zh-CN' }, headers: { [mutationCsrf.headerName]: mutationCsrf.token } });
  assert.equal(unavailable.status(), 503); assert.equal((await unavailable.json()).detail, 'smsUnavailable');
  const page = await context.newPage(); const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto('/dashboard/sms'); await expect(page.getByRole('heading', { name: '短信服务', exact: true })).toBeVisible();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  await page.goto('/dashboard/home'); await page.locator('.pro-account-trigger').click(); await page.getByRole('menuitem', { name: '个人资料', exact: true }).click(); await page.waitForURL('**/dashboard/account-profile');
  const profile = page.locator('.account-settings-panel'); await profile.getByRole('button', { name: '账号手机', exact: true }).click();
  const phone = page.getByRole('dialog', { name: '账号手机', exact: true }); await expect(phone.getByText('已验证手机号: 尚未验证', { exact: true })).toBeVisible();
  let submissions = 0;
  await page.route('**/api/account/phone/request', async route => { submissions++; await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'smsUnavailable', message: 'smsUnavailable' }) }); });
  await phone.getByLabel('账号手机', { exact: true }).fill('+8613800138000'); await phone.getByLabel('当前密码', { exact: true }).fill('fixture-password');
  await phone.getByRole('button', { name: '发送验证码短信', exact: true }).click();
  await expect(page.locator('.ant-message-notice').filter({ hasText: '短信服务未就绪，请联系管理员配置短信服务商。' })).toBeVisible();
  await expect(phone.getByLabel('当前密码', { exact: true })).toHaveValue(''); assert.equal(submissions, 1);
  await phone.getByRole('button', { name: '关闭', exact: true }).click(); await expect(phone).not.toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 }); await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  assert.deepEqual(errors, []);
  console.log('PASS: SMS settings route, account phone drawer, unavailable-provider feedback, password clearing and mobile bounds; no SMS sent');
} finally {
  try { const csrf = await (await context.request.get('/api/auth/csrf')).json(); await context.request.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } }); } finally { await browser.close(); }
}
