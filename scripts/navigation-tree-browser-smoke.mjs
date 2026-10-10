import {chromium, expect} from '@playwright/test';
import {mkdirSync, readFileSync} from 'node:fs';
import assert from 'node:assert/strict';

mkdirSync('.runtime/navigation-tree-qa', {recursive: true});
const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({headless: true, ...(process.env.BROWSER_EXECUTABLE ? {executablePath: process.env.BROWSER_EXECUTABLE} : {})});
try {
  const context = await browser.newContext({baseURL: 'http://127.0.0.1:8010', viewport: {width: 1134, height: 869}});
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {form: {username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD}, headers: {[csrf.headerName]: csrf.token}})).status(), 200);
  await context.addCookies([{name: 'lumira-next.preferences', value: encodeURIComponent(JSON.stringify({dark: true, locale: 'zh-CN'})), url: 'http://127.0.0.1:8010'}]);
  const initial = await (await context.request.get('/api/platform/navigation/menus')).json();
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const table = page.locator('.resource-data .ant-table-tbody');
  const row = id => table.locator(`tr[data-row-key="${id}"]`);
  const expand = id => row(id).locator('button.ant-table-row-expand-icon');
  const active = initial.filter(item => !item.deleted);
  const roots = active.filter(item => item.parentId === null);
  const parent = roots.find(item => active.some(child => child.parentId === item.id));
  const children = active.filter(item => item.parentId === parent.id);
  await page.goto('/dashboard/navigation');
  await expect(table.locator('tr[data-row-key]')).toHaveCount(roots.length);
  await expand(parent.id).click();
  for (const child of children) await expect(row(child.id)).toBeVisible();
  await expect(expand(parent.id)).toHaveAttribute('aria-expanded', 'true');
  // The text and native icon share a line, rather than an overflowing full-width label.
  const iconBox = await expand(parent.id).boundingBox();
  const labelBox = await row(parent.id).locator('.resource-table-ellipsis').first().boundingBox();
  assert.ok(labelBox.x > iconBox.x && Math.abs(labelBox.y + labelBox.height / 2 - iconBox.y - iconBox.height / 2) < 8);
  await page.screenshot({path: '.runtime/navigation-tree-qa/desktop.png', fullPage: true});
  await expand(parent.id).click();
  for (const child of children) await expect(row(child.id)).not.toBeVisible();
  await page.reload();
  await expect(table.locator('tr[data-row-key]')).toHaveCount(roots.length);

  // A branch with more than a page of children must stay on its root's page.
  // Fixtures are served only to this browser; business navigation is not modified.
  const fixture = (id, parentId, sortOrder, labelDefault) => ({...parent, id, parentId, sortOrder, labelDefault, menuCode: `qa.${id}`, deleted: false});
  const data = Array.from({length: 11}, (_, i) => fixture(`root-${i}`, null, i, `测试分组 ${i}`));
  data.push(...Array.from({length: 12}, (_, i) => fixture(`child-${i}`, 'root-10', i, `测试子项 ${i}`)));
  data.push(fixture('grandchild', 'child-11', 0, '查找深层导航'));
  await page.route('**/api/platform/navigation/menus', route => route.fulfill({json: data}));
  await page.reload();
  await expect(table.locator('tr[data-row-key]')).toHaveCount(10);
  await page.locator('.ant-pagination-item-2').click();
  await expect(row('root-10')).toBeVisible();
  await expand('root-10').click();
  await expect(table.locator('tr[data-row-key]')).toHaveCount(13);
  await expand('child-11').click();
  await expect(row('grandchild')).toBeVisible();
  await expect(table.locator('tr[data-row-key]')).toHaveCount(14);
  await expect(page.locator('.ant-pagination-item-active')).toHaveText('2');
  // A query for a deep child retains and expands both ancestors.
  await page.locator('input#navigation-editor_labelDefault').fill('查找深层导航');
  await page.getByRole('button', {name: /查\s*询/, exact: true}).click();
  await expect(row('grandchild')).toBeVisible();
  await expect(table.locator('tr[data-row-key]')).toHaveCount(3);
  await expect(expand('root-10')).toHaveAttribute('aria-expanded', 'true');
  await expect(expand('child-11')).toHaveAttribute('aria-expanded', 'true');
  await page.getByRole('button', {name: /重\s*置/, exact: true}).click();
  await expect(table.locator('tr[data-row-key]')).toHaveCount(10);
  await page.unroute('**/api/platform/navigation/menus');
  await page.goto('/dashboard/navigation');
  await expect(row(parent.id)).toBeVisible();
  await expand(parent.id).click();
  await page.setViewportSize({width: 390, height: 844});
  for (const child of children) await expect(row(child.id)).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({path: '.runtime/navigation-tree-qa/mobile.png', fullPage: true});
  assert.deepEqual(await (await context.request.get('/api/platform/navigation/menus')).json(), initial);
  assert.deepEqual(errors, []);
  console.log('PASS: native tree expand/collapse, aligned labels, reload, root-only pagination with 12 children, nested search/ancestor expansion, reset, mobile no page overflow, unchanged business navigation and no page errors');
} finally {
  await browser.close();
}
