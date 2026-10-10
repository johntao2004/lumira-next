import { chromium } from '@playwright/test';
import { mkdirSync, readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const artifacts = '.runtime/profile-fields-layout-qa';
mkdirSync(artifacts, { recursive: true });
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: process.env.LUMIRA_WEB_ORIGIN ?? 'http://127.0.0.1:8010', viewport: { width: 1440, height: 1000 } });
const path = '/api/platform/settings/profile-fields';
let original;
let lastWrittenVersion;
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 200);
  original = await (await context.request.get(path)).json();
  assert.ok(original.data.fields.some(field => field.key === 'avatarUrl' && field.type === 'IMAGE'));
  assert.ok(original.data.fields.length < 100, 'Field capacity reached; no test fixture added');
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/dashboard/profile-fields');
  const add = page.getByRole('button', { name: '新增资料字段', exact: true });
  await add.waitFor();
  assert.equal(await page.getByRole('main').getByRole('button', { name: '配置历史', exact: true }).count(), 0);
  assert.equal(await page.getByRole('main').getByRole('button', { name: '重新载入配置', exact: true }).count(), 0);
  assert.equal(await page.locator('button[type="submit"]').count(), 1, 'Profile field Save action must remain visible');
  assert.equal(await page.locator('.profile-fields-list').count(), 1, 'Profile field list and sorting region must remain available');
  assert.equal(await page.locator('.profile-fields-detail').count(), 1, 'Profile field editor region must remain available');
  const rows = page.locator('.profile-field-row');
  const order = () => rows.evaluateAll(items => items.map(item => item.getAttribute('data-field-key')));
  assert.equal(await page.getByRole('button', { name: '新增资料字段 · 头像', exact: true }).count(), 0);
  assert.equal(await rows.count(), original.data.fields.length);
  await page.locator('[data-field-key="avatarUrl"] .profile-field-select').click();
  const avatarIndex = original.data.fields.findIndex(field => field.key === 'avatarUrl');
  assert.equal(await page.locator(`#data_fields_${avatarIndex}_key`).isDisabled(), true);
  assert.equal(await page.locator(`#data_fields_${avatarIndex}_type`).isDisabled(), true);
  await page.screenshot({ path: `${artifacts}/desktop.png`, fullPage: true });

  // Exercise real pointer sorting, including cancellation without losing values.
  const initialOrder = await order();
  const from = await rows.last().locator('.profile-field-drag').boundingBox();
  const to = await rows.first().boundingBox();
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x + 30, to.y + to.height / 2, { steps: 20 });
  await page.mouse.up();
  const draggedOrder = [initialOrder.at(-1), ...initialOrder.slice(0, -1)];
  await assertEventually(async () => assert.deepEqual(await order(), draggedOrder));
  const handle = rows.first().locator('.profile-field-drag');
  await handle.focus();
  await page.keyboard.press('Space');
  await page.locator('.profile-field-row.is-dragging').waitFor();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.keyboard.press('ArrowDown');
  await assertEventually(async () => assert.ok(await page.locator('.profile-field-row.is-dragging').evaluate(row => new DOMMatrix(getComputedStyle(row).transform).m42 > 10)));
  await page.getByText('已移至第 2 位', { exact: true }).waitFor({ state: 'attached' });
  await page.keyboard.press('Escape');
  await page.locator('.profile-field-row.is-dragging').waitFor({ state: 'hidden' });
  assert.deepEqual(await order(), draggedOrder);
  await handle.focus();
  await page.keyboard.press('Space');
  await page.locator('.profile-field-row.is-dragging').waitFor();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.keyboard.press('ArrowDown');
  await assertEventually(async () => assert.ok(await page.locator('.profile-field-row.is-dragging').evaluate(row => new DOMMatrix(getComputedStyle(row).transform).m42 > 10)));
  await page.getByText('已移至第 2 位', { exact: true }).waitFor({ state: 'attached' });
  await page.keyboard.press('Space');
  const keyboardOrder = [...draggedOrder];
  [keyboardOrder[0], keyboardOrder[1]] = [keyboardOrder[1], keyboardOrder[0]];
  await assertEventually(async () => assert.deepEqual(await order(), keyboardOrder));

  await add.click();
  const customIndex = original.data.fields.length;
  const keyInput = page.locator(`#data_fields_${customIndex}_key`);
  assert.equal(await keyInput.isEnabled(), true);
  assert.match(await keyInput.inputValue(), /^profileField_[0-9a-f]{32}$/);
  // A validation error in another field must bring its settings into view.
  await rows.first().locator('.profile-field-select').click();
  await page.locator('button[type="submit"]').click();
  await keyInput.waitFor({ state: 'visible' });
  assert.ok(await page.locator('.profile-fields-detail .ant-form-item-explain-error').count() > 0);
  const key = `qaField_${Date.now()}`;
  await keyInput.fill(key);
  await page.locator(`#data_fields_${customIndex}_label`).fill('排序验证字段');
  await page.locator(`#data_fields_${customIndex}_type`).click();
  await page.locator('.ant-select-dropdown').getByText('单选', { exact: true }).click();
  await page.locator(`#data_fields_${customIndex}_options`).fill('One\nTwo');
  await page.getByRole('button', { name: '上移 · 排序验证字段', exact: true }).click();
  const expectedOrder = [...keyboardOrder];
  expectedOrder.splice(expectedOrder.length - 1, 0, key);
  const responsePromise = page.waitForResponse(response => response.url().endsWith(path) && response.request().method() === 'PUT');
  await page.locator('button[type="submit"]').click();
  const response = await responsePromise;
  assert.equal(response.status(), 200, await response.text());
  const saved = await response.json();
  lastWrittenVersion = saved.version;
  assert.deepEqual(saved.data.fields.map(field => field.key), expectedOrder);
  assert.deepEqual(saved.data.fields.find(field => field.key === key).options, ['One', 'Two']);
  assert.deepEqual(saved.data.fields.map(field => field.sortNo), expectedOrder.map((_key, index) => (index + 1) * 10));
  await page.reload();
  await add.waitFor();
  assert.deepEqual(await order(), expectedOrder);
  await page.locator(`[data-field-key="${key}"] .profile-field-select`).click();
  assert.equal(await page.locator(`#data_fields_${expectedOrder.indexOf(key)}_key`).inputValue(), key);
  assert.equal(await page.locator(`#data_fields_${expectedOrder.indexOf(key)}_options`).inputValue(), 'One\nTwo');
  await page.screenshot({ path: `${artifacts}/saved.png`, fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await assertEventually(async () => assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Mobile horizontal overflow'));
  const listBox = await page.locator('.profile-fields-list').boundingBox();
  const editorBox = await page.locator('.profile-fields-detail').boundingBox();
  assert.ok(editorBox.y > listBox.y + listBox.height, 'Mobile panels must stack');
  await page.screenshot({ path: `${artifacts}/mobile.png`, fullPage: true });
  assert.deepEqual(errors, []);
  console.log('PASS: default image, editable generated key, pointer/keyboard/button sorting, drag cancellation, options and order save/reload, mobile layout, no page errors');

  // Removing a custom field must preserve all other definitions and their order.
  await page.locator('.profile-fields-detail').getByRole('button', { name: /删\s*除/ }).click();
  const removedPromise = page.waitForResponse(response => response.url().endsWith(path) && response.request().method() === 'PUT');
  await page.locator('button[type="submit"]').click();
  const removed = await removedPromise;
  assert.equal(removed.status(), 200, await removed.text());
  const removal = await removed.json();
  lastWrittenVersion = removal.version;
  assert.deepEqual(removal.data.fields.map(field => field.key), keyboardOrder);
  console.log('PASS: custom field removal preserves other fields');
  await page.route('**/api/auth/me', async route => {
    const response = await route.fetch();
    const identity = await response.json();
    await route.fulfill({ response, json: { ...identity, globalAuthorities: identity.globalAuthorities.filter(authority => authority !== 'platform:settings:write') } });
  });
  await page.reload();
  await add.waitFor();
  assert.equal(await add.isDisabled(), true);
  assert.equal(await rows.first().locator('.profile-field-drag').isDisabled(), true);
  await rows.first().locator('.profile-field-select').click();
  assert.equal(await page.locator('.profile-fields-detail input[id$="_label"]:visible').isDisabled(), true);
  for (const button of await page.locator('.profile-field-move button').all()) assert.equal(await button.isDisabled(), true);
  console.log('PASS: read-only UI disables editing and every sorting control; hidden-field errors select the invalid field');
} finally {
  // Use versioned writes; never delete existing settings, audit history or business data.
  if (lastWrittenVersion !== undefined) {
    const current = await (await context.request.get(path)).json();
    assert.equal(current.version, lastWrittenVersion, 'Concurrent change detected; refusing to overwrite it');
    const csrf = await (await context.request.get('/api/auth/csrf')).json();
    const restored = await context.request.put(path, {
      data: { version: current.version, data: original.data },
      headers: { [csrf.headerName]: csrf.token },
    });
    assert.equal(restored.status(), 200, await restored.text());
    assert.deepEqual((await restored.json()).data, original.data);
    console.log('PASS: original field settings restored; temporary field removed');
  }
  await browser.close();
}

async function assertEventually(check) {
  const deadline = Date.now() + 5000;
  while (true) {
    try { await check(); return; } catch (error) {
      if (Date.now() >= deadline) throw error;
      await new Promise(resolve => setTimeout(resolve, 50));
    }
  }
}
