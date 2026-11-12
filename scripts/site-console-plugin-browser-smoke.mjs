import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { loadQaRunContext } from './qa-run-guard.mjs';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
const { config, baseURL } = loadQaRunContext();
const fixture = JSON.parse(readFileSync('.runtime/architecture-fix/site-console-fixture.json', 'utf8'));
assert.equal(fixture.runToken, config.LUMIRA_QA_RUN_TOKEN);
const t = await loadLocaleMessages('zh-CN');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
async function operate(action) {
  const result = await page.evaluate(async action => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const base = '/platform/modules/plugins/site-console/';
    const plan = await client.mutate(base + action + '-plan', 'POST', {});
    if (plan.blockers.length) throw new Error(JSON.stringify(plan.blockers));
    return client.mutate(base + action, 'POST', { revision: plan.revision, dataMode: 'PRESERVE' });
  }, action);
  let operation = result;
  for (let attempt = 0; attempt < 240 && !['SUCCEEDED', 'FAILED'].includes(operation.status); attempt++) {
    await page.waitForTimeout(250);
    operation = await page.evaluate(async id => window.__lumiraPluginSdk['@/platform/api/client'].api('/platform/modules/plugins/operations/' + id), result.id);
  }
  assert.equal(operation.status, 'SUCCEEDED', JSON.stringify(operation));
  await page.evaluate(() => window.dispatchEvent(new Event('lumira:modules-changed')));
}
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } })).status(), 200);
  await page.goto('/dashboard/branding');
  const name = page.getByRole('textbox', { name: t.websiteName });
  await expect(name).toHaveValue(fixture.data.branding.websiteName, { timeout: 30000 });
  const next = fixture.data.branding.websiteName + ' UI';
  await name.fill(next);
  const saving = page.waitForResponse(response => response.url().endsWith('/api/platform/settings/branding') && response.request().method() === 'PUT');
  await page.getByRole('button', { name: t.save, exact: true }).click();
  assert.equal((await saving).status(), 200);
  await page.reload();
  await expect(name).toHaveValue(next, { timeout: 30000 });
  await page.evaluate(async original => {
    const client = window.__lumiraPluginSdk['@/platform/api/client'];
    const current = await client.api('/platform/settings/branding');
    if (current.data.websiteName !== original + ' UI') throw new Error('Competing branding edit');
    return client.mutate('/platform/settings/branding', 'PUT', { version: current.version, data: { ...current.data, websiteName: original } });
  }, fixture.data.branding.websiteName);
  const terms = page.locator('textarea.markdown-editor-source:visible').first();
  await page.goto('/dashboard/agreements');
  await expect(terms).toHaveValue(fixture.data.agreements.userAgreementMarkdown, { timeout: 30000 });
  await operate('uninstall');
  await expect(terms).toHaveCount(0, { timeout: 20000 });
  assert.equal(new URL(page.url()).pathname, '/dashboard/agreements');
  await operate('install');
  await expect(terms).toHaveValue(fixture.data.agreements.userAgreementMarkdown, { timeout: 30000 });
  for (const width of [1213, 390]) {
    await page.setViewportSize({ width, height: 886 });
    for (const section of ['branding', 'watermark', 'agreements', 'maintenance']) {
      console.log(JSON.stringify({ stage: 'site-page-reload', section, width }));
      await page.goto('/dashboard/' + section);
      await expect(page.locator('.settings-form:visible')).toBeVisible({ timeout: 30000 });
      await page.reload();
      if (section === 'branding') await expect(name).toHaveValue(fixture.data.branding.websiteName, { timeout: 30000 });
      if (section === 'watermark') await expect(page.getByRole('textbox', { name: t.watermarkText })).toHaveValue(fixture.data.watermark.textLines.join('\n'), { timeout: 30000 });
      if (section === 'agreements') await expect(terms).toHaveValue(fixture.data.agreements.userAgreementMarkdown, { timeout: 30000 });
      if (section === 'maintenance') await expect(page.getByRole('switch', { name: t.enabled, exact: true })).not.toBeChecked({ timeout: 30000 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false, section + ' ' + width);
    }
  }
  assert.deepEqual(errors, []);
  console.log('PASS: independent site branding/watermark/agreement/maintenance pages; UI branding save/reload and guarded restore; live mounted editor uninstall/reinstall restores deep route and terms; retained values on desktop/mobile and no uncaught page errors');
} catch (error) {
  mkdirSync('.runtime/browser-failures', { recursive: true });
  await page.screenshot({ path: '.runtime/browser-failures/site-console-plugin.png', fullPage: true });
  writeFileSync('.runtime/browser-failures/site-console-plugin.txt', await page.locator('body').innerText());
  throw error;
} finally {
  await browser.close();
}
