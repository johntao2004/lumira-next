import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { chromium, expect } from '@playwright/test';
import { messages } from '../apps/web/src/platform/i18n/messages.ts';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const t = messages['zh-CN'];
const filename = `managed-files-${Date.now()}.txt`;
const payload = Buffer.from('managed file center smoke');
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
let fileId;

async function csrfHeaders() {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  return { [csrf.headerName]: csrf.token };
}

async function clearFile() {
  if (!fileId) return;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const response = await context.request.get(`/api/account/files/${fileId}`);
    if (response.status() === 404 || !response.ok()) return;
    const file = await response.json();
    if (file.state === 'READY') await context.request.delete(`/api/platform/files/${fileId}?version=${file.version}`, { headers: await csrfHeaders() });
    else if (file.state === 'TRASHED') await context.request.delete(`/api/platform/files/${fileId}/purge?version=${file.version}`, { headers: await csrfHeaders() });
    else if (file.state !== 'DELETING') return;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
}

try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  const login = await context.request.post('/api/auth/login', { form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [csrf.headerName]: csrf.token } });
  assert.equal(login.status(), 200, await login.text());
  const before = await (await context.request.get('/api/account/files/usage')).json();
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

  const downloadPromise = page.waitForEvent('download');
  await row.getByRole('link', { name: t.downloadFile, exact: true }).click();
  const download = await downloadPromise;
  assert.equal(await download.failure(), null);
  assert.equal(download.suggestedFilename(), filename);
  const stream = await download.createReadStream();
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  assert.deepEqual(Buffer.concat(chunks), payload);

  await row.getByRole('button', { name: t.moveToTrash, exact: true }).click();
  const moveResponse = page.waitForResponse(response => response.url().includes(`/api/platform/files/${fileId}?`) && response.request().method() === 'DELETE');
  const moveConfirm = page.locator('.ant-popconfirm');
  await expect(moveConfirm).toBeVisible();
  await moveConfirm.getByRole('button', { name: t.moveToTrash, exact: true }).click();
  assert.equal((await moveResponse).status(), 204);
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

  const fileRow = page.getByRole('row').filter({ hasText: filename });
  await fileRow.getByRole('button', { name: t.moveToTrash, exact: true }).click();
  const movedAgain = page.waitForResponse(response => response.url().includes(`/api/platform/files/${fileId}?`) && response.request().method() === 'DELETE');
  await page.locator('.ant-popconfirm').getByRole('button', { name: t.moveToTrash, exact: true }).click();
  assert.equal((await movedAgain).status(), 204);
  await page.getByRole('tab', { name: t.fileTrash, exact: true }).click();
  const finalTrashRow = page.getByRole('row').filter({ hasText: filename });
  await expect(finalTrashRow).toHaveCount(1);
  await finalTrashRow.getByRole('button', { name: t.purgeFile, exact: true }).click();
  const purgeConfirm = page.locator('.ant-popconfirm');
  await expect(purgeConfirm).toBeVisible();
  const purgeResponse = page.waitForResponse(response => response.url().includes(`/api/platform/files/${fileId}/purge?`) && response.request().method() === 'DELETE');
  await purgeConfirm.getByRole('button', { name: t.purgeFile, exact: true }).click();
  assert.equal((await purgeResponse).status(), 204);
  await expect(finalTrashRow).toHaveCount(0);
  usage = await (await context.request.get('/api/account/files/usage')).json();
  assert.equal(usage.chargedBytes, before.chargedBytes + payload.length, 'purge request keeps bytes charged until cleanup completes');
  for (let attempt = 0; attempt < 180; attempt += 1) {
    usage = await (await context.request.get('/api/account/files/usage')).json();
    if (usage.chargedBytes === before.chargedBytes && usage.fileCount === before.fileCount) break;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.equal(usage.chargedBytes, before.chargedBytes);
  assert.equal(usage.fileCount, before.fileCount);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log('PASS: global file center owner projection, download bytes, trash persistence, quota accounting, restore, confirmed purge and asynchronous physical cleanup');
} finally {
  await clearFile();
  await context.close();
  await browser.close();
}
