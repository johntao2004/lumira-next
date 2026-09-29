import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
const key = `qa.browser.localization.${Date.now()}`;
const emptyKey = `${key}.empty`;
let created;
let emptyCreated;

async function write(path, method, data) {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  return context.request.fetch(`/api${path}`, { method, data, headers: { [csrf.headerName]: csrf.token } });
}

try {
  let csrf = await (await context.request.get('/api/auth/csrf')).json();
  const login = await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(login.status(), 200);

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const response = await page.goto('/dashboard/localization');
  assert.equal(response?.status(), 200);
  const main = page.getByRole('main');
  await expect(main.getByRole('heading', { name: '本地化管理', exact: true })).toBeVisible();
  await expect(main.getByRole('button', { name: '新增文案', exact: true })).toBeVisible();
  await expect(main.getByRole('button', { name: '导入文案', exact: true })).toBeVisible();

  await main.getByRole('button', { name: '新增文案', exact: true }).click();
  const editor = page.getByRole('dialog', { name: '新增文案', exact: true });
  await editor.getByLabel('消息键').fill(emptyKey);
  await editor.getByLabel('默认文案').fill('无翻译默认值');
  await editor.getByRole('button', { name: '保存', exact: true }).click();
  await expect(editor).not.toBeVisible();
  const emptyRows = main.locator('tbody tr[data-row-key]').filter({ hasText: emptyKey });
  await expect(emptyRows).toHaveCount(1);
  const emptyList = await (await context.request.get(`/api/platform/localization/entries?search=${encodeURIComponent(emptyKey)}`)).json();
  emptyCreated = emptyList.items?.[0];
  assert.ok(emptyCreated?.id);

  const create = await write('/platform/localization/entries', 'POST', {
    messageKey: key,
    namespace: 'platform',
    defaultMessage: '浏览器默认文案',
    translations: { en: 'Browser runtime copy', 'zh-TW': '瀏覽器執行時文案' },
    enabled: true,
  });
  assert.equal(create.status(), 201, await create.text());
  created = await create.json();
  const publish = await write('/platform/localization/publish/en', 'POST', {});
  assert.equal(publish.status(), 200, await publish.text());
  const bundle = await context.request.get('/api/public/localization/bundle/en');
  assert.equal(bundle.status(), 200);
  const body = await bundle.json();
  assert.equal(body.messages[key], 'Browser runtime copy');
  assert.ok(body.releaseVersion >= 1);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log(`PASS: localization page, create/publish/public bundle and responsive layout (${key})`);
} finally {
  if (created) await write(`/platform/localization/entries/${created.id}?version=${created.version}`, 'DELETE', {});
  if (emptyCreated) await write(`/platform/localization/entries/${emptyCreated.id}?version=${emptyCreated.version}`, 'DELETE', {});
  if (created || emptyCreated) await write('/platform/localization/publish/en', 'POST', {});
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  await context.request.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } }).catch(() => {});
  await context.close();
  await browser.close();
}
