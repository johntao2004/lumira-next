import { loadQaRunContext } from './qa-run-guard.mjs';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { chromium, expect } from '@playwright/test';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
import { restoreOwnedPolicy, samePolicyData } from './file-retention-policy-ownership.mjs';

const { config, baseURL, projectRoot } = loadQaRunContext();
const t = await loadLocaleMessages('zh-CN');
const evidenceDir = path.join(projectRoot, '.runtime', 'security-remaining-20261009');
const evidenceRunId = randomUUID().replaceAll('-', '').slice(0, 12);
mkdirSync(evidenceDir, { recursive: true, mode: 0o700 });
const screenshotPath = label => path.join(evidenceDir, `file-retention-${evidenceRunId}-${label}.png`);
const filename = `retention-policy-${Date.now()}.txt`;
const payload = Buffer.from('retention smoke file');
const sql = statement => execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'], {
  cwd: path.join(projectRoot, 'scripts'),
  env: process.env,
  input: statement,
  encoding: 'utf8',
  timeout: 15_000,
  stdio: ['pipe', 'pipe', 'pipe'],
}).trim();
const sqlQuote = value => `'${String(value).replaceAll("'", "''")}'`;
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const adminContext = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
const context = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
let otherContext;
let page;
let writer;
let otherWriter;
const temporaryActors = [];
let fileId;
let originalPolicy;
const policyWriteIntents = [];
const otherPolicyWriteIntents = [];
let primaryError;
let currentStage = 'browser startup';
const apiTrace = [];

function setStage(stage) {
  currentStage = stage;
  console.log(`[file-retention] ${stage}`);
}

function rememberApiTrace(entry) {
  apiTrace.push(entry);
  if (apiTrace.length > 20) apiTrace.shift();
}

function traceContext(target) {
  target.on('requestfailed', request => {
    const url = new URL(request.url());
    if (url.origin !== baseURL || !url.pathname.startsWith('/api/')) return;
    rememberApiTrace({ kind: 'failed', method: request.method(), path: url.pathname.replace(/[0-9a-f-]{36}/gi, ':id'), reason: request.failure()?.errorText ?? 'unknown' });
  });
  target.on('response', response => {
    const url = new URL(response.url());
    if (url.origin !== baseURL || !url.pathname.startsWith('/api/') || response.status() < 400) return;
    rememberApiTrace({ kind: 'http', status: response.status(), method: response.request().method(), path: url.pathname.replace(/[0-9a-f-]{36}/gi, ':id') });
  });
}

traceContext(adminContext);
traceContext(context);

async function waitUntilFileRemoved(timeoutMs = 70_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const response = await adminContext.request.get(`/api/account/files/${fileId}`, { headers: await authorizationHeaders(adminContext) });
    if (response.status() === 404) return;
    assert.equal(response.status(), 200, await response.text());
    assert.equal((await response.json()).state, 'DELETING', 'The synthetic file must stay fenced while cleanup runs');
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert.fail('The accelerated synthetic file was not physically cleaned within one 60-second sweep interval.');
}

async function csrfHeaders(target = context) {
  const csrf = await (await target.request.get('/api/auth/csrf')).json();
  return { [csrf.headerName]: csrf.token };
}

async function authorizationHeaders(target = context) {
  const response = await target.request.get('/api/auth/me');
  if (response.status() === 401) return {};
  assert.equal(response.status(), 200, `Could not read the current authorization context (HTTP ${response.status()}).`);
  const identity = await response.json();
  const revision = identity.authorizationContext?.revision;
  return typeof revision === 'string' && revision
    ? { 'X-Lumira-Authorization-Context-Revision': revision }
    : {};
}

async function tokenHeaders(target = context) {
  return { ...(await csrfHeaders(target)), ...(await authorizationHeaders(target)) };
}

