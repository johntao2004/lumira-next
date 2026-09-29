import { chromium } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { messages } from '../apps/web/src/platform/i18n/messages.ts';

const t = messages['zh-CN'];
const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const owner = randomUUID();
const username = `qa_files_${owner.replaceAll('-', '')}`;
let fileId;
const sql = statement => execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; sql(sys.stdin.read())'], { cwd: 'scripts', input: statement, stdio: ['pipe', 'pipe', 'pipe'] });
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
let context;

async function csrfHeaders() {
  const token = await (await context.request.get('/api/auth/csrf')).json();
  return { [token.headerName]: token.token };
}

async function clearFile() {
  if (!fileId || !context) return;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const response = await context.request.get(`/api/account/files/${fileId}`);
    if (response.status() === 404) return;
    if (!response.ok()) return;
    const entry = await response.json();
    if (entry.state === 'READY') {
      await context.request.delete(`/api/account/files/${fileId}?version=${entry.version}`, { headers: await csrfHeaders() });
    } else if (entry.state === 'TRASHED') {
      await context.request.delete(`/api/account/files/${fileId}/purge?version=${entry.version}`, { headers: await csrfHeaders() });
    } else if (entry.state !== 'DELETING') {
      return;
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
}

try {
  sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${owner}','${username}','Files QA',password_hash FROM platform_user WHERE username='admin'`);
  context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
  const token = await (await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login', { form: { username, password: config.LUMIRA_BOOTSTRAP_PASSWORD }, headers: { [token.headerName]: token.token } })).status(), 200);
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
  const downloadPromise = page.waitForEvent('download');
  await fileRow.getByRole('link', { name: t.downloadFile }).click();
  const download = await downloadPromise;
  assert.equal(download.suggestedFilename(), 'browser-file.txt');
  const chunks = [];
  for await (const chunk of await download.createReadStream()) chunks.push(chunk);
  assert.equal(Buffer.concat(chunks).toString(), 'browser file contents');

  await fileRow.getByRole('button', { name: t.moveToTrash, exact: true }).click();
  const moved = page.waitForResponse(r => r.url().includes(`/api/account/files/${fileId}?`) && r.request().method() === 'DELETE');
  await page.locator('.ant-popconfirm').getByRole('button', { name: new RegExp(t.moveToTrash.split('').join('\\s*')) }).click({ timeout: 5000 });
  assert.equal((await moved).status(), 204);
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
  await fileRow.getByRole('button', { name: t.moveToTrash, exact: true }).click();
  const movedAgain = page.waitForResponse(r => r.url().includes(`/api/account/files/${fileId}?`) && r.request().method() === 'DELETE');
  await page.locator('.ant-popconfirm').getByRole('button', { name: new RegExp(t.moveToTrash.split('').join('\\s*')) }).click();
  assert.equal((await movedAgain).status(), 204);
  await page.getByRole('tab', { name: t.fileTrash, exact: true }).click();
  const finalTrashRow = page.getByRole('row').filter({ hasText: 'browser-file.txt' });
  await finalTrashRow.waitFor();
  await finalTrashRow.getByRole('button', { name: t.purgeFile, exact: true }).click();
  const confirmPurge = page.locator('.ant-popconfirm');
  await confirmPurge.getByRole('button', { name: new RegExp(t.purgeFile.split('').join('\\s*')) }).waitFor();
  const purged = page.waitForResponse(r => r.url().includes(`/api/account/files/${fileId}/purge?`) && r.request().method() === 'DELETE');
  await confirmPurge.getByRole('button', { name: new RegExp(t.purgeFile.split('').join('\\s*')) }).click();
  assert.equal((await purged).status(), 204);
  await finalTrashRow.waitFor({ state: 'hidden' });
  await usage.getByText('21 B / 1 GiB', { exact: true }).waitFor();
  let cleaned = false;
  const cleanupDeadline = Date.now() + 180000;
  while (Date.now() < cleanupDeadline) {
    const currentUsage = await (await context.request.get('/api/account/files/usage')).json();
    if (currentUsage.chargedBytes === 0 && currentUsage.fileCount === 0) { cleaned = true; break; }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.equal(cleaned, true, 'background cleanup releases file usage');
  await page.reload();
  await page.getByRole('region', { name: t.fileUsage, exact: true }).getByText('0 B / 1 GiB', { exact: true }).waitFor({ timeout: 15000 });

  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  console.log('PASS: owner isolation, upload/download, trash persistence after reload, quota retention, restore, confirmed purge, cleanup completion, and no browser errors or desktop overflow');
} finally {
  await clearFile();
  sql(`DELETE FROM platform_audit WHERE actor='${username}'; DELETE FROM spring_session WHERE principal_name='${username}'; DELETE FROM platform_user WHERE id='${owner}'`);
  await context?.close();
  await browser.close();
}
