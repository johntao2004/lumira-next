import { chromium, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({
  baseURL: 'http://127.0.0.1:8010',
  viewport: { width: 1440, height: 900 },
});

try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 200);

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/dashboard/home');

  const sider = page.locator('.ant-layout-sider');
  const menu = sider.locator('.ant-pro-sider-menu');
  const collapseButton = sider.locator('.ant-pro-sider-collapsed-button');
  await expect(sider).toBeVisible();
  await expect.poll(() => sider.evaluate(node => Math.round(node.getBoundingClientRect().width))).toBe(256);
  await expect(menu).not.toHaveClass(/ant-menu-inline-collapsed/);
  await expect(collapseButton).toBeVisible();

  await collapseButton.click();
  await expect.poll(() => sider.evaluate(node => Math.round(node.getBoundingClientRect().width))).toBe(64);
  await expect(menu).toHaveClass(/ant-menu-inline-collapsed/);
  await expect(menu.getByRole('link', { name: '工作台', exact: true })).toBeVisible();
  await expect(menu.getByRole('link', { name: '站点设置', exact: true })).not.toBeVisible();

  await collapseButton.click();
  await expect.poll(() => sider.evaluate(node => Math.round(node.getBoundingClientRect().width))).toBe(256);
  await expect(menu).not.toHaveClass(/ant-menu-inline-collapsed/);

  await menu.getByText('平台配置', { exact: true }).click();
  await menu.getByRole('link', { name: '站点设置', exact: true }).click();
  await expect.poll(() => sider.evaluate(node => Math.round(node.getBoundingClientRect().width))).toBe(256);
  await page.getByRole('tab', { name: '水印设置', exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard\/watermark$/);
  await expect(page.locator('.ant-tabs-tab-active')).toContainText('水印设置');

  for (const testId of [
    'dual-sider',
    'tree-sider',
    'navigation-mode-switch',
    'dual-label-toggle',
    'dual-pin-toggle',
  ]) {
    await expect(page.locator(`[data-testid="${testId}"]`)).toHaveCount(0);
  }

  await collapseButton.click();
  await expect.poll(() => sider.evaluate(node => Math.round(node.getBoundingClientRect().width))).toBe(64);
  await expect(menu).toHaveClass(/ant-menu-inline-collapsed/);

  await page.emulateMedia({ reducedMotion: 'reduce' });
  await collapseButton.click();
  await expect.poll(() => sider.evaluate(node => Math.round(node.getBoundingClientRect().width))).toBe(256);
  const transitionSeconds = await sider.evaluate(node => {
    const raw = getComputedStyle(node).transitionDuration.split(',')[0];
    return raw.endsWith('ms') ? Number.parseFloat(raw) / 1000 : Number.parseFloat(raw);
  });
  assert.ok(transitionSeconds <= 0.001, 'reduced-motion must suppress the standard sider transition');

  assert.deepEqual(errors, []);
  console.log('PASS: the standard ProLayout tree sidebar navigates, selects routes, collapses and contains no retired dual-sidebar controls');
} finally {
  try {
    const csrf = await (await context.request.get('/api/auth/csrf')).json();
    await context.request.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } });
  } finally {
    await browser.close();
  }
}
