import { chromium } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
import { assertQaRunGuard } from './qa-run-guard.mjs';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const configuredPath = process.env.LUMIRA_SMOKE_CONFIG;
const baseURL = process.env.BASE_URL;
if (!configuredPath || !baseURL) {
  throw new Error('Set LUMIRA_SMOKE_CONFIG to a private isolated QA config and BASE_URL to its loopback origin.');
}
const configPath = path.isAbsolute(configuredPath) ? configuredPath : path.resolve(projectRoot, configuredPath);
assert.ok(existsSync(configPath), 'The explicitly selected smoke config must exist');
const parsedBase = new URL(baseURL ?? 'http://127.0.0.1:8010');
assert.ok(['127.0.0.1', 'localhost', '::1'].includes(parsedBase.hostname), 'Browser QA must target a loopback app');
assert.ok(['http:', 'https:'].includes(parsedBase.protocol));
assert.equal(parsedBase.username, '', 'Browser QA URL must not embed credentials');
assert.equal(parsedBase.password, '', 'Browser QA URL must not embed credentials');
const config = JSON.parse(readFileSync(configPath, 'utf8'));
assertQaRunGuard({ projectRoot, configPath, config, baseURL });
const databaseUrl = new URL(String(config.LUMIRA_DB_URL ?? '').replace(/^jdbc:/, ''));
const databaseName = decodeURIComponent(databaseUrl.pathname.replace(/^\//, ''));
assert.equal(databaseUrl.protocol, 'postgresql:', 'Fixture SQL requires a PostgreSQL QA database');
assert.ok(['127.0.0.1', 'localhost', '::1'].includes(databaseUrl.hostname), 'Fixture SQL must target a loopback PostgreSQL host');
assert.match(databaseName, /^lumira_(?:security|qa|test|smoke)_[a-z0-9_]+$/, 'Fixture SQL requires the dedicated Lumira QA database namespace');
const password = config.LUMIRA_BOOTSTRAP_PASSWORD ?? config.bootstrapPassword ?? config.password;
const bootstrapUsername = process.env.LUMIRA_SMOKE_USERNAME ?? config.LUMIRA_BOOTSTRAP_USERNAME ?? config.username ?? 'admin';
assert.equal(typeof password, 'string');
assert.ok(password.length > 0, 'The isolated QA config must provide a bootstrap password');
const t = await loadLocaleMessages('zh-CN');
const owner = randomUUID();
const username = `qa_files_${owner.replaceAll('-', '')}`;
let fileId;
const sql = statement => execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; sql(sys.stdin.read())'], {
  cwd: path.join(projectRoot, 'scripts'),
  env: { ...process.env, LUMIRA_SMOKE_CONFIG: configPath, BASE_URL: parsedBase.origin },
  input: statement,
  stdio: ['pipe', 'pipe', 'pipe'],
});
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
let context;
let authorizationHeaders = {};

async function authenticatedGet(url) {
  return context.request.get(url, { headers: authorizationHeaders });
}

