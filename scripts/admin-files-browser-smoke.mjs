import { loadQaRunContext } from './qa-run-guard.mjs';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { chromium, expect } from '@playwright/test';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';

const { config, baseURL, projectRoot } = loadQaRunContext();
const t = await loadLocaleMessages('zh-CN');
const filename = `managed-files-${Date.now()}.txt`;
const payload = Buffer.from('managed file center smoke');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: baseURL, viewport: { width: 1213, height: 886 } });
let fileId;
let primaryError;
let currentStage = 'browser startup';
const apiTrace = [];

function setStage(stage) {
  currentStage = stage;
  console.log(`[admin-files] ${stage}`);
}

function rememberApiTrace(entry) {
  apiTrace.push(entry);
  if (apiTrace.length > 20) apiTrace.shift();
}

context.on('requestfailed', request => {
  const url = new URL(request.url());
  if (url.origin !== baseURL || !url.pathname.startsWith('/api/')) return;
  rememberApiTrace({ kind: 'failed', method: request.method(), path: url.pathname.replace(/[0-9a-f-]{36}/gi, ':id'), reason: request.failure()?.errorText ?? 'unknown' });
});
context.on('response', response => {
  const url = new URL(response.url());
  if (url.origin !== baseURL || !url.pathname.startsWith('/api/') || response.status() < 400) return;
  rememberApiTrace({ kind: 'http', status: response.status(), method: response.request().method(), path: url.pathname.replace(/[0-9a-f-]{36}/gi, ':id') });
});

const sql = statement => execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'], {
  cwd: path.join(projectRoot, 'scripts'),
  env: process.env,
  input: statement,
  encoding: 'utf8',
  timeout: 15_000,
  stdio: ['pipe', 'pipe', 'pipe'],
}).trim();

const sqlQuote = value => `'${String(value).replaceAll("'", "''")}'`;

function readRetirement(fileIdValue, graceStartedAt) {
  const output = sql(`
    SELECT count(*)::text || '|' ||
           count(*) FILTER (WHERE retire_after IS NULL)::text || '|' ||
           count(*) FILTER (WHERE retire_after < ${sqlQuote(graceStartedAt)}::timestamptz + interval '24 hours')::text || '|' ||
           coalesce(min(retire_after)::text, '') || '|' || coalesce(max(retire_after)::text, '')
      FROM platform_file_blob_chunk WHERE file_id=${sqlQuote(fileIdValue)};
  `);
  const [chunkCount, missingCount, shortGraceCount, earliestRetireAfter, latestRetireAfter] = output.split('|');
  assert.equal(output.split('|').length, 5, 'Retired chunk query must return one aggregate row');
  return {
    chunkCount: Number(chunkCount),
    missingCount: Number(missingCount),
    shortGraceCount: Number(shortGraceCount),
    earliestRetireAfter,
    latestRetireAfter,
  };
}

async function waitUntilFileRemoved(timeoutMs = 70_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const response = await context.request.get(`/api/account/files/${fileId}`);
    if (response.status() === 404) return;
    assert.equal(response.status(), 200, await response.text());
    assert.equal((await response.json()).state, 'DELETING', 'The synthetic file must stay fenced while cleanup runs');
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert.fail('The accelerated synthetic file was not physically cleaned within one 60-second sweep interval.');
}

async function csrfHeaders() {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  return { [csrf.headerName]: csrf.token };
}