async function writeJson(target, endpoint, method, data) {
  return target.request.fetch(endpoint, {
    method,
    data,
    headers: { ...(await tokenHeaders(target)), 'Content-Type': 'application/json' },
  });
}

async function login(target, username, password) {
  const response = await target.request.post('/api/auth/login', {
    form: { username, password },
    headers: await csrfHeaders(target),
  });
  assert.equal(response.status(), 200, `Login failed for isolated QA actor ${username} (HTTP ${response.status()}).`);
}

async function createTemporarySettingsActor(label) {
  const suffix = randomUUID().replaceAll('-', '');
  const username = `qa_retention_${label}_${suffix}`;
  const password = `QaA9!${randomBytes(36).toString('base64url')}`;
  const userResponse = await writeJson(adminContext, '/api/platform/users', 'POST', {
    username,
    displayName: `Retention ${label} QA`,
    password,
  });
  assert.equal(userResponse.status(), 201, `Could not create the isolated ${label} settings actor (HTTP ${userResponse.status()}).`);
  const user = await userResponse.json();
  const actor = { username, password, userId: user.id, roleId: null };
  temporaryActors.push(actor);

  const roleCode = `qa_retention_${label}_${suffix}`;
  const roleResponse = await writeJson(adminContext, '/api/platform/roles', 'POST', {
    code: roleCode,
    name: `Retention ${label} QA`,
    enabled: true,
    dataScope: 'ALL',
    authorities: ['platform:modules:access', 'platform:file-retention:access', 'platform:file-retention:update'],
  });
  assert.equal(roleResponse.status(), 201, `Could not create the isolated ${label} settings role (HTTP ${roleResponse.status()}).`);
  const role = await roleResponse.json();
  actor.roleId = role.id;

  const assignment = await writeJson(adminContext, `/api/platform/users/${user.id}/roles`, 'PUT', {
    version: user.version,
    roleIds: [role.id],
  });
  assert.equal(assignment.status(), 200, `Could not grant the isolated ${label} settings role (HTTP ${assignment.status()}).`);
  return actor;
}

async function readPolicy(target = context) {
  const response = await target.request.get('/api/platform/settings/file-retention', { headers: await authorizationHeaders(target) });
  assert.equal(response.status(), 200, await response.text());
  return response.json();
}

async function readPolicyRevision(version, target = context) {
  if (version < 1) return null;
  const response = await target.request.get(`/api/platform/settings/file-retention/history/${version}`, { headers: await authorizationHeaders(target) });
  if (response.status() === 404) return null;
  assert.equal(response.status(), 200, await response.text());
  return response.json();
}

async function savePolicy(snapshot, data, target = context) {
  const response = await target.request.put('/api/platform/settings/file-retention', {
    data: { version: snapshot.version, data },
    headers: { ...(await tokenHeaders(target)), 'Content-Type': 'application/json' },
  });
  if (response.status() === 409) {
    const error = new Error('File-retention policy save conflicted with a newer version; restore stopped without retrying.');
    error.name = 'PolicyVersionConflictError';
    error.status = 409;
    throw error;
  }
  assert.equal(response.status(), 200, await response.text());
  return response.json();
}

function recordPolicyWriteIntent(baseline, proposed) {
  policyWriteIntents.push({ expectedVersion: baseline.version + 1, proposed: { ...proposed } });
}

async function restoreOriginalPolicyIfOwned({ target = context, actor = writer?.username, intents = policyWriteIntents } = {}) {
  return restoreOwnedPolicy({
    actor,
    baseline: originalPolicy,
    intents,
    readCurrent: () => readPolicy(target),
    readRevision: version => readPolicyRevision(version, target),
    save: (snapshot, data) => savePolicy(snapshot, data, target),
  });
}

async function requireFinalPolicyRestore(result) {
  if (result.status === 'not-owned') {
    throw new Error('File-retention policy changed outside this smoke; leaving the current version untouched.');
  }
  if (result.status === 'restored') {
    const revision = await readPolicyRevision(result.restored.version);
    if (revision?.changedBy !== writer.username || !samePolicyData(revision.data, originalPolicy.data)) {
      throw new Error('The restored file-retention revision does not identify this isolated smoke actor.');
    }
  }
}

