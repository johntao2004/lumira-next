import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { loadLocaleMessages, translate } from '../apps/web/src/platform/i18n/messages.ts';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({
  baseURL: 'http://127.0.0.1:8010',
  viewport: { width: 877, height: 759 },
});

try {
  await loadLocaleMessages('zh-CN');
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 200);

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 759 });
  await page.goto('/dashboard/sms');
  const navigation = page.locator('.ant-layout-sider');
  await expect(navigation.getByText(translate('zh-CN', 'communicationCenter'), { exact: true })).toBeVisible();
  await expect(navigation.getByRole('link', { name: translate('zh-CN', 'messageChannels'), exact: true })).toBeVisible();
  await page.setViewportSize({ width: 877, height: 759 });
  await page.goto('/dashboard/sms');
  await expect(page.locator('.ant-page-header-heading-title')).toHaveText('消息渠道');
  await page.locator('#data_endpoint').waitFor();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollHeight > innerHeight)).toBe(true);

  const initial = await page.evaluate(() => {
    const navigation = document.querySelector('.ant-layout-sider');
    const header = document.querySelector('.workspace-utility-header');
    const content = document.querySelector('main.ant-pro-layout-content');
    const info = node => node && ({
      position: getComputedStyle(node).position,
      rect: node.getBoundingClientRect().toJSON(),
    });
    return {
      navigation: info(navigation),
      header: info(header),
      content: info(content),
      pageHeight: document.documentElement.scrollHeight,
      viewportHeight: innerHeight,
    };
  });
  assert.equal(initial.navigation.position, 'fixed');
  assert.equal(initial.header.position, 'sticky');
  assert.ok(initial.pageHeight > initial.viewportHeight);
  assert.ok(Math.abs(initial.navigation.rect.top) <= 1, 'standard sider must begin at the top of the viewport');
  assert.ok(Math.abs(initial.header.rect.top) <= 1, 'utility header must begin at the top of the workspace');

  await page.evaluate(() => window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' }));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  const scrolled = await page.evaluate(() => ({
    scrollY: window.scrollY,
    navigation: document.querySelector('.ant-layout-sider')?.getBoundingClientRect().toJSON(),
    header: document.querySelector('.workspace-utility-header')?.getBoundingClientRect().toJSON(),
    content: document.querySelector('main.ant-pro-layout-content')?.getBoundingClientRect().toJSON(),
  }));
  assert.ok(Math.abs(scrolled.navigation.top - initial.navigation.rect.top) <= 1, 'fixed sider moved with document scroll');
  assert.ok(Math.abs(scrolled.header.top - initial.header.rect.top) <= 1, 'header moved with document scroll');
  assert.ok(scrolled.content.top < initial.content.rect.top, 'settings content did not scroll');
  assert.ok(scrolled.navigation.bottom <= 760, 'sider must remain within the viewport');

  assert.deepEqual(errors, []);
  console.log('PASS: communication navigation and its message-channel anchor render; the ProLayout sidebar and utility header remain anchored while the SMS form scrolls');
} finally {
  try {
    const csrf = await (await context.request.get('/api/auth/csrf')).json();
    await context.request.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } });
  } finally {
    await browser.close();
  }
}
