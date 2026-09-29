import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const sql = (statement) => execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'], {
  cwd: 'scripts', input: statement, stdio: ['pipe', 'pipe', 'pipe'],
}).toString().trim();
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
let created;
const secretContext = () => created ? `settings:${created}:sms-provider-secret` : null;

try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 200);
  const initial = await (await context.request.get('/api/platform/settings/sms')).json();
  assert.equal(initial.version, 0, 'Refuse to overwrite configured SMS settings');
  assert.equal(initial.data.enabled, false);
  assert.equal(initial.encryptionConfigured, true);

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/dashboard/sms');
  const main = page.getByRole('main');
  await expect(page.getByRole('tab', { name: '短信服务', exact: true })).toHaveAttribute('aria-selected', 'true');
  await main.locator('#data_endpoint').fill('https://sms.example.test/send');
  await main.locator('#data_sender').fill('Lumira QA');
  await main.locator('#data_template').fill('QA code: {code}');
  await main.getByRole('combobox', { name: '访问密钥操作', exact: true }).click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText('更换密钥', { exact: true }).click();
  await main.getByLabel('密码', { exact: true }).fill('sms-browser-fixture');
  await main.locator('#changeReason').fill('SMS browser configuration');
  const save = page.waitForResponse(response => response.url().endsWith('/api/platform/settings/sms') && response.request().method() === 'PUT');
  await main.getByRole('button', { name: '保存', exact: true }).click();
  const saved = await save;
  assert.equal(saved.status(), 200, await saved.text());
  created = sql("SELECT id FROM platform_setting WHERE section='sms'");
  assert.ok(created);
  await expect(main.getByText('访问密钥已配置', { exact: true })).toBeVisible();
  await expect(main.getByLabel('密码', { exact: true })).toHaveCount(0);
  await page.reload();
  await expect(main.getByText('访问密钥已配置', { exact: true })).toBeVisible();
  assert.equal(await main.locator('#data_endpoint').inputValue(), 'https://sms.example.test/send');

  await main.getByRole('button', { name: '配置历史', exact: true }).click();
  await page.getByRole('row').filter({ hasText: 'SMS browser configuration' }).getByRole('button', { name: '详情', exact: true }).click();
  const details = page.locator('.ant-modal').last();
  await expect(details.locator('pre')).toContainText('https://sms.example.test/send');
  assert.equal(await details.locator('pre').innerText().then(value => value.includes('sms-browser-fixture')), false);
  const restore = page.locator('#restore-sms');
  await restore.getByRole('combobox', { name: '访问密钥操作', exact: true }).click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText('更换密钥', { exact: true }).click();
  await restore.getByLabel('密码', { exact: true }).fill('sms-browser-restored');
  await restore.locator('#restore-sms_changeReason').fill('SMS browser history restore');
  const rollback = page.waitForResponse(response => response.url().endsWith('/api/platform/settings/sms/rollback'));
  await page.locator('button[form="restore-sms"]').click();
  const restored = await rollback;
  assert.equal(restored.status(), 200, await restored.text());
  assert.equal((await restored.json()).version, 2);
  await page.reload();
  const reloaded = await (await context.request.get('/api/platform/settings/sms')).json();
  assert.equal(reloaded.data.endpoint, 'https://sms.example.test/send');
  assert.equal(reloaded.credentialConfigured, true);
  assert.deepEqual(errors, []);
  console.log('PASS: SMS webhook settings save/reload, encrypted write-only key, non-secret history, explicit-key rollback; delivery stays disabled and no SMS is sent');
} finally {
  await browser.close();
  if (created) sql(`DELETE FROM platform_secret WHERE secret_context='${secretContext()}'; DELETE FROM platform_setting_revision WHERE setting_id='${created}'; DELETE FROM platform_audit WHERE target_id='${created}'; DELETE FROM platform_setting WHERE id='${created}'`);
}