async function restoreControlledOtherWriteIfOwned() {
  if (!otherWriter || otherPolicyWriteIntents.length === 0 || !originalPolicy) return;
  const outcome = await restoreOriginalPolicyIfOwned({
    target: otherContext,
    actor: otherWriter.username,
    intents: otherPolicyWriteIntents,
  });
  if (outcome.status === 'not-owned') {
    throw new Error('The isolated competing actor no longer owns the current file-retention revision; leaving it untouched.');
  }
}

function policyModeLabel(mode) {
  return mode === 'AUTOMATIC' ? t.fileRetentionAutomatic : t.fileRetentionManual;
}

async function choosePolicyMode(mode) {
  await page.locator('.settings-form .ant-select').first().click();
  await page.locator('.ant-select-dropdown:visible').getByText(policyModeLabel(mode), { exact: true }).click();
}

async function confirmAutomaticSave(retentionDays) {
  const confirmation = page.getByRole('dialog', { name: t.fileRetentionConfirmTitle });
  await expect(confirmation).toBeVisible();
  await expect(confirmation).toContainText(t.fileRetentionConfirm.replace('{days}', String(retentionDays)));
  // The Chinese locale adds visual letter spacing to button accessible names.
  await confirmation.locator('.ant-modal-confirm-btns .ant-btn-primary').click();
}

async function savePolicyThroughUi(proposed, expectedStatus = 200) {
  const saveButton = page.getByRole('button', { name: t.save, exact: true });
  const responsePromise = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/platform/settings/file-retention'
      && response.request().method() === 'PUT');
  await saveButton.click();
  if (proposed.mode === 'AUTOMATIC') await confirmAutomaticSave(proposed.retentionDays);
  const response = await responsePromise;
  assert.equal(response.status(), expectedStatus, `UI settings PUT returned HTTP ${response.status()}, expected ${expectedStatus}.`);
  return response;
}

async function savePolicyThroughUiWithLostResponse(proposed) {
  let serverStatus;
  const routePattern = '**/api/platform/settings/file-retention';
  const handler = async route => {
    if (route.request().method() !== 'PUT') return route.continue();
    const actualResponse = await route.fetch();
    serverStatus = actualResponse.status();
    await actualResponse.dispose();
    await route.abort('failed');
  };
  await page.route(routePattern, handler);
  try {
    const failed = page.waitForEvent('requestfailed', request =>
      new URL(request.url()).pathname === '/api/platform/settings/file-retention'
        && request.method() === 'PUT');
    await page.getByRole('button', { name: t.save, exact: true }).click();
    if (proposed.mode === 'AUTOMATIC') await confirmAutomaticSave(proposed.retentionDays);
    await failed;
  } finally {
    await page.unroute(routePattern, handler);
  }
  assert.equal(serverStatus, 200, 'The actual QA HTTP PUT must commit successfully before its browser response is dropped.');
}

async function setEditorToPolicy(proposed) {
  // Select automatic first so the retention-day input is enabled, then set the
  // requested value before choosing manual mode (which disables that field).
  await choosePolicyMode('AUTOMATIC');
  const days = page.locator('.settings-form .ant-input-number-input').first();
  await expect(days).toBeEnabled();
  await days.fill(String(proposed.retentionDays));
  await choosePolicyMode(proposed.mode);
  if (proposed.mode === 'MANUAL') await expect(days).toBeDisabled();
}

function oppositeModePolicy(data) {
  return {
    mode: data.mode === 'AUTOMATIC' ? 'MANUAL' : 'AUTOMATIC',
    retentionDays: data.retentionDays,
  };
}

