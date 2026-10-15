import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
let changed = false;
try {
  const anonymous = await context.newPage();
  const pageErrors = [];
  anonymous.on('pageerror', error => pageErrors.push(error));
  await anonymous.goto('/user/login');
  await expect(anonymous.getByRole('button', { name: '使用通行密钥登录', exact: true })).toHaveCount(0);
  await anonymous.close();

  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 200);
  const settings = await (await context.request.get('/api/platform/settings/passkey')).json();
  assert.equal(settings.version, 0);
  assert.equal(settings.data.enabled, false);
  const runtimeNavigation = await (await context.request.get('/api/platform/navigation')).json();
  const runtimeNodes = [];
  const collect = (nodes) => nodes.forEach((node) => { runtimeNodes.push(node); collect(node.children ?? []); });
  collect(runtimeNavigation);
  assert.ok(runtimeNodes.some((node) => node.sectionKey === 'passkey'), 'passkey must be present in the server-owned navigation tree');
  const page = await context.newPage();
  page.on('pageerror', error => pageErrors.push(error));
  await page.goto('/dashboard/passkey');
  await expect(page.getByText('通行密钥设置', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('tab', { name: '通行密钥设置', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('启用通行密钥', { exact: true })).toBeVisible();
  await page.locator('#data_rpName').fill('Passkey browser QA');
  assert.equal(await page.locator('#changeReason').count(), 0, 'Passkey form must not ask for a change reason');
  const save = page.waitForResponse(response => response.url().endsWith('/api/platform/settings/passkey') && response.request().method() === 'PUT');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  assert.equal((await save).status(), 200);
  changed = true;
  assert.equal((await (await context.request.get('/api/platform/settings/passkey')).json()).data.rpName, 'Passkey browser QA');
  await page.reload();
  await expect(page.locator('#data_rpName')).toHaveValue('Passkey browser QA');
  await page.locator('.pro-account-trigger').click(); await page.getByRole('menuitem', { name: '个人资料', exact: true }).click();
  await page.waitForURL('**/dashboard/account-security');
  await page.locator('.account-settings-menu').getByRole('menuitem', { name: '账号安全', exact: true }).click();
  await page.waitForURL('**/dashboard/account-authentication-security');
  const profile = page.locator('.account-settings-panel');
  await expect(profile.getByRole('button', { name: '通行密钥', exact: true })).toBeVisible();
  await profile.getByRole('button', { name: '通行密钥', exact: true }).click();
  const passkeys = page.getByRole('dialog', { name: '通行密钥', exact: true });
  await expect(passkeys).toBeVisible();
  await expect(passkeys.getByText('尚未绑定通行密钥', { exact: true })).toBeVisible();
  assert.deepEqual(pageErrors, []);
  console.log('PASS: disabled feature boundary, passkey settings save/reload, account passkey drawer and route tabs render without page errors');
} finally {
  try {
    if (changed) execFileSync('python3', ['-c', `from smoke_support import sql; sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='passkey'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='passkey'); DELETE FROM platform_setting WHERE section='passkey'")`], { cwd: 'scripts', stdio: 'pipe' });
    const token = await (await context.request.get('/api/auth/csrf')).json();
    await context.request.post('/api/auth/logout', { headers: { [token.headerName]: token.token } });
  } finally {
    await browser.close();
  }
}
