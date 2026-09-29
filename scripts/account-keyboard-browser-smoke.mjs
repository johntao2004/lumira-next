import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010' });

try {
  const token = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [token.headerName]: token.token },
  })).status(), 200);
  const page = await context.newPage();
  const widths = process.env.KEYBOARD_STRESS ? Array.from({ length: 8 }, () => 390) : [1213, 390];

  for (const width of widths) {
    await page.setViewportSize({ width, height: 886 });
    await page.goto('/dashboard/home');
    await expect(page.locator('.ant-page-header-heading-title')).toHaveText('工作台');
    for (const label of ['个人资料', '修改密码', '我的会话']) {
      const trigger = page.locator('.pro-account-trigger');
      await trigger.focus();
      await page.keyboard.press('Enter');
      await expect(page.getByRole('menuitem', { name: label, exact: true })).toBeVisible();
      await expect.poll(() => page.locator('.account-menu-popup').evaluateAll(nodes => nodes.some(node => node.contains(document.activeElement)))).toBe(true);
      for (let index = 0; index < 6; index += 1) {
        if (await page.getByRole('menuitem', { name: label, exact: true }).evaluate(node => node === document.activeElement)) break;
        await page.keyboard.press('ArrowDown');
      }
      await expect(page.getByRole('menuitem', { name: label, exact: true })).toBeFocused();
      await page.keyboard.press('Enter');
      const dialog = page.getByRole('dialog', { name: label, exact: true });
      await expect(dialog).toBeVisible();
      await expect.poll(() => dialog.evaluate(node => node.closest('.ant-drawer').contains(document.activeElement))).toBe(true);
      await page.keyboard.press('Tab');
      await expect.poll(() => dialog.evaluate(node => node.closest('.ant-drawer').contains(document.activeElement))).toBe(true);

      if (label === '修改密码') {
        const close = dialog.getByRole('button', { name: '关闭', exact: true });
        await close.dispatchEvent('keydown', { key: 'Escape', isComposing: true });
        await expect(dialog).toBeVisible();
        await close.dispatchEvent('compositionend');
        await page.keyboard.press('Escape');
        await expect(dialog).toBeVisible();
        await page.waitForTimeout(220);
      }
      if (label === '个人资料') {
        const emailButton = dialog.getByRole('button', { name: '账号邮箱', exact: true });
        for (let index = 0; index < 20; index += 1) {
          if (await emailButton.evaluate(node => node === document.activeElement)) break;
          await page.keyboard.press('Tab');
        }
        await expect(emailButton).toBeFocused();
        await page.keyboard.press('Enter');
        const email = page.getByRole('dialog', { name: '账号邮箱', exact: true });
        await expect(email).toBeVisible();
        await page.keyboard.press('Escape');
        await expect(email).not.toBeVisible();
        await expect(dialog).toBeVisible();
        await expect(emailButton).toBeFocused();
      }
      await page.keyboard.press('Escape');
      await expect(dialog).not.toBeVisible();
      await expect(trigger).toBeFocused();
    }
    console.log('PASS keyboard ProLayout', width);
  }
} finally {
  try {
    const token = await (await context.request.get('/api/auth/csrf')).json();
    await context.request.post('/api/auth/logout', { headers: { [token.headerName]: token.token } });
  } finally {
    await browser.close();
  }
}
