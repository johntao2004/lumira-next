import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
const code = `qa.browser.sensitive.${Date.now()}`;
const word = `qa-sensitive-${Date.now()}`;

async function write(path, method, data) {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  return context.request.fetch(`/api${path}`, { method, data, headers: { [csrf.headerName]: csrf.token } });
}

try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  const login = await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(login.status(), 200);

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/dashboard/sensitive-words');
  const main = page.getByRole('main');
  await expect(main.getByRole('heading', { name: '敏感词策略' })).toBeVisible();
  await main.getByRole('tab', { name: '共享词库', exact: true }).click();
  await expect(main.getByRole('button', { name: '新增敏感词', exact: true })).toBeVisible();
  await expect(main.getByRole('button', { name: '导入敏感词', exact: true })).toBeVisible();

  await main.getByRole('button', { name: '新增敏感词', exact: true }).click();
  const editor = page.getByRole('dialog', { name: '新增敏感词', exact: true });
  await expect(editor).toBeVisible();
  await editor.getByLabel('敏感词', { exact: true }).fill(word);
  await editor.getByText('策略与级别（高级）', { exact: true }).click();
  await editor.locator('.ant-select').filter({ has: page.locator('#sensitive-word-editor_action') }).click();
  await page.keyboard.press('ArrowUp');
  await page.keyboard.press('Enter');
  await editor.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(editor).not.toBeVisible();
  const row = main.locator('tbody tr[data-row-key]').filter({ hasText: word });
  await expect(row).toHaveCount(1);

  await main.getByRole('tab', { name: '策略试运行', exact: true }).click();
  const check = main.getByLabel('待检查内容', { exact: true });
  await check.fill(`text ${word}`);
  await main.getByRole('button', { name: '检查内容', exact: true }).click();
  await expect(main.getByText('检测到敏感内容，提交会被阻断', { exact: true })).toBeVisible();

  await main.getByRole('tab', { name: '共享词库', exact: true }).click();
  await row.getByRole('button', { name: '停用', exact: true }).click();
  await expect(row.getByText('停用', { exact: true })).toBeVisible();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log(`PASS: sensitive-word browser page create/check/status, responsive layout and no pageerror (${code})`);
} finally {
  const rows = await context.request.get(`/api/platform/sensitive-words?search=${encodeURIComponent(word)}`);
  if (rows.ok()) {
    for (const row of (await rows.json()).items ?? []) {
      await write(`/platform/sensitive-words/${row.id}?version=${row.version}`, 'DELETE', {});
    }
  }
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  await context.request.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } });
  await context.close();
  await browser.close();
}