async function assertPolicyRevisionOwned(snapshot, actor, data) {
  const revision = await readPolicyRevision(snapshot.version);
  assert.equal(revision?.version, snapshot.version, 'The saved policy revision must exist in persistent history.');
  assert.equal(revision.changedBy, actor.username, 'Persistent setting history must identify the unique QA actor that submitted the save.');
  assert.ok(samePolicyData(revision.data, data), 'The persistent revision payload must match the exact saved policy.');
  return revision;
}

async function assertPolicyRevisionOwnedBy(snapshot, actor, data, target) {
  const revision = await readPolicyRevision(snapshot.version, target);
  assert.equal(revision?.version, snapshot.version, 'The competing policy revision must exist in persistent history.');
  assert.equal(revision.changedBy, actor.username, 'Persistent setting history must identify the competing QA actor.');
  assert.ok(samePolicyData(revision.data, data), 'The competing revision payload must match its submitted policy.');
  return revision;
}

async function cleanupTemporaryActors() {
  if (temporaryActors.length === 0) return;
  for (const actor of temporaryActors) {
    const id = actor.userId;
    const roleId = actor.roleId;
    if (!id) continue;
    const roleSql = roleId ? ` OR target_id=${sqlQuote(roleId)}` : '';
    const roleTables = roleId
      ? `DELETE FROM platform_user_role WHERE user_id=${sqlQuote(id)};
         DELETE FROM platform_role_authority WHERE role_id=${sqlQuote(roleId)};
         DELETE FROM platform_role WHERE id=${sqlQuote(roleId)};`
      : '';
    sql(`DELETE FROM platform_audit WHERE actor=${sqlQuote(actor.username)} OR target_id=${sqlQuote(id)}${roleSql};
         DELETE FROM spring_session WHERE principal_name=${sqlQuote(actor.username)};
         ${roleTables}
         DELETE FROM platform_authority WHERE user_id=${sqlQuote(id)};
         DELETE FROM platform_user WHERE id=${sqlQuote(id)};`);
  }
}

async function cleanupFile() {
  if (!fileId) return;
  assert.match(fileId, /^[a-f0-9-]{36}$/i, 'Cleanup may touch only this run\'s synthetic file UUID');
  for (let transition = 0; transition < 3; transition += 1) {
    const response = await adminContext.request.get(`/api/account/files/${fileId}`, { headers: await authorizationHeaders(adminContext) });
    if (response.status() === 404) return;
    if (!response.ok()) return;
    const file = await response.json();
    if (file.state === 'READY') {
      await adminContext.request.delete(`/api/account/files/${fileId}?version=${file.version}`, { headers: await tokenHeaders(adminContext) });
      continue;
    }
    if (file.state === 'TRASHED') {
      await adminContext.request.delete(`/api/account/files/${fileId}/purge?version=${file.version}`, { headers: await tokenHeaders(adminContext) });
      continue;
    }
    if (file.state === 'DELETING') {
      sql(`UPDATE platform_file SET updated_at=clock_timestamp()-interval '2 minutes' WHERE id=${sqlQuote(fileId)} AND state='DELETING'`);
      await waitUntilFileRemoved();
      return;
    }
    return;
  }
  assert.fail('The synthetic file did not reach DELETING during bounded cleanup.');
}

