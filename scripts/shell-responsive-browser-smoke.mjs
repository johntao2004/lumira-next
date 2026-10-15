import { loadQaRunContext } from './qa-run-guard.mjs';
import { chromium, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
import { languages, loadLocaleMessages, translate } from '../apps/web/src/platform/i18n/messages.ts';
import { PREFERENCES_COOKIE, serializePreferences, defaultPreferences } from '../apps/web/src/platform/preferences/model.ts';

const { config, baseURL } = loadQaRunContext();
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL: baseURL });

try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 200);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));

  async function load(locale, width, dark = false) {
    await loadLocaleMessages(locale);
    await context.addCookies([{
      name: PREFERENCES_COOKIE,
      value: serializePreferences({ ...defaultPreferences, locale, dark }),
      url: baseURL,
    }]);
    await page.setViewportSize({ width, height: 844 });
    await page.goto('/dashboard/home');
    await expect(page.locator('.ant-page-header-heading-title')).toHaveText(translate(locale, 'home'));
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), dark ? 'dark' : 'light');
  }

  for (const dark of [false, true]) {
    for (const { value: locale } of languages) {
      await load(locale, 320, dark);
      const account = page.locator('.pro-account-trigger');
      await expect(account).toBeVisible();
      await account.click();
      await expect(page.getByRole('menuitem', { name: translate(locale, 'profile'), exact: true })).toBeVisible();
      await page.keyboard.press('Escape');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    }
  }

  for (const width of [390, 768, 1024, 1440]) {
    for (const dark of [false, true]) {
      await load('zh-CN', width, dark);
      const header = page.locator('[data-testid="pro-layout-global-header"]');
      const headerBox = await header.boundingBox();
      const accountBox = await header.locator('.pro-account-trigger').boundingBox();
      assert.ok(headerBox && accountBox, `ProLayout global header and account must render at ${width}px`);
      assert.ok(accountBox.x >= headerBox.x && accountBox.x + accountBox.width <= headerBox.x + headerBox.width + 1, `header action escaped at ${width}px`);
    }
  }

  await load('zh-CN', 390);
  const mobileTrigger = page.locator('.ant-pro-global-header-collapsed-button');
  await expect(mobileTrigger).toBeVisible();
  await mobileTrigger.click();
  const navigationDrawer = page.getByRole('dialog');
  await expect(navigationDrawer).toBeVisible();
  const drawerBox = await navigationDrawer.boundingBox();
  assert.ok(drawerBox && Math.abs(drawerBox.width - 256) <= 1, `mobile navigation drawer must match the standard 256px ProLayout sider, got ${drawerBox?.width}`);
  await expect(navigationDrawer.getByRole('link', { name: '工作台', exact: true })).toBeVisible();
  await expect(navigationDrawer.getByRole('link', { name: translate('zh-CN', 'siteSettings'), exact: true })).not.toBeVisible();
  await navigationDrawer.getByText(translate('zh-CN', 'administration'), { exact: true }).click();
  await expect(navigationDrawer.getByRole('link', { name: translate('zh-CN', 'siteSettings'), exact: true })).toBeVisible();
  await navigationDrawer.getByRole('link', { name: translate('zh-CN', 'siteSettings'), exact: true }).click();
  await page.waitForURL('**/dashboard/branding');
  await expect(navigationDrawer).not.toBeVisible();

  await page.locator('.pro-account-trigger').click();
  const accountMenuItems = page.locator('.account-menu-popup [role="menuitem"]');
  await expect(accountMenuItems).toHaveCount(2);
  await expect(accountMenuItems).toHaveText([translate('zh-CN', 'profile'), translate('zh-CN', 'accountLogout')]);
  await page.getByRole('menuitem', { name: translate('zh-CN', 'profile'), exact: true }).click();
  await page.waitForURL('**/dashboard/account-security');
  for (const [key, path] of [['profile', '/dashboard/account-security'], ['accountSecurity', '/dashboard/account-authentication-security'], ['mySessions', '/dashboard/account-sessions'], ['changePassword', '/dashboard/account-password']]) {
    if (key !== 'profile') {
      await page.locator('.account-settings-menu').getByRole('menuitem', { name: translate('zh-CN', key), exact: true }).click();
      await page.waitForURL(`**${path}`);
    }
    const panel = page.locator('.account-settings-panel');
    await expect(panel).toBeVisible();
    await expect(panel.locator('.account-settings-title')).toHaveText(translate('zh-CN', key));
    await expect(panel.locator('.account-settings-title')).toBeFocused();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }

  mkdirSync('.runtime/browser-checks', { recursive: true });
  await page.screenshot({ path: '.runtime/browser-checks/pro-layout-mobile.png', fullPage: true });
  await load('zh-CN', 1440);
  const desktopSider = page.locator('.ant-layout-sider');
  await expect(desktopSider).toBeVisible();
  await expect.poll(() => desktopSider.evaluate(node => Math.round(node.getBoundingClientRect().width))).toBe(256);
  await desktopSider.locator('.ant-pro-sider-collapsed-button').click();
  await expect.poll(() => desktopSider.evaluate(node => Math.round(node.getBoundingClientRect().width))).toBe(64);
  await desktopSider.locator('.ant-pro-sider-collapsed-button').click();
  await expect.poll(() => desktopSider.evaluate(node => Math.round(node.getBoundingClientRect().width))).toBe(256);
  await expect(desktopSider.getByRole('link', { name: '工作台', exact: true })).toBeVisible();
  await desktopSider.getByText(translate('zh-CN', 'administration'), { exact: true }).click();
  await expect(desktopSider.getByRole('link', { name: translate('zh-CN', 'siteSettings'), exact: true })).toBeVisible();
  await expect(page.locator('[data-testid="pro-layout-global-header"] .pro-account-trigger')).toBeVisible();
  await page.screenshot({ path: '.runtime/browser-checks/pro-layout-desktop.png', fullPage: true });

  assert.deepEqual(errors, []);
  console.log('PASS: standard ProLayout desktop sidebar and mobile drawer render in light/dark, eight locales and without overflow/pageerrors');
} finally {
  try {
    const csrf = await (await context.request.get('/api/auth/csrf')).json();
    await context.request.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } });
  } finally {
    await browser.close();
  }
}