try {
  sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${owner}','${username}','Files QA',password_hash FROM platform_user WHERE username='${String(bootstrapUsername).replaceAll("'", "''")}'`);
  context = await browser.newContext({ baseURL: parsedBase.origin, viewport: { width: 1213, height: 886 } });
  const token = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username, password }, headers: { [token.headerName]: token.token } })).status(), 200);
  const identityResponse = await context.request.get('/api/auth/me');
  assert.equal(identityResponse.status(), 200);
  const identity = await identityResponse.json();
  const revision = identity.authorizationContext?.rolePreviewSupported === true ? identity.authorizationContext.revision : null;
  if (typeof revision === 'string' && revision) authorizationHeaders = { 'X-Lumira-Authorization-Context-Revision': revision };
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/dashboard/files');
  await page.getByRole('heading', { name: t.myFiles, exact: true }).waitFor();
  const usage = page.getByRole('region', { name: t.fileUsage, exact: true });
  await usage.getByText('0 B / 1 GiB', { exact: true }).waitFor();

  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: t.uploadFile, exact: true }).click();
  const uploadedResponse = page.waitForResponse(response => response.url().includes('/api/account/files?') && response.request().method() === 'POST');
  await (await chooser).setFiles({ name: 'browser-file.txt', mimeType: 'text/plain', buffer: Buffer.from('browser file contents') });
  const response = await uploadedResponse;
  assert.equal(response.status(), 201);
  fileId = (await response.json()).id;
  const fileRow = page.getByRole('row').filter({ hasText: 'browser-file.txt' });
  await fileRow.waitFor();
  await usage.getByText('21 B / 1 GiB', { exact: true }).waitFor();

  await page.reload();
  await fileRow.waitFor();
  await fileRow.getByRole('button', { name: t.downloadFile, exact: true }).click();
  const downloadConfirmText = t.fileDownloadConfirm.replace('{name}', 'browser-file.txt');
  const downloadConfirm = page.locator('.ant-popconfirm').filter({ hasText: downloadConfirmText });
  await downloadConfirm.getByRole('button', { name: new RegExp(t.downloadFile.split('').join('\\s*')) }).waitFor({ timeout: 5000 });
  const downloadPromise = page.waitForEvent('download');
  // A Playwright download event may resolve without a matching page response event.
  void downloadPromise.catch(() => {});
  await downloadConfirm.getByRole('button', { name: new RegExp(t.downloadFile.split('').join('\\s*')) }).click({ timeout: 5000 });
  const download = await downloadPromise;
  assert.equal(await download.failure(), null);
  assert.equal(download.suggestedFilename(), 'browser-file.txt');
  const chunks = [];
  for await (const chunk of await download.createReadStream()) chunks.push(chunk);
  assert.equal(Buffer.concat(chunks).toString(), 'browser file contents');

  await downloadConfirm.waitFor({ state: 'hidden', timeout: 5000 });
  await fileRow.getByRole('button', { name: t.moveToTrash, exact: true }).click({ timeout: 5000 });
  const moveConfirmText = t.moveToTrashConfirm.replace('{name}', 'browser-file.txt');
  const moveConfirm = page.locator('.ant-popconfirm').filter({ hasText: moveConfirmText });
  await moveConfirm.waitFor({ state: 'visible', timeout: 5000 });
  const moved = page.waitForResponse(r => r.url().includes(`/api/account/files/${fileId}?`) && r.request().method() === 'DELETE', { timeout: 10000 }).catch(() => null);
  await moveConfirm.getByRole('button', { name: new RegExp(t.moveToTrash.split('').join('\\s*')) }).click({ timeout: 5000 });
  const moveResponse = await moved;
  assert.ok(moveResponse, 'move-to-trash UI action returned no browser response');
  assert.equal(moveResponse.status(), 204);
  await fileRow.waitFor({ state: 'hidden' });
  await usage.getByText('21 B / 1 GiB', { exact: true }).waitFor();
  await page.getByRole('tab', { name: t.fileTrash, exact: true }).click();
  const trashRow = page.getByRole('row').filter({ hasText: 'browser-file.txt' });
  await trashRow.waitFor();
  await page.reload();
  await page.getByRole('tab', { name: t.fileTrash, exact: true }).click();
  await page.getByRole('row').filter({ hasText: 'browser-file.txt' }).waitFor();

  const restoreResponse = page.waitForResponse(r => r.url().includes(`/api/account/files/${fileId}/restore?`) && r.request().method() === 'POST');
  await page.getByRole('row').filter({ hasText: 'browser-file.txt' }).getByRole('button', { name: t.restoreFile, exact: true }).click();
  assert.equal((await restoreResponse).status(), 204);
  await page.getByRole('tab', { name: t.filesTab, exact: true }).click();
  await fileRow.waitFor();
  await fileRow.getByRole('button', { name: t.moveToTrash, exact: true }).click({ timeout: 5000 });
  const moveAgainConfirm = page.locator('.ant-popconfirm').filter({ hasText: moveConfirmText });
  await moveAgainConfirm.waitFor({ state: 'visible', timeout: 5000 });
  const movedAgain = page.waitForResponse(r => r.url().includes(`/api/account/files/${fileId}?`) && r.request().method() === 'DELETE', { timeout: 10000 }).catch(() => null);
  await moveAgainConfirm.getByRole('button', { name: new RegExp(t.moveToTrash.split('').join('\\s*')) }).click({ timeout: 5000 });
  const moveAgainResponse = await movedAgain;
  assert.ok(moveAgainResponse, 'second move-to-trash UI action returned no browser response');
  assert.equal(moveAgainResponse.status(), 204);
  await page.getByRole('tab', { name: t.fileTrash, exact: true }).click();
  const finalTrashRow = page.getByRole('row').filter({ hasText: 'browser-file.txt' });
  await finalTrashRow.waitFor();
  await finalTrashRow.getByRole('button', { name: t.purgeFile, exact: true }).click();
  const purgeConfirmText = t.purgeFileConfirm.replace('{name}', 'browser-file.txt');
  const confirmPurge = page.locator('.ant-popconfirm').filter({ hasText: purgeConfirmText });
  await confirmPurge.getByRole('button', { name: new RegExp(t.purgeFile.split('').join('\\s*')) }).waitFor({ timeout: 5000 });
  const purged = page.waitForResponse(r => r.url().includes(`/api/account/files/${fileId}/purge?`) && r.request().method() === 'DELETE', { timeout: 10000 }).catch(() => null);
  await confirmPurge.getByRole('button', { name: new RegExp(t.purgeFile.split('').join('\\s*')) }).click({ timeout: 5000 });
  const purgeResponse = await purged;
  assert.ok(purgeResponse, 'permanent-delete UI action returned no browser response');
  assert.equal(purgeResponse.status(), 204);
  await finalTrashRow.waitFor({ state: 'hidden' });
  await usage.getByText('21 B / 1 GiB', { exact: true }).waitFor();
  const pendingUsage = await (await authenticatedGet('/api/account/files/usage')).json();
  assert.equal(pendingUsage.chargedBytes, 21);
  assert.equal(pendingUsage.fileCount, 1);
  assert.equal(pendingUsage.pendingDeletingCount, 1);
  assert.equal(pendingUsage.pendingDeletingBytes, 21);
  await page.reload();
  await page.getByRole('region', { name: t.fileUsage, exact: true }).getByText('21 B / 1 GiB', { exact: true }).waitFor({ timeout: 15000 });
  const visibleFiles = await (await authenticatedGet('/api/account/files?pageSize=100')).json();
  const visibleTrash = await (await authenticatedGet('/api/account/files/trash?pageSize=100')).json();
  assert.ok(!visibleFiles.items.some(item => item.id === fileId), 'Purge hides the pending file from the main list');
  assert.ok(!visibleTrash.items.some(item => item.id === fileId), 'Purge hides the pending file from the trash list');

  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log('PASS: owner isolation, upload/download, trash persistence after reload, quota retention, restore, confirmed purge, hidden DELETING file and 21 B charge across reload; 24-hour PostgreSQL grace remains pending');
} finally {
  // This direct deletion only removes this script's private synthetic QA owner;
  // it is teardown, not evidence that the product cleanup worker completed.
  sql(`DELETE FROM platform_audit WHERE actor='${username}' OR target_id='${fileId ?? owner}'; DELETE FROM platform_file_blob_chunk WHERE file_id IN (SELECT id FROM platform_file WHERE owner_id='${owner}'); DELETE FROM platform_file WHERE owner_id='${owner}'; DELETE FROM spring_session WHERE principal_name='${username}'; DELETE FROM platform_user WHERE id='${owner}' AND username='${username}'`);
  await context?.close();
  await browser.close();
}
