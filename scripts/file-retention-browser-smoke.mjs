import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { chromium, expect } from '@playwright/test';
import { messages } from '../apps/web/src/platform/i18n/messages.ts';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const t = messages['zh-CN'];
const filename = `retention-policy-${Date.now()}.txt`;
const payload = Buffer.from('retention smoke file');
const sql = statement => execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; sql(sys.stdin.read())'], { cwd: 'scripts', input: statement, stdio: ['pipe', 'pipe', 'pipe'] });
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const context = await browser.newContext({ baseURL: 'http://127.0.0.1:8010', viewport: { width: 1213, height: 886 } });
let fileId;
let originalPolicy;
let policyTouched = false;

async function tokenHeaders() {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  return { [csrf.headerName]: csrf.token };
}

async function readPolicy() {
  const response = await context.request.get('/api/platform/settings/file-retention');
  assert.equal(response.status(), 200, await response.text());
  return response.json();
}

async function savePolicy(snapshot, data) {
  const response = await context.request.put('/api/platform/settings/file-retention', {
    data: { version: snapshot.version, data },
    headers: { ...(await tokenHeaders()), 'Content-Type': 'application/json' },
  });
  assert.equal(response.status(), 200, await response.text());
  return response.json();
}

async function cleanupFile() {
  if (!fileId) return;
  for (let attempt = 0; attempt < 180; attempt += 1) {
    const response = await context.request.get(`/api/account/files/${fileId}`);
    if (response.status() === 404 || !response.ok()) return;
    const file = await response.json();
    if (file.state === 'READY') await context.request.delete(`/api/account/files/${fileId}?version=${file.version}`, { headers: await tokenHeaders() });
    else if (file.state === 'TRASHED') await context.request.delete(`/api/account/files/${fileId}/purge?version=${file.version}`, { headers: await tokenHeaders() });
    else if (file.state !== 'DELETING') return;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
}

try {
  const login = await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: await tokenHeaders(),
  });
  assert.equal(login.status(), 200, await login.text());
  assert.equal(Number(sql("SELECT count(*) FROM platform_file WHERE state='TRASHED'")), 0, 'isolated retention test requires an empty trash');
  originalPolicy = await readPolicy();

  const upload = await context.request.post(`/api/account/files?filename=${encodeURIComponent(filename)}`, {
    data: payload,
    headers: { 'Content-Type': 'application/octet-stream', ...(await tokenHeaders()) },
  });
  assert.equal(upload.status(), 201, await upload.text());
  fileId = (await upload.json()).id;
  let file = await (await context.request.get(`/api/account/files/${fileId}`)).json();
  const moved = await context.request.delete(`/api/account/files/${fileId}?version=${file.version}`, { headers: await tokenHeaders() });
  assert.equal(moved.status(), 204);
  file = await (await context.request.get(`/api/account/files/${fileId}`)).json();
  assert.equal(file.state, 'TRASHED');
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/dashboard/file-retention');
  await page.locator('.settings-form .ant-select').first().waitFor();
  await expect(page.getByRole('button', { name: t.save, exact: true })).toBeEnabled();

  const chooseMode = async label => {
    await page.locator('.settings-form .ant-select').first().click();
    await page.locator('.ant-select-dropdown:visible').getByText(label, { exact: true }).click();
  };
  const saveButton = page.getByRole('button', { name: t.save, exact: true });

  await chooseMode(t.fileRetentionManual);
  await expect(page.locator('.settings-form .ant-input-number-input')).toBeDisabled();
  let saveResponse = page.waitForResponse(response => response.url().includes('/api/platform/settings/file-retention') && response.request().method() === 'PUT');
  await saveButton.click();
  assert.equal((await saveResponse).status(), 200);
  policyTouched = true;
  let snapshot = await readPolicy();
  assert.equal(snapshot.data.mode, 'MANUAL');
  assert.equal(snapshot.data.retentionDays, 30);

  sql(`UPDATE platform_file SET trashed_at=clock_timestamp()-interval '31 days' WHERE id='${fileId}' AND state='TRASHED'`);
  await new Promise(resolve => setTimeout(resolve, 65000));
  const manualTrash = await context.request.get(`/api/account/files/trash?search=${encodeURIComponent(filename)}&page=1&pageSize=10`);
  assert.equal(manualTrash.status(), 200, await manualTrash.text());
  assert.equal((await manualTrash.json()).total, 1, 'manual retention leaves expired trash items untouched');

  await chooseMode(t.fileRetentionAutomatic);
  await expect(page.locator('.settings-form .ant-input-number-input')).toBeEnabled();
  saveResponse = page.waitForResponse(response => response.url().includes('/api/platform/settings/file-retention') && response.request().method() === 'PUT');
  await saveButton.click();
  const confirmation = page.getByRole('dialog', { name: t.fileRetentionConfirmTitle });
  await expect(confirmation).toBeVisible();
  await expect(confirmation).toContainText(t.fileRetentionConfirm.replace('{days}', '30'));
  // The Chinese locale applies letter spacing to modal buttons, which adds
  // visual spaces to the accessible name (for example, "确 认"). Target the
  // primary action within this specific confirmation dialog instead.
  await confirmation.locator('.ant-modal-confirm-btns .ant-btn-primary').click();
  assert.equal((await saveResponse).status(), 200);
  snapshot = await readPolicy();
  assert.equal(snapshot.data.mode, 'AUTOMATIC');
  assert.equal(snapshot.data.retentionDays, 30);
  file = await (await context.request.get(`/api/account/files/${fileId}`)).json();
  assert.equal(file.state, 'DELETING', 'automatic save immediately queues already-expired files');
  const usage = await (await context.request.get('/api/account/files/usage')).json();
  assert.equal(usage.chargedBytes, payload.length, 'queued deletion remains charged until blob and metadata cleanup finish');

  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    if ((await context.request.get(`/api/account/files/${fileId}`)).status() === 404) break;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  assert.equal((await context.request.get(`/api/account/files/${fileId}`)).status(), 404, 'automatic cleanup eventually removes the file');
  assert.deepEqual(errors, []);
  console.log('PASS: file retention settings save, manual mode preserves overdue trash, automatic save confirms and immediately queues overdue files, and queued bytes remain charged until cleanup');
} finally {
  if (policyTouched && originalPolicy) {
    const current = await readPolicy();
    await savePolicy(current, originalPolicy.data);
  }
  await cleanupFile();
  await context.close();
  await browser.close();
}