try {
  setStage('admin setup and unique settings actors');
  await login(adminContext, 'admin', config.LUMIRA_BOOTSTRAP_PASSWORD);
  writer = await createTemporarySettingsActor('writer');
  otherWriter = await createTemporarySettingsActor('other');
  otherContext = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
  traceContext(otherContext);
  await login(context, writer.username, writer.password);
  await login(otherContext, otherWriter.username, otherWriter.password);
  const writerIdentity = await (await context.request.get('/api/auth/me')).json();
  assert.ok(writerIdentity.globalAuthorities.includes('platform:file-retention:access'));
  assert.ok(writerIdentity.globalAuthorities.includes('platform:file-retention:update'));
  assert.equal(Number(sql("SELECT count(*) FROM platform_file WHERE state='TRASHED'")), 0, 'isolated retention test requires an empty trash');
  originalPolicy = await readPolicy();
  const beforeUsage = await (await adminContext.request.get('/api/account/files/usage', { headers: await authorizationHeaders(adminContext) })).json();

  const upload = await adminContext.request.post(`/api/account/files?filename=${encodeURIComponent(filename)}`, {
    data: payload,
    headers: { 'Content-Type': 'application/octet-stream', ...(await tokenHeaders(adminContext)) },
  });
  assert.equal(upload.status(), 201, await upload.text());
  fileId = (await upload.json()).id;
  assert.match(fileId, /^[a-f0-9-]{36}$/i);
  let usage = await (await adminContext.request.get('/api/account/files/usage', { headers: await authorizationHeaders(adminContext) })).json();
  assert.equal(usage.chargedBytes, beforeUsage.chargedBytes + payload.length);
  assert.equal(usage.fileCount, beforeUsage.fileCount + 1);
  let file = await (await adminContext.request.get(`/api/account/files/${fileId}`, { headers: await authorizationHeaders(adminContext) })).json();
  const moved = await adminContext.request.delete(`/api/account/files/${fileId}?version=${file.version}`, { headers: await tokenHeaders(adminContext) });
  assert.equal(moved.status(), 204);
  file = await (await adminContext.request.get(`/api/account/files/${fileId}`, { headers: await authorizationHeaders(adminContext) })).json();
  assert.equal(file.state, 'TRASHED');
  page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/dashboard/file-retention');
  await page.locator('.settings-form .ant-select').first().waitFor();
  await expect(page.getByRole('button', { name: t.save, exact: true })).toBeEnabled();

  setStage('[actual HTTP/browser] committed save with dropped response is recovered from revision owner');
  const droppedResponseProposal = oppositeModePolicy(originalPolicy.data);
  const lostBaseline = await readPolicy();
  assert.ok(samePolicyData(lostBaseline.data, originalPolicy.data));
  await setEditorToPolicy(droppedResponseProposal);
  recordPolicyWriteIntent(lostBaseline, droppedResponseProposal);
  await savePolicyThroughUiWithLostResponse(droppedResponseProposal);
  const lostResponseCommit = await readPolicy();
  assert.equal(lostResponseCommit.version, lostBaseline.version + 1);
  assert.ok(samePolicyData(lostResponseCommit.data, droppedResponseProposal));
  await assertPolicyRevisionOwned(lostResponseCommit, writer, droppedResponseProposal);
  const responseLostRecovery = await restoreOriginalPolicyIfOwned();
  assert.equal(responseLostRecovery.status, 'restored');
  await assertPolicyRevisionOwned(responseLostRecovery.restored, writer, originalPolicy.data);
  console.log('[file-retention] [actual HTTP commit; browser response deliberately dropped] persistent changedBy recovered the own revision and a guarded HTTP PUT restored the baseline.');
  await page.reload();
  await page.locator('.settings-form .ant-select').first().waitFor();

  setStage('[actual HTTP/browser] same-value same-version foreign save is not claimed');
  const collisionBaseline = await readPolicy();
  assert.ok(samePolicyData(collisionBaseline.data, originalPolicy.data));
  const collisionProposal = oppositeModePolicy(originalPolicy.data);
  await setEditorToPolicy(collisionProposal);
  recordPolicyWriteIntent(collisionBaseline, collisionProposal);
  otherPolicyWriteIntents.push({ expectedVersion: collisionBaseline.version + 1, proposed: { ...collisionProposal } });
  const otherSameVersionSave = await savePolicy(collisionBaseline, collisionProposal, otherContext);
  assert.equal(otherSameVersionSave.version, collisionBaseline.version + 1);
  assert.ok(samePolicyData(otherSameVersionSave.data, collisionProposal));
  await assertPolicyRevisionOwnedBy(otherSameVersionSave, otherWriter, collisionProposal, otherContext);
  const sameValueGuard = await restoreOriginalPolicyIfOwned();
  assert.equal(sameValueGuard.status, 'not-owned');
  assert.deepEqual(await readPolicy(), otherSameVersionSave, 'The other actor\'s same-value same-version commit must remain unchanged.');
  const staleUiSave = await savePolicyThroughUi(collisionProposal, 409);
  assert.equal(staleUiSave.status(), 409);
  assert.deepEqual(await readPolicy(), otherSameVersionSave, 'A stale real UI save must not replace the competing commit.');
  await assertPolicyRevisionOwnedBy(await readPolicy(otherContext), otherWriter, collisionProposal, otherContext);
  console.log('[file-retention] [actual HTTP/browser] a different unique actor committed the exact proposed value at the expected version; restore skipped it and the stale UI PUT received 409.');
  otherPolicyWriteIntents.push({ expectedVersion: otherSameVersionSave.version + 1, proposed: { ...originalPolicy.data } });
  const collisionCleanup = await savePolicy(otherSameVersionSave, originalPolicy.data, otherContext);
  assert.equal(collisionCleanup.version, otherSameVersionSave.version + 1);
  await assertPolicyRevisionOwnedBy(collisionCleanup, otherWriter, originalPolicy.data, otherContext);
  await page.reload();
  await page.locator('.settings-form .ant-select').first().waitFor();

  setStage('manual retention UI');
  await setEditorToPolicy({ mode: 'MANUAL', retentionDays: 30 });
  const manualWriteBaseline = await readPolicy();
  assert.ok(samePolicyData(manualWriteBaseline.data, originalPolicy.data), 'File-retention settings changed before the normal UI save');
  recordPolicyWriteIntent(manualWriteBaseline, { mode: 'MANUAL', retentionDays: 30 });
  await savePolicyThroughUi({ mode: 'MANUAL', retentionDays: 30 });
  let snapshot = await readPolicy();
  assert.equal(snapshot.version, manualWriteBaseline.version + 1);
  assert.equal(snapshot.data.mode, 'MANUAL');
  assert.equal(snapshot.data.retentionDays, 30);
  await assertPolicyRevisionOwned(snapshot, writer, { mode: 'MANUAL', retentionDays: 30 });
  await page.screenshot({ path: screenshotPath('manual-ui-saved'), fullPage: true });

  sql(`UPDATE platform_file SET trashed_at=clock_timestamp()-interval '31 days' WHERE id=${sqlQuote(fileId)} AND state='TRASHED'`);
  assert.equal(Number(sql(`SELECT count(*) FROM platform_file WHERE id=${sqlQuote(fileId)} AND state='TRASHED' AND trashed_at<=clock_timestamp()-interval '30 days'`)), 1);
  await new Promise(resolve => setTimeout(resolve, 65000));
  const manualTrash = await adminContext.request.get(`/api/account/files/trash?search=${encodeURIComponent(filename)}&page=1&pageSize=10`, { headers: await authorizationHeaders(adminContext) });
  assert.equal(manualTrash.status(), 200, await manualTrash.text());
  assert.equal((await manualTrash.json()).total, 1, 'manual retention leaves expired trash items untouched');
  file = await (await adminContext.request.get(`/api/account/files/${fileId}`, { headers: await authorizationHeaders(adminContext) })).json();
  assert.equal(file.state, 'TRASHED');
  usage = await (await adminContext.request.get('/api/account/files/usage', { headers: await authorizationHeaders(adminContext) })).json();
  assert.equal(usage.chargedBytes, beforeUsage.chargedBytes + payload.length);
  assert.equal(usage.fileCount, beforeUsage.fileCount + 1);

  setStage('automatic retention UI');
  await choosePolicyMode('AUTOMATIC');
  await expect(page.locator('.settings-form .ant-input-number-input')).toBeEnabled();
  const automaticWriteBaseline = await readPolicy();
  assert.equal(automaticWriteBaseline.version, snapshot.version, 'File-retention settings changed before the automatic-policy write');
  assert.ok(samePolicyData(automaticWriteBaseline.data, snapshot.data), 'File-retention settings changed before the automatic-policy write');
  recordPolicyWriteIntent(automaticWriteBaseline, { mode: 'AUTOMATIC', retentionDays: 30 });
  await savePolicyThroughUi({ mode: 'AUTOMATIC', retentionDays: 30 });
  snapshot = await readPolicy();
  assert.equal(snapshot.data.mode, 'AUTOMATIC');
  assert.equal(snapshot.data.retentionDays, 30);
  await assertPolicyRevisionOwned(snapshot, writer, { mode: 'AUTOMATIC', retentionDays: 30 });
  await page.screenshot({ path: screenshotPath('automatic-ui-saved'), fullPage: true });
  file = await (await adminContext.request.get(`/api/account/files/${fileId}`, { headers: await authorizationHeaders(adminContext) })).json();
  assert.equal(file.state, 'DELETING', 'automatic save immediately queues already-expired files');
  usage = await (await adminContext.request.get('/api/account/files/usage', { headers: await authorizationHeaders(adminContext) })).json();
  assert.equal(usage.chargedBytes, beforeUsage.chargedBytes + payload.length, 'queued deletion remains charged until physical cleanup');
  assert.equal(usage.fileCount, beforeUsage.fileCount + 1);
  assert.equal(usage.pendingDeletingCount, beforeUsage.pendingDeletingCount + 1);
  assert.equal(usage.pendingDeletingBytes, beforeUsage.pendingDeletingBytes + payload.length);

  const pendingChunks = Number(sql(`SELECT count(*) FROM platform_file_blob_chunk WHERE file_id=${sqlQuote(fileId)}`));
  assert.ok(pendingChunks > 0, 'The queued synthetic file must still have PostgreSQL chunks');
  // Existing storage semantics delete fenced bytes immediately, including old
  // retirement markers. Do not reintroduce the retired 24-hour grace contract.
  sql(`UPDATE platform_file_blob_chunk SET retire_after=clock_timestamp()+interval '24 hours' WHERE file_id=${sqlQuote(fileId)}`);
  const repeatPurge = await adminContext.request.delete(`/api/account/files/${fileId}/purge?version=${file.version}`, { headers: await tokenHeaders(adminContext) });
  assert.equal(repeatPurge.status(), 409, 'A repeated purge while DELETING must be rejected');
  assert.equal(Number(sql(`SELECT count(*) FROM platform_file_blob_chunk WHERE file_id=${sqlQuote(fileId)}`)), pendingChunks, 'Rejected repeated purge must leave queued chunks untouched');
  sql(`UPDATE platform_file SET updated_at=clock_timestamp()-interval '2 minutes' WHERE id=${sqlQuote(fileId)} AND state='DELETING'`);
  setStage('await scheduled physical cleanup');
  await waitUntilFileRemoved();
  assert.equal(Number(sql(`SELECT count(*) FROM platform_file WHERE id=${sqlQuote(fileId)}`)), 0);
  assert.equal(Number(sql(`SELECT count(*) FROM platform_file_blob_chunk WHERE file_id=${sqlQuote(fileId)}`)), 0);
  usage = await (await adminContext.request.get('/api/account/files/usage', { headers: await authorizationHeaders(adminContext) })).json();
  assert.equal(usage.chargedBytes, beforeUsage.chargedBytes);
  assert.equal(usage.fileCount, beforeUsage.fileCount);
  assert.equal(usage.pendingDeletingCount, beforeUsage.pendingDeletingCount);
  assert.equal(usage.pendingDeletingBytes, beforeUsage.pendingDeletingBytes);

  setStage('[actual HTTP] later competing save is preserved and stale restore conflicts');
  const ownAutomaticCommit = snapshot;
  const laterCompetitorPolicy = { mode: 'MANUAL', retentionDays: 29 };
  otherPolicyWriteIntents.push({ expectedVersion: ownAutomaticCommit.version + 1, proposed: { ...laterCompetitorPolicy } });
  const laterOtherSave = await savePolicy(ownAutomaticCommit, laterCompetitorPolicy, otherContext);
  assert.equal(laterOtherSave.version, ownAutomaticCommit.version + 1);
  await assertPolicyRevisionOwnedBy(laterOtherSave, otherWriter, laterCompetitorPolicy, otherContext);

  const laterGuard = await restoreOriginalPolicyIfOwned();
  assert.equal(laterGuard.status, 'not-owned');
  assert.deepEqual(await readPolicy(), laterOtherSave, 'A later competing HTTP save must remain untouched by final restoration.');
  await assert.rejects(savePolicy(ownAutomaticCommit, originalPolicy.data), error =>
    error.name === 'PolicyVersionConflictError' && error.status === 409);
  assert.deepEqual(await readPolicy(), laterOtherSave, 'A restore PUT with the stale version must fail closed without retrying.');
  await assertPolicyRevisionOwnedBy(await readPolicy(otherContext), otherWriter, laterCompetitorPolicy, otherContext);
  console.log('[file-retention] [actual HTTP] a later unique actor revision stayed unchanged; a stale-version restore PUT received 409 and was not retried.');

  await restoreControlledOtherWriteIfOwned();
  const restoredByOtherCleanup = await readPolicy();
  assert.ok(samePolicyData(restoredByOtherCleanup.data, originalPolicy.data));
  await assertPolicyRevisionOwnedBy(restoredByOtherCleanup, otherWriter, originalPolicy.data, otherContext);
  assert.deepEqual(errors, []);
  setStage('complete');
  console.log('PASS: real UI saves, policy ownership/recovery races, manual retention, automatic cleanup, version-fenced physical blob deletion, and quota restoration.');
} catch (error) {
  primaryError = error;
  console.error(`[file-retention] main flow failed at ${currentStage} (${error?.name ?? 'Error'}); recent API trace=${JSON.stringify(apiTrace.slice(-12))}`);
  throw error;
} finally {
  const finalizationErrors = [];
  setStage('finally restore and cleanup');
  try {
    await requireFinalPolicyRestore(await restoreOriginalPolicyIfOwned());
  } catch (error) {
    finalizationErrors.push(error);
    console.error(`[file-retention] policy restore failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
  } finally {
    try {
      await restoreControlledOtherWriteIfOwned();
    } catch (error) {
      finalizationErrors.push(error);
      console.error(`[file-retention] competing actor cleanup failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
    }
    try {
      await cleanupFile();
    } catch (error) {
      finalizationErrors.push(error);
      console.error(`[file-retention] fixture cleanup failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
    } finally {
      try {
        await context.close();
      } catch (error) {
        finalizationErrors.push(error);
        console.error(`[file-retention] context close failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
      }
      try {
        if (otherContext) await otherContext.close();
      } catch (error) {
        finalizationErrors.push(error);
        console.error(`[file-retention] other actor context close failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
      }
      try {
        await adminContext.close();
      } catch (error) {
        finalizationErrors.push(error);
        console.error(`[file-retention] admin context close failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
      }
      try {
        await cleanupTemporaryActors();
      } catch (error) {
        finalizationErrors.push(error);
        console.error(`[file-retention] temporary actor cleanup failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
      }
      try {
        await browser.close();
      } catch (error) {
        finalizationErrors.push(error);
        console.error(`[file-retention] browser close failed (${error?.name ?? 'Error'}); preservedMainFailure=${Boolean(primaryError)}`);
      }
    }
  }
  if (finalizationErrors.length && !primaryError) throw finalizationErrors[0];
}
