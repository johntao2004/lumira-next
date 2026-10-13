import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010' });
const destinations = [
  ['个人资料', '/dashboard/account-profile'],
  ['修改密码', '/dashboard/account-password'],
  ['我的会话', '/dashboard/account-sessions'],
];
async function focusMenuItem(page, item, key, itemCount) {
  for (let index = 0; index < itemCount; index += 1) {
    if (await item.evaluate(node => node === document.activeElement)) return;
    await page.keyboard.press(key);
  }
  await expect(item).toBeFocused();
}
async function expectSelectedMenuItem(item, label) {
  await expect(item).toHaveClass(/ant-menu-item-selected/);
  await expect(item.getByRole('link', { name: label, exact: true })).toHaveAttribute('aria-current', 'page');
}

try {
  const token = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [token.headerName]: token.token },
  })).status(), 200);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));

  for (const width of [1213, 390]) {
    await page.setViewportSize({ width, height: 886 });

    // The account popup remains a keyboard entry point, and activation now lands on a page.
    for (const [label, path] of destinations) {
      await page.goto('/dashboard/home');
      await expect(page.locator('.ant-page-header-heading-title')).toHaveText('工作台');
      const trigger = page.locator('.pro-account-trigger');
      await trigger.focus();
      await page.keyboard.press('Enter');
      const entry = page.getByRole('menuitem', { name: label, exact: true });
      await expect(entry).toBeVisible();
      await expect.poll(() => page.locator('.account-menu-popup').evaluateAll(nodes => nodes.some(node => node.contains(document.activeElement)))).toBe(true);
      for (let index = 0; index < 8; index += 1) {
        if (await entry.evaluate(node => node === document.activeElement)) break;
        await page.keyboard.press('ArrowDown');
      }
      await expect(entry).toBeFocused();
      await page.keyboard.press('Enter');
      await page.waitForURL(`**${path}`);

      const panel = page.locator('.account-settings-panel');
      const title = panel.locator('.account-settings-title');
      await expect(panel).toBeVisible();
      await expect(title).toHaveText(label);
      await expect(title).toBeFocused();
      await expectSelectedMenuItem(panel.locator('.account-settings-menu').getByRole('menuitem', { name: label, exact: true }), label);
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

      if (label === '个人资料') {
        const emailButton = panel.getByRole('button', { name: '账号邮箱', exact: true });
        await emailButton.focus();
        await page.keyboard.press('Enter');
        const email = page.getByRole('dialog', { name: '账号邮箱', exact: true });
        await expect(email).toBeVisible();
        await expect.poll(() => email.evaluate(node => node.closest('.ant-drawer').contains(document.activeElement))).toBe(true);
        await page.keyboard.press('Escape');
        await expect(email).not.toBeVisible();
        await expect(panel).toBeVisible();
        await expect(page).toHaveURL(/\/dashboard\/account-profile$/);
        await expect(emailButton).toBeFocused();
      }
    }

    // The persistent left menu uses vertical arrows on desktop and horizontal arrows on mobile.
    await page.goto('/dashboard/account-profile');
    const panel = page.locator('.account-settings-panel');
    const nav = panel.locator('.account-settings-nav');
    const menu = panel.locator('.account-settings-menu');
    await expect(nav).toBeVisible();
    const profileItem = menu.getByRole('menuitem', { name: '个人资料', exact: true });
    const passwordItem = menu.getByRole('menuitem', { name: '修改密码', exact: true });
    const sessionsItem = menu.getByRole('menuitem', { name: '我的会话', exact: true });
    const nextKey = width <= 600 ? 'ArrowRight' : 'ArrowDown';
    const itemCount = await menu.getByRole('menuitem').count();

    await profileItem.focus();
    await focusMenuItem(page, passwordItem, nextKey, itemCount);
    await page.keyboard.press('Enter');
    await page.waitForURL('**/dashboard/account-password');
    await expect(panel.locator('.account-settings-title')).toBeFocused();
    await expectSelectedMenuItem(passwordItem, '修改密码');

    await passwordItem.focus();
    await focusMenuItem(page, sessionsItem, nextKey, itemCount);
    await page.keyboard.press('Enter');
    await page.waitForURL('**/dashboard/account-sessions');
    await expect(panel.locator('.account-settings-title')).toBeFocused();
    await expectSelectedMenuItem(sessionsItem, '我的会话');

    const navBox = await nav.boundingBox();
    assert.ok(navBox && navBox.x >= 0 && navBox.x + navBox.width <= width, `Account navigation exceeds ${width}px viewport`);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    console.log('PASS keyboard account routes, selected menu focus, nested drawer restoration and responsive navigation', width);
  }

  assert.deepEqual(errors, []);
} finally {
  try {
    const token = await (await context.request.get('/api/auth/csrf')).json();
    await context.request.post('/api/auth/logout', { headers: { [token.headerName]: token.token } });
  } finally {
    await browser.close();
  }
}
