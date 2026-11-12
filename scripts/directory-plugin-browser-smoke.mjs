import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';

const { config, baseURL } = loadQaRunContext();
const fixture = JSON.parse(readFileSync('.runtime/architecture-fix/directory-fixture.json', 'utf8'));
assert.equal(fixture.runToken, config.LUMIRA_QA_RUN_TOKEN);
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
const page = await context.newPage();
const failures = [];
page.on('pageerror', error => failures.push(error.message));
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  const authenticated = await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(authenticated.status(), 200);
  for (const [section, text] of [
    ['users', fixture.username], ['roles', fixture.roleName],
    ['organizations', fixture.organizationName], ['print-templates', fixture.templateName],
  ]) {
    await page.goto('/dashboard/' + section);
    await expect(page.getByRole('row').filter({ hasText: text })).toBeVisible({ timeout: 30000 });
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: text })).toBeVisible({ timeout: 30000 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false, section);
  }
  await page.goto('/dashboard/online-users');
  await expect(page.getByRole('row').filter({ hasText: 'admin' }).first()).toBeVisible({ timeout: 30000 });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/dashboard/users');
  await expect(page.getByRole('row').filter({ hasText: fixture.username })).toBeVisible({ timeout: 30000 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(failures, []);
  console.log('PASS: dynamically loaded directory user/role/organization/template/session pages; retained rows after reload; desktop/mobile layout; no uncaught page errors');
} catch (error) {
  mkdirSync('.runtime/browser-failures', { recursive: true });
  await page.screenshot({ path: '.runtime/browser-failures/directory-plugin.png', fullPage: true });
  writeFileSync('.runtime/browser-failures/directory-plugin.txt', await page.locator('body').innerText());
  throw error;
} finally {
  await browser.close();
}
