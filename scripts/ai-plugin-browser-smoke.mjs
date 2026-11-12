import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';

const { config, baseURL } = loadQaRunContext();
const fixture = JSON.parse(readFileSync('.runtime/architecture-fix/ai-fixture.json', 'utf8'));
assert.equal(fixture.runToken, config.LUMIRA_QA_RUN_TOKEN);
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
const publicContext = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
const page = await context.newPage();
const publicPage = await publicContext.newPage();
const errors = [];
for (const current of [page, publicPage]) current.on('pageerror', error => errors.push(error.message));
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  })).status(), 200);
  for (let repeat = 0; repeat < 2; repeat++) {
    await page.goto('/dashboard/ai-models');
    await expect(page.getByRole('row').filter({ hasText: fixture.providerName })).toBeVisible({ timeout: 30000 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  }
  await page.goto('/dashboard/ai-console');
  await page.getByRole('combobox', { name: '选择模型服务' }).click();
  await page.getByText(fixture.providerName, { exact: true }).last().click();
  await expect(page.getByText(fixture.providerName, { exact: true }).filter({ visible: true }).first()).toBeVisible({ timeout: 30000 });
  for (const width of [1213, 390]) {
    await publicPage.setViewportSize({ width, height: 886 });
    await publicPage.goto(fixture.sharePath);
    await expect(publicPage.getByRole('heading', { name: fixture.shareTitle })).toBeVisible({ timeout: 30000 });
    await expect(publicPage.getByText('QA synthetic question', { exact: true })).toBeVisible();
    await expect(publicPage.locator('strong').filter({ hasText: 'answer' })).toBeVisible();
    await publicPage.reload();
    await expect(publicPage.getByRole('heading', { name: fixture.shareTitle })).toBeVisible();
    assert.equal(await publicPage.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  }
  assert.deepEqual(errors, []);
  console.log('PASS: dynamically loaded AI model/console pages; retained model after reload; anonymous retained share with Markdown on desktop/mobile; no uncaught page errors');
} catch (error) {
  mkdirSync('.runtime/browser-failures', { recursive: true });
  await page.screenshot({ path: '.runtime/browser-failures/ai-plugin.png', fullPage: true });
  writeFileSync('.runtime/browser-failures/ai-plugin.txt', await page.locator('body').innerText());
  throw error;
} finally {
  await browser.close();
}