async function clearFile() {
  if (!fileId) return;
  assert.match(fileId, /^[a-f0-9-]{36}$/i, 'Cleanup may touch only this run\'s synthetic file UUID');
  let response = await context.request.get(`/api/account/files/${fileId}`);
  if (response.status() === 404) return;
  if (!response.ok()) return;
  let file = await response.json();
  if (file.state === 'READY') {
    await context.request.delete(`/api/account/files/${fileId}?version=${file.version}`, { headers: await csrfHeaders() });
    response = await context.request.get(`/api/account/files/${fileId}`);
    if (response.status() === 404 || !response.ok()) return;
    file = await response.json();
  }
  if (file.state === 'TRASHED') {
    await context.request.delete(`/api/account/files/${fileId}/purge?version=${file.version}`, { headers: await csrfHeaders() });
    response = await context.request.get(`/api/account/files/${fileId}`);
    if (response.status() === 404 || !response.ok()) return;
    file = await response.json();
  }
  if (file.state === 'DELETING') {
    sql(`UPDATE platform_file_blob_chunk SET retire_after=clock_timestamp()-interval '1 second' WHERE file_id=${sqlQuote(fileId)} AND retire_after IS NOT NULL`);
    sql(`UPDATE platform_file SET updated_at=clock_timestamp()-interval '2 minutes' WHERE id=${sqlQuote(fileId)} AND state='DELETING'`);
    await waitUntilFileRemoved();
  }
}

