import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const code = `qa.navigation.${Date.now()}`;
const dynamicCode = `qa.navigation.dynamic.${Date.now()}`;
const dynamicSection = `qa-dynamic-${Date.now()}`;
let context;
try {
  context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
  const runtime = await context.request.get('/api/platform/navigation');
  assert.equal(runtime.status(), 200);
  const runtimeNodes = await runtime.json();
  const flattened = [];
  const walk = (nodes) => nodes.forEach((node) => { flattened.push(node); walk(node.children ?? []); });
  walk(runtimeNodes);
  assert.ok(flattened.some((node) => node.sectionKey === 'navigation'));

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/dashboard/navigation');
  const main = page.getByRole('main');
  await expect(main.getByRole('button', { name: /新增导航/ })).toBeVisible();
  await expect(main.getByText('概览', { exact: true })).toBeVisible();
  await main.getByRole('button', { name: /新增导航/ }).click();
  const editor = page.locator('.platform-form-drawer');
  await editor.locator('#navigation-editor_menuCode').fill(code);
  await editor.locator('#navigation-editor_labelKey').fill('qaNavigation');
  await editor.locator('#navigation-editor_labelDefault').fill('QA 临时分组');
  await editor.locator('.ant-select').first().click();
  await page.keyboard.press('ArrowUp');
  await page.keyboard.press('Enter');
  const createResponse = page.waitForResponse((candidate) => candidate.url().endsWith('/api/platform/navigation/menus') && candidate.request().method() === 'POST');
  await editor.getByRole('button', { name: '保存', exact: true }).click();
  assert.equal((await createResponse).status(), 201);
  const row = main.locator('tbody tr[data-row-key]').filter({ hasText: code });
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: /停\s*用/ }).click();
  await expect(row.getByText('停用', { exact: true })).toBeVisible();
  const menuRows = await (await context.request.get('/api/platform/navigation/menus')).json();
  const administration = menuRows.find((item) => item.menuCode === 'administration');
  assert.ok(administration);
  const dynamicCsrf = await (await context.request.get('/api/auth/csrf')).json();
  const dynamicResponse = await context.request.post('/api/platform/navigation/menus', {
    data: {
      parentId: administration.id,
      menuCode: dynamicCode,
      labelKey: 'qaNavigationDynamic',
      labelDefault: 'QA 动态页面',
      menuType: 'PAGE',
      sectionKey: dynamicSection,
      path: `/dashboard/${dynamicSection}`,
      componentKey: 'platform:unavailable',
      enabled: true,
      sortOrder: 0,
    },
    headers: { [dynamicCsrf.headerName]: dynamicCsrf.token },
  });
  assert.equal(dynamicResponse.status(), 201, await dynamicResponse.text());
  await page.goto(`/dashboard/${dynamicSection}`);
  await expect(page.getByRole('heading', { name: 'QA 动态页面' }).first()).toBeVisible();
  await expect(page.getByRole('alert').filter({ hasText: '该页面组件未包含在当前部署中' })).toBeVisible();
  await expect(page.getByText(`/dashboard/${dynamicSection}`, { exact: true })).toBeVisible();
  const refreshed = await context.request.get('/api/platform/navigation/menus');
  const dynamic = (await refreshed.json()).find((item) => item.menuCode === dynamicCode && !item.deleted);
  assert.ok(dynamic);
  const deleteCsrf = await (await context.request.get('/api/auth/csrf')).json();
  const deleteDynamic = await context.request.delete(`/api/platform/navigation/menus/${dynamic.id}?version=${dynamic.version}`, { headers: { [deleteCsrf.headerName]: deleteCsrf.token } });
  assert.equal(deleteDynamic.status(), 204, await deleteDynamic.text());
  await page.goto('/dashboard/navigation');
  const refreshedRow = page.getByRole('main').locator('tbody tr[data-row-key]').filter({ hasText: code });
  await expect(refreshedRow).toHaveCount(1);
  await refreshedRow.getByRole('button', { name: '删除', exact: true }).click();
  const confirm = page.locator('.ant-popconfirm').filter({ hasText: '确定删除“QA 临时分组”？' });
  await expect(confirm).toBeVisible();
  await confirm.getByRole('button', { name: '删除', exact: true }).click();
  await expect(main.getByText('QA 临时分组', { exact: true })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(
    () => page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth),
  ).toBe(false);
  assert.deepEqual(errors, []);
  console.log('PASS: runtime navigation tree, admin create/status/delete, built-in protection, mobile no-overflow and no pageerror');
} finally {
  // If an assertion interrupted the UI flow, remove the fixture through the API
  // so repeated QA runs never accumulate custom menus.
  if (context) {
    const rows = await context.request.get('/api/platform/navigation/menus');
    if (rows.ok()) {
      const current = await rows.json();
      for (const targetCode of [dynamicCode, code]) {
        const found = current.find((item) => item.menuCode === targetCode && !item.deleted);
        if (!found) continue;
        const csrf = await (await context.request.get('/api/auth/csrf')).json();
        await context.request.delete(`/api/platform/navigation/menus/${found.id}?version=${found.version}`, { headers: { [csrf.headerName]: csrf.token } });
      }
    }
    await context.close();
  }
  await browser.close();
}
