import {chromium, expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {observeResponse} from './browser-support.mjs';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const moduleId = `qa-browser-${Date.now().toString(36)}`;
const digest = 'a'.repeat(64);
const sql = statement => execFileSync(
  'python3',
  ['-c', 'import sys; from smoke_support import sql; sql(sys.stdin.read())'],
  {cwd: 'scripts', input: statement, stdio: ['pipe', 'pipe', 'pipe']},
);
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? {executablePath: process.env.BROWSER_EXECUTABLE} : {}),
});
const context = await browser.newContext({baseURL: 'http://127.0.0.1:8010', viewport: {width: 1213, height: 886}});
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal(
    (await context.request.post('/api/auth/login', {
      form: {username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD},
      headers: {[csrf.headerName]: csrf.token},
    })).status(),
    200,
  );

  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error));
  await page.goto('/dashboard/modules');
  await page.getByRole('button', {name: '模块包', exact: true}).click();
  const packages = page.getByRole('dialog', {name: '模块包', exact: true});
  await expect(packages).toBeVisible();
  await packages.getByRole('button', {name: '登记模块包', exact: true}).click();
  const stage = page.getByRole('dialog', {name: '登记模块包', exact: true});
  await expect(stage).toBeVisible();
  await stage.locator('#moduleId').fill(moduleId);
  await stage.locator('#name').fill('Browser QA package');
  await stage.locator('#version').fill('1.0.0');
  await stage.locator('#schemaVersion').fill('schema-1');
  await stage.locator('#apiVersion').fill('lumira.platform/v1');
  await stage.locator('#artifactDigest').fill(digest);
  await stage.locator('#artifactRef').fill(`oci://registry.example/lumira/${moduleId}@sha256:${digest}`);
  await stage.locator('#dependencies').fill('platform');
  await stage.locator('#permissions').fill('qa-browser:example:read');
  await stage.locator('#routes').fill('/dashboard/qa-browser');
  await stage.locator('#reason').fill('Browser module package lifecycle verification');
  const staged = observeResponse(page, response => response.url().endsWith('/api/platform/modules/packages') && response.request().method() === 'POST');
  await stage.locator('button[type="submit"]').click();
  const stagedResponse = await staged;
  assert.equal(stagedResponse.status(), 201);
  const stagedItem = await stagedResponse.json();
  assert.equal(stagedItem.status, 'STAGED');
  assert.equal(stagedItem.moduleId, moduleId);
  assert.equal(stagedItem.runtimeReady, false);
  await expect(stage).not.toBeVisible();
  const row = packages.getByRole('row').filter({hasText: moduleId});
  await expect(row).toBeVisible();
  await expect(row.getByText('等待代码部署', {exact: true})).toBeVisible();
  await expect(row.getByText('待激活', {exact: true})).toBeVisible();
  await row.getByRole('button', {name: '拒绝包', exact: true}).click();
  const reject = page.getByRole('dialog', {name: '拒绝包', exact: true});
  await expect(reject).toBeVisible();
  await reject.locator('#reason').fill('Browser lifecycle rejection');
  const rejected = observeResponse(page, response => response.url().endsWith(`/api/platform/modules/packages/${stagedItem.id}/reject`) && response.request().method() === 'POST');
  await reject.locator('button[type="submit"]').click();
  const rejectedResponse = await rejected;
  assert.equal(rejectedResponse.status(), 200);
  await expect(reject).not.toBeVisible();
  await expect(row.getByText('已拒绝', {exact: true})).toBeVisible();
  assert.equal(pageErrors.length, 0, pageErrors.map(error => error.message).join('\n'));
  console.log('PASS: module package UI registration, digest metadata, runtime readiness fence and rejection flow have no page errors');
} finally {
  sql(`DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_module_package WHERE artifact_digest='${digest}' AND module_id='${moduleId}'); DELETE FROM platform_module_package WHERE artifact_digest='${digest}' AND module_id='${moduleId}';`);
  try {
    const csrf = await (await context.request.get('/api/auth/csrf')).json();
    await context.request.post('/api/auth/logout', {headers: {[csrf.headerName]: csrf.token}});
  } finally {
    await browser.close();
  }
}