try {
  setStage('login and usage baseline');
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  const login = await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } });
  assert.equal(login.status(), 200, await login.text());
  const before = await (await context.request.get('/api/account/files/usage')).json();
  setStage('upload and admin listing');
  const upload = await context.request.post(`/api/account/files?filename=${encodeURIComponent(filename)}`, { data: payload, headers: { 'Content-Type': 'application/octet-stream', ...(await csrfHeaders()) } });
  assert.equal(upload.status(), 201, await upload.text());
  const uploaded = await upload.json();
  fileId = uploaded.id;
  assert.equal(uploaded.state, 'READY');
  const afterUpload = await (await context.request.get('/api/account/files/usage')).json();
  assert.equal(afterUpload.chargedBytes, before.chargedBytes + payload.length);
  assert.equal(afterUpload.fileCount, before.fileCount + 1);

  const managed = await context.request.get(`/api/platform/files?search=${encodeURIComponent(filename)}&page=1&pageSize=20`);
  assert.equal(managed.status(), 200, await managed.text());
  const listed = await managed.json();
  assert.equal(listed.total, 1);
  assert.equal(listed.items[0].id, fileId);
  assert.equal(listed.items[0].ownerUsername, 'admin');

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/dashboard/files-all');
  await expect(page.getByRole('heading', { name: t.allFiles, exact: true })).toBeVisible();
  const row = page.getByRole('row').filter({ hasText: filename });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText('管理员 (admin)');

  setStage('download and verify content');
  await row.getByRole('button', { name: t.downloadFile, exact: true }).click();
  const downloadConfirmText = t.fileDownloadConfirm.replace('{name}', filename);
  const downloadConfirm = page.locator('.ant-popconfirm').filter({ hasText: downloadConfirmText });
  await expect(downloadConfirm.getByRole('button', { name: new RegExp(t.downloadFile.split('').join('\\s*')) })).toBeVisible({ timeout: 5000 });
  const downloadPromise = page.waitForEvent('download');
  // Chromium may emit a download event without exposing a page response event.
  void downloadPromise.catch(() => {});
  await downloadConfirm.getByRole('button', { name: new RegExp(t.downloadFile.split('').join('\\s*')) }).click({ timeout: 5000 });
  const download = await downloadPromise;
  assert.equal(await download.failure(), null);
  assert.equal(download.suggestedFilename(), filename);
  const stream = await download.createReadStream();
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  assert.deepEqual(Buffer.concat(chunks), payload);

  await expect(downloadConfirm).toBeHidden({ timeout: 5000 });
  setStage('trash and restore');
  await row.getByRole('button', { name: t.moveToTrash, exact: true }).click({ timeout: 5000 });
  const moveConfirmText = t.moveToTrashConfirm.replace('{name}', filename);
  const moveConfirm = page.locator('.ant-popconfirm').filter({ hasText: moveConfirmText });
  await expect(moveConfirm).toBeVisible({ timeout: 5000 });
  const moveResponsePromise = page.waitForResponse(response => response.url().includes(`/api/platform/files/${fileId}?`) && response.request().method() === 'DELETE', { timeout: 10000 }).catch(() => null);
  await moveConfirm.getByRole('button', { name: new RegExp(t.moveToTrash.split('').join('\\s*')) }).click({ timeout: 5000 });
  const moveResponse = await moveResponsePromise;
  assert.ok(moveResponse, 'move-to-trash UI action returned no browser response');
  assert.equal(moveResponse.status(), 204);
  await expect(row).toHaveCount(0);
  let usage = await (await context.request.get('/api/account/files/usage')).json();
  assert.equal(usage.chargedBytes, before.chargedBytes + payload.length);
  assert.equal(usage.fileCount, before.fileCount + 1);
  const trashResponse = await context.request.get(`/api/platform/files/trash?search=${encodeURIComponent(filename)}&page=1&pageSize=20`);
  assert.equal(trashResponse.status(), 200, await trashResponse.text());
  assert.equal((await trashResponse.json()).total, 1);

  await page.getByRole('tab', { name: t.fileTrash, exact: true }).click();
  const trashRow = page.getByRole('row').filter({ hasText: filename });
  await expect(trashRow).toContainText('管理员 (admin)');
  await page.reload();
  await page.getByRole('tab', { name: t.fileTrash, exact: true }).click();
  await expect(page.getByRole('row').filter({ hasText: filename })).toHaveCount(1);
  const restoreResponse = page.waitForResponse(response => response.url().includes(`/api/platform/files/${fileId}/restore?`) && response.request().method() === 'POST');
  await page.getByRole('row').filter({ hasText: filename }).getByRole('button', { name: t.restoreFile, exact: true }).click();
  assert.equal((await restoreResponse).status(), 204);
  await page.getByRole('tab', { name: t.filesTab, exact: true }).click();
  await expect(page.getByRole('row').filter({ hasText: filename })).toHaveCount(1);

  setStage('second trash and purge');
  const fileRow = page.getByRole('row').filter({ hasText: filename });
  await fileRow.getByRole('button', { name: t.moveToTrash, exact: true }).click({ timeout: 5000 });
  const moveAgainConfirm = page.locator('.ant-popconfirm').filter({ hasText: moveConfirmText });
  await expect(moveAgainConfirm).toBeVisible({ timeout: 5000 });
  const movedAgain = page.waitForResponse(response => response.url().includes(`/api/platform/files/${fileId}?`) && response.request().method() === 'DELETE', { timeout: 10000 }).catch(() => null);
  await moveAgainConfirm.getByRole('button', { name: new RegExp(t.moveToTrash.split('').join('\\s*')) }).click({ timeout: 5000 });
  const movedAgainResponse = await movedAgain;
  assert.ok(movedAgainResponse, 'second move-to-trash UI action returned no browser response');
  assert.equal(movedAgainResponse.status(), 204);
  await page.getByRole('tab', { name: t.fileTrash, exact: true }).click();
  const finalTrashRow = page.getByRole('row').filter({ hasText: filename });
  await expect(finalTrashRow).toHaveCount(1);
  await finalTrashRow.getByRole('button', { name: t.purgeFile, exact: true }).click();
  const purgeConfirmText = t.purgeFileConfirm.replace('{name}', filename);
  const purgeConfirm = page.locator('.ant-popconfirm').filter({ hasText: purgeConfirmText });
  await expect(purgeConfirm).toBeVisible({ timeout: 5000 });
  const graceStartedAt = sql('SELECT clock_timestamp()::text');
  const purgeResponsePromise = page.waitForResponse(response => response.url().includes(`/api/platform/files/${fileId}/purge?`) && response.request().method() === 'DELETE', { timeout: 10000 }).catch(() => null);
  await purgeConfirm.getByRole('button', { name: new RegExp(t.purgeFile.split('').join('\\s*')) }).click({ timeout: 5000 });
  const purgeResponse = await purgeResponsePromise;
  assert.ok(purgeResponse, 'permanent-delete UI action returned no browser response');
  assert.equal(purgeResponse.status(), 204);
  await expect(finalTrashRow).toHaveCount(0);
  const deleting = await (await context.request.get(`/api/account/files/${fileId}`)).json();
  assert.equal(deleting.state, 'DELETING');
  usage = await (await context.request.get('/api/account/files/usage')).json();
  assert.equal(usage.chargedBytes, before.chargedBytes + payload.length, 'Purge keeps synthetic bytes charged during the grace period');
  assert.equal(usage.fileCount, before.fileCount + 1);
  assert.equal(usage.pendingDeletingCount, before.pendingDeletingCount + 1);
  assert.equal(usage.pendingDeletingBytes, before.pendingDeletingBytes + payload.length);

  setStage('verify immutable grace and pending quota');
  const firstRetirement = readRetirement(fileId, graceStartedAt);
  assert.ok(firstRetirement.chunkCount > 0, 'The QA file must have PostgreSQL blob chunks to verify the 24-hour grace');
  assert.equal(firstRetirement.missingCount, 0, 'Every PostgreSQL chunk must have a retire_after timestamp');
  assert.equal(firstRetirement.shortGraceCount, 0, 'Every chunk must retain at least 24 hours from the pre-purge database timestamp');

  const repeatPurge = await context.request.delete(`/api/platform/files/${fileId}/purge?version=${deleting.version}`, { headers: await csrfHeaders() });
  assert.equal(repeatPurge.status(), 409, 'A repeated purge while DELETING must be rejected');
  const repeatedRetirement = readRetirement(fileId, graceStartedAt);
  assert.deepEqual(repeatedRetirement, firstRetirement, 'A rejected repeated purge must not change the grace timestamps');

  sql(`UPDATE platform_file_blob_chunk SET retire_after=clock_timestamp()-interval '1 second' WHERE file_id=${sqlQuote(fileId)} AND retire_after IS NOT NULL`);
  // FileCleanup only selects DELETING rows with updated_at older than one minute.
  // Age this synthetic row so the next scheduled sweep can finalize it immediately.
  sql(`UPDATE platform_file SET updated_at=clock_timestamp()-interval '2 minutes' WHERE id=${sqlQuote(fileId)} AND state='DELETING'`);
  const advancedChunks = Number(sql(`SELECT count(*) FROM platform_file_blob_chunk WHERE file_id=${sqlQuote(fileId)} AND retire_after<=clock_timestamp()`));
  assert.equal(advancedChunks, firstRetirement.chunkCount, 'Only this synthetic file\'s chunks are advanced to trigger cleanup');
  setStage('await scheduled physical cleanup');
  await waitUntilFileRemoved();
  assert.equal(Number(sql(`SELECT count(*) FROM platform_file WHERE id=${sqlQuote(fileId)}`)), 0);
  assert.equal(Number(sql(`SELECT count(*) FROM platform_file_blob_chunk WHERE file_id=${sqlQuote(fileId)}`)), 0);
  usage = await (await context.request.get('/api/account/files/usage')).json();
  assert.equal(usage.chargedBytes, before.chargedBytes);
  assert.equal(usage.fileCount, before.fileCount);
  assert.equal(usage.pendingDeletingCount, before.pendingDeletingCount);
  assert.equal(usage.pendingDeletingBytes, before.pendingDeletingBytes);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  setStage('complete');
  console.log('PASS: global file center, upload/download, trash and restore, charged DELETING state, immutable 24-hour retire_after on repeated purge, synthetic-time accelerated cleanup and baseline quota restoration');
} catch (error) {
  primaryError = error;
  console.error(`[admin-files] main flow failed at ${currentStage} (${error?.name ?? 'Error'}); recent API trace=${JSON.stringify(apiTrace.slice(-12))}`);
  throw error;
} finally {
  const finalizationErrors = [];
  setStage('finally cleanup');
  try {
    await clearFile();
  } catch (error) {
    finalizationErrors.push(error);
    console.error(`[admin-files] fixture cleanup failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
  }
  try {
    await context.close();
  } catch (error) {
    finalizationErrors.push(error);
    console.error(`[admin-files] context close failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
  }
  try {
    await browser.close();
  } catch (error) {
    finalizationErrors.push(error);
    console.error(`[admin-files] browser close failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
  }
  if (finalizationErrors.length && !primaryError) throw finalizationErrors[0];
}
