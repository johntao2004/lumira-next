import {chromium} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {observeResponse} from './browser-support.mjs';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const sql = statement => execFileSync(
  'python3',
  ['-c', 'import sys; from smoke_support import sql; sql(sys.stdin.read())'],
  {cwd: 'scripts', input: statement, stdio: ['pipe', 'pipe', 'pipe']},
);
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? {executablePath: process.env.BROWSER_EXECUTABLE} : {}),
});
const context = await browser.newContext({baseURL: 'http://127.0.0.1:8010', viewport: {width: 1213, height: 886}});
let changed = false;
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal(
    (await context.request.post('/api/auth/login', {
      form: {username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD},
      headers: {[csrf.headerName]: csrf.token},
    })).status(),
    200,
  );

  const initial = await (await context.request.get('/api/platform/settings/security')).json();
  assert.equal(initial.version, 0, 'Refuse to overwrite configured security policy');
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error));
  await page.goto('/dashboard/security');
  await page.locator('#data_passwordMinLength').waitFor();
  assert.equal(await page.locator('#data_passwordMinLength').inputValue(), '12');
  assert.equal(await page.locator('#data_verificationCodeExpireSeconds').inputValue(), '600');
  assert.equal(await page.locator('#data_verificationCodeCooldownSeconds').inputValue(), '60');
  assert.ok(await page.getByText('密码策略', {exact: true}).count() > 0);
  assert.ok(await page.getByText('登录与找回限流', {exact: true}).count() > 0);
  assert.ok(await page.getByText('验证码策略', {exact: true}).count() > 0);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1));

  await page.locator('#data_passwordMinLength').fill('14');
  await page.locator('#data_passwordRequireUppercase').click();
  await page.locator('#data_verificationCodeExpireSeconds').fill('900');
  await page.locator('#data_verificationCodeCooldownSeconds').fill('90');
  await page.locator('#changeReason').fill('Security browser first');
  let saved = observeResponse(page, response => response.url().endsWith('/api/platform/settings/security') && response.request().method() === 'PUT');
  await page.locator('button[type="submit"]').click();
  let result = await saved;
  assert.equal(result.status(), 200);
  let snapshot = await result.json();
  assert.equal(snapshot.version, 1);
  assert.equal(snapshot.data.passwordMinLength, 14);
  assert.equal(snapshot.data.passwordRequireUppercase, true);
  assert.equal(snapshot.data.verificationCodeExpireSeconds, 900);
  assert.equal(snapshot.data.verificationCodeCooldownSeconds, 90);
  changed = true;
  await page.locator('.ant-message-notice').filter({hasText:'配置已保存'}).waitFor();

  await page.locator('#data_passwordMinLength').fill('16');
  await page.locator('#changeReason').fill('Security browser second');
  saved = observeResponse(page, response => response.url().endsWith('/api/platform/settings/security') && response.request().method() === 'PUT');
  await page.locator('button[type="submit"]').click();
  result = await saved;
  assert.equal(result.status(), 200);
  snapshot = await result.json();
  assert.equal(snapshot.version, 2);
  assert.equal(snapshot.data.passwordMinLength, 16);

  await page.getByRole('button', {name: '配置历史', exact: true}).click();
  await page.getByRole('row').filter({hasText: 'Security browser first'}).getByRole('button', {name: '详情', exact: true}).click();
  await page.locator('#restore-security_changeReason').fill('Security browser restore');
  const rollback = observeResponse(page, response => response.url().endsWith('/api/platform/settings/security/rollback') && response.request().method() === 'POST');
  await page.locator('button[form="restore-security"]').click();
  const restored = await rollback;
  assert.equal(restored.status(), 200);
  const restoredSnapshot = await restored.json();
  assert.equal(restoredSnapshot.version, 3);
  assert.equal(restoredSnapshot.data.passwordMinLength, 14);
  assert.equal(restoredSnapshot.data.verificationCodeExpireSeconds, 900);
  assert.equal(restoredSnapshot.data.verificationCodeCooldownSeconds, 90);
  assert.equal(await page.locator('#data_passwordMinLength').inputValue(), '14');

  await page.reload();
  await page.locator('#data_passwordMinLength').waitFor();
  assert.equal(await page.locator('#data_passwordMinLength').inputValue(), '14');
  assert.equal(await page.locator('#data_passwordRequireUppercase').getAttribute('aria-checked'), 'true');
  assert.equal(pageErrors.length, 0, pageErrors.map(error => error.message).join('\n'));
  console.log('PASS: security policy page loads all sections, saves typed password/login/verification policy, records history, restores a prior version, survives reload, and has no page errors or horizontal overflow');
} finally {
  if (changed) {
    sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='security'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='security'); DELETE FROM platform_setting WHERE section='security'");
  }
  await browser.close();
}
