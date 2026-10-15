import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { lstatSync, mkdirSync, readFileSync } from 'node:fs';
import { writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { chromium, expect } from '@playwright/test';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
import { loadQaRunContext } from './qa-run-guard.mjs';

const { baseURL, projectRoot, configPath, databaseName } = loadQaRunContext();
const messages = await loadLocaleMessages('zh-CN');
const evidenceDirectory = path.join(projectRoot, '.runtime', 'event-trace-qa', 'evidence');
const storageStatePath = process.env.LUMIRA_EVENT_TRACE_STORAGE_STATE
  ? path.resolve(projectRoot, process.env.LUMIRA_EVENT_TRACE_STORAGE_STATE)
  : '/root/.lumira/event-trace-qa/storage-state.json';
const title = `QA event trace ${Date.now()}`;
const body = `Private acceptance body ${Date.now()}-${Math.random().toString(16).slice(2)}`;
const escapedTitle = title.replaceAll("'", "''");
const escapedBody = body.replaceAll("'", "''");
const authorizationRevisionHeader = 'X-Lumira-Authorization-Context-Revision';
const legacyEventId = randomUUID();
const unrecordedEventId = randomUUID();
const emptyAuditId = randomUUID();
const emptyBusinessId = randomUUID();
const emptyOperationId = randomUUID();
const retryBroadcastId = randomUUID();
const retryOperationId = retryBroadcastId;
const retryAuditId = randomUUID();
const retryEventId = randomUUID();
const errors = [];
let published;

mkdirSync(evidenceDirectory, { recursive: true });

function sql(statement) {
  return execFileSync(
    'python3',
    ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],
    {
      cwd: path.join(projectRoot, 'scripts'),
      env: { ...process.env, LUMIRA_SMOKE_CONFIG: configPath, BASE_URL: baseURL },
      input: statement,
      encoding: 'utf8',
      timeout: 20_000,
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  ).trim();
}

function privateStorageState() {
  try {
    const stat = lstatSync(storageStatePath);
    assert.ok(stat.isFile() && !stat.isSymbolicLink(), 'Storage state must be a regular file.');
    assert.equal(stat.mode & 0o077, 0, 'Storage state must be owner-only.');
    JSON.parse(readFileSync(storageStatePath, 'utf8'));
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

function fixtureSql() {
  const auditTarget = emptyBusinessId;
  return `
INSERT INTO platform_audit(id,actor,action,target_id)
VALUES('${emptyAuditId}','qa-event-trace','qa.event-trace.empty','${auditTarget}');
INSERT INTO platform_event_operation(
  operation_id,operation_type,business_type,business_id,audit_operation_id,outcome_code,
  expected_event_count,expected_inbox_count,inbox_created,inbox_skipped,skipped_by_reason,
  completed_event_count,known_event_count,result_known,history_status,completed_at)
VALUES('${emptyOperationId}','qa.event.trace.empty','QA','${emptyBusinessId}','${emptyAuditId}',
  'SUPPRESSED_GLOBAL_MANAGER',0,0,0,0,'{}'::jsonb,0,0,true,'NO_EVENTS',now());
INSERT INTO platform_event_outbox(
  id,source_type,event_type,event_key,payload,status,attempts,delivered_at)
VALUES('${legacyEventId}','qa.trace','qa.trace.legacy','legacy:${legacyEventId}',
  '{"body":"legacy-trace-private-fixture"}'::jsonb,'DELIVERED',3,now());
INSERT INTO platform_event_outbox(
  id,source_type,event_type,event_key,payload,status,attempts,delivered_at)
VALUES('${unrecordedEventId}','qa.trace','qa.trace.unrecorded','unrecorded:${unrecordedEventId}',
  '{"body":"zero-attempt-unrecorded-fixture"}'::jsonb,'DELIVERED',0,now());
`;
}

function retryFixtureSql() {
  return `
INSERT INTO platform_audit(id,actor,action,target_id)
VALUES('${retryAuditId}','admin','notification.broadcast','${retryBroadcastId}');
INSERT INTO platform_event_operation(
  operation_id,operation_type,business_type,business_id,audit_operation_id,outcome_code,
  expected_event_count,expected_inbox_count,inbox_created,inbox_skipped,skipped_by_reason,
  completed_event_count,known_event_count,result_known)
VALUES('${retryOperationId}','notification.broadcast','BROADCAST','${retryBroadcastId}','${retryAuditId}',
  'EVENTS_PENDING',1,1,0,0,'{}'::jsonb,0,0,false);
INSERT INTO platform_event_outbox(id,operation_id,source_type,event_type,event_key,payload,status,attempts,generation,available_at)
VALUES('${retryEventId}','${retryOperationId}','notifications','notification.broadcast.requested',
  'broadcast:${retryBroadcastId}:0',
  '{"broadcastId":"${retryBroadcastId}","chunk":0,"recipients":[],"title":"invalid QA fixture","body":"invalid QA fixture"}'::jsonb,
  'PENDING',0,0,now());
`;
}

async function waitForFailureAttempts(context, eventId, headers) {
  let trace;
  for (let expectedAttempt = 1; expectedAttempt <= 8; expectedAttempt += 1) {
    await expect.poll(
      async () => {
        trace = await readJson(
          context,
          `/api/platform/event-outbox/${encodeURIComponent(eventId)}/trace?page=1&pageSize=100`,
          headers,
        );
        return trace.event.attempts >= expectedAttempt
          && trace.history.items.some(entry => entry.phase === 'FAILED' && entry.generation === 0 && entry.attempt === expectedAttempt);
      },
      { timeout: 30_000, intervals: [200, 500, 1_000] },
    ).toBe(true);
    if (expectedAttempt < 8) {
      assert.equal(trace.event.status, 'PENDING', `Attempt ${expectedAttempt} must return the event to retryable state.`);
      assert.equal(
        sql(`UPDATE platform_event_outbox SET available_at=now() WHERE id='${eventId}' AND status='PENDING' AND attempts=${expectedAttempt};`),
        'UPDATE 1',
        `The isolated QA retry should make attempt ${expectedAttempt + 1} immediately available.`,
      );
    }
  }
  await expect.poll(
    async () => {
      trace = await readJson(
        context,
        `/api/platform/event-outbox/${encodeURIComponent(eventId)}/trace?page=1&pageSize=100`,
        headers,
      );
      return trace.event.status === 'DEAD_LETTER';
    },
    { timeout: 15_000, intervals: [200, 500, 1_000] },
  ).toBe(true);
  return trace;
}

async function authorizationHeaders(context) {
  const response = await context.request.get('/api/auth/me');
  assert.equal(response.status(), 200, await response.text());
  const identity = await response.json();
  const revision = identity.authorizationContext?.revision;
  return revision ? { [authorizationRevisionHeader]: revision } : {};
}

async function readJson(context, endpoint, headers = {}) {
  const response = await context.request.get(endpoint, { headers });
  assert.equal(response.status(), 200, `${endpoint} returned HTTP ${response.status()}: ${await response.text()}`);
  return response.json();
}

async function waitForDrawerSettled(page) {
  await expect(page.getByTestId('event-trace-drawer')).toBeVisible();
  const panel = page.locator('.ant-drawer-content-wrapper').last();
  await expect.poll(
    () => panel.evaluate(element => {
      const transform = getComputedStyle(element).transform;
      if (transform === 'none') return true;
      const matrix = new DOMMatrixReadOnly(transform);
      return Math.abs(matrix.m41) < 0.5 && Math.abs(matrix.m42) < 0.5;
    }),
    { timeout: 5_000, intervals: [50, 100, 200] },
  ).toBe(true);
}

async function captureDrawer(page, filename, fullPage = true) {
  await waitForDrawerSettled(page);
  await page.screenshot({
    path: path.join(evidenceDirectory, filename),
    fullPage,
    animations: 'disabled',
  });
}

async function waitForDelivery(context, auditOperationId, headers) {
  let snapshot;
  await expect.poll(
    async () => {
      snapshot = await readJson(
        context,
        `/api/platform/event-outbox/operations/${encodeURIComponent(auditOperationId)}/trace?page=1&pageSize=20`,
        headers,
      );
      return snapshot.items.length > 0
        && snapshot.items.every(({ event }) => event.status === 'DELIVERED')
        && snapshot.operation.result.known;
    },
    { timeout: 60_000, intervals: [250, 500, 1_000, 2_000] },
  ).toBe(true);
  return snapshot;
}

async function ensureUiSession(context, page) {
  await page.goto('/dashboard/notifications');
  const me = await context.request.get('/api/auth/me');
  assert.equal(me.status(), 200, 'Run the private QA bootstrap helper first; UI acceptance never fabricates a login session.');
  const identity = await me.json();
  assert.equal(identity.username, 'admin', 'The browser state must belong to the dedicated QA admin.');
}

const hasSavedState = privateStorageState();
assert.ok(
  hasSavedState,
  'Run .runtime/event-trace-qa/prepare-admin-session.mjs first to complete the normal bootstrap/password-change flow and create a private session state.',
);
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({
  baseURL,
  ...(hasSavedState ? { storageState: storageStatePath } : {}),
  reducedMotion: 'reduce',
  viewport: { width: 1280, height: 900 },
});
const page = await context.newPage();
page.on('pageerror', error => errors.push(error.message));

try {
  await ensureUiSession(context, page);
  const headers = await authorizationHeaders(context);
  await expect(page.getByRole('heading', { name: messages.notificationCenter, exact: true })).toBeVisible();

  await page.getByRole('button', { name: messages.publishNotification, exact: true }).click();
  const editor = page.locator('#notification-editor');
  await expect(editor.getByText(messages.notificationAllUsers, { exact: true })).toBeVisible();
  await editor.getByLabel(messages.notificationTitle, { exact: true }).fill(title);
  await editor.getByLabel(messages.notificationBody, { exact: true }).fill(body);
  const publishResponse = page.waitForResponse(
    response => response.url().endsWith('/api/platform/notifications')
      && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: messages.save, exact: true }).click();
  const posted = await publishResponse;
  assert.equal(posted.status(), 201, await posted.text());
  published = await posted.json();
  assert.match(published.broadcastId, /^[a-f0-9-]{36}$/i);
  assert.match(published.operationId, /^[a-f0-9-]{36}$/i);
  assert.match(published.auditOperationId, /^[a-f0-9-]{36}$/i);
  assert.ok(Array.isArray(published.eventIds) && published.eventIds.length > 0);

  const operationTrace = await waitForDelivery(context, published.auditOperationId, headers);
  assert.equal(operationTrace.operation.auditOperationId, published.auditOperationId);
  assert.equal(operationTrace.operation.operationId, published.operationId);
  assert.ok(operationTrace.items.some(({ event }) => published.eventIds.includes(event.id)));
  assert.ok(operationTrace.operation.result.inboxCreated > 0, 'The operation must report a real inbox insert.');
  assert.equal(operationTrace.operation.result.known, true);
  const deliveredEvent = operationTrace.items.find(({ event }) => published.eventIds.includes(event.id)).event;
  const eventTrace = await readJson(
    context,
    `/api/platform/event-outbox/${encodeURIComponent(deliveredEvent.id)}/trace?page=1&pageSize=20`,
    headers,
  );
  assert.equal(eventTrace.event.id, deliveredEvent.id);
  assert.equal(eventTrace.operation.auditOperationId, published.auditOperationId);
  assert.equal(eventTrace.event.status, 'DELIVERED');
  assert.equal(eventTrace.event.result.known, true);
  assert.ok(eventTrace.history.items.some(entry => entry.phase === 'SUCCEEDED'));
  assert.deepEqual(
    eventTrace.history.items.map(entry => entry.sequence),
    [...eventTrace.history.items.map(entry => entry.sequence)].sort((a, b) => a - b),
  );
  assert.equal(JSON.stringify(operationTrace).includes(body), false);
  assert.equal(JSON.stringify(eventTrace).includes(body), false);
  assert.equal(Object.hasOwn(eventTrace.event, 'payload'), false);
  assert.equal(Object.hasOwn(eventTrace.event, 'leaseToken'), false);

  const invalidEventPage = await context.request.get(
    `/api/platform/event-outbox/${encodeURIComponent(deliveredEvent.id)}/trace?page=0&pageSize=20`,
    { headers },
  );
  assert.equal(invalidEventPage.status(), 400, 'Event trace rejects page numbers below 1.');
  assert.equal((await invalidEventPage.text()).includes(body), false);
  const invalidOperationPageSize = await context.request.get(
    `/api/platform/event-outbox/operations/${encodeURIComponent(published.auditOperationId)}/trace?page=1&pageSize=101`,
    { headers },
  );
  assert.equal(invalidOperationPageSize.status(), 400, 'Operation trace rejects page sizes above 100.');
  assert.equal((await invalidOperationPageSize.text()).includes(body), false);

  const inbox = await readJson(context, '/api/account/notifications?page=1&pageSize=100&unread=true', headers);
  assert.ok(inbox.items.some(item => {
    try {
      const params = JSON.parse(item.params);
      return params.title === title && params.body === body;
    } catch {
      return false;
    }
  }), 'The inbox record must contain the published notice and reference the delivered event id.');
  const linkedInboxCount = Number(sql(`SELECT count(*) FROM platform_notification WHERE event_id='${deliveredEvent.id}' AND params->>'title'='${escapedTitle}' AND params->>'body'='${escapedBody}'`));
  assert.equal(linkedInboxCount, eventTrace.event.result.inboxCreated, 'The inbox row count must match the event result and link to its event_id.');

  const anonymous = await browser.newContext({ baseURL });
  try {
    const denied = await anonymous.request.get(
      `/api/platform/event-outbox/${encodeURIComponent(deliveredEvent.id)}/trace?page=1&pageSize=20`,
    );
    assert.ok([401, 403].includes(denied.status()), `Anonymous trace access should be denied, got ${denied.status()}.`);
    assert.equal((await denied.text()).includes(body), false);
  } finally {
    await anonymous.close();
  }

  await expect(page.getByTestId('event-trace-notification')).toBeVisible();
  await page.getByTestId('event-trace-notification').click();
  const drawer = page.getByTestId('event-trace-drawer');
  await expect(drawer).toBeVisible();
  await expect(page.getByTestId('event-trace-operation-events')).toBeVisible();
  const operationEventTrigger = page.getByTestId(`event-trace-open-event-${deliveredEvent.id}`);
  await expect(operationEventTrigger).toBeVisible();
  await operationEventTrigger.click();
  await expect(page.getByTestId('event-trace-history')).toBeVisible();
  await expect(page.getByTestId('event-trace-result')).toBeVisible();
  assert.equal((await drawer.innerText()).includes(body), false);
  await captureDrawer(page, 'event-trace-notification-desktop.png');
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(drawer).toBeVisible();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  await captureDrawer(page, 'event-trace-notification-mobile.png');

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/dashboard/audit');
  await page.getByTestId(`event-trace-audit-${published.auditOperationId}`).click();
  await expect(page.getByTestId('event-trace-operation-events')).toBeVisible();
  await expect(page.getByTestId(`event-trace-event-${deliveredEvent.id}`)).toBeVisible();
  await captureDrawer(page, 'event-trace-audit-desktop.png');

  await page.goto('/dashboard/event-outbox');
  await expect(page.getByTestId(`event-trace-event-${deliveredEvent.id}`)).toBeVisible();
  await page.getByTestId(`event-trace-event-${deliveredEvent.id}`).click();
  await expect(page.getByTestId('event-trace-history')).toBeVisible();
  await page.goto('/dashboard/monitoring');
  const monitoringTrigger = page.getByTestId(`event-trace-open-event-${deliveredEvent.id}`);
  await expect(monitoringTrigger).toBeVisible({ timeout: 30_000 });
  await monitoringTrigger.click();
  await expect(page.getByTestId('event-trace-result')).toBeVisible();

  sql(fixtureSql());
  await page.goto('/dashboard/audit');
  await page.getByTestId(`event-trace-audit-${emptyAuditId}`).click();
  const emptyOperationEvents = page.getByTestId('event-trace-operation-events');
  await expect(emptyOperationEvents).toBeVisible();
  await expect(emptyOperationEvents.locator('[data-testid^="event-trace-open-event-"]')).toHaveCount(0);
  await expect(page.getByText(messages.eventTraceSuppressedGlobalManager, { exact: true }).first()).toBeVisible();
  const emptyOperationTrace = await readJson(
    context,
    `/api/platform/event-outbox/operations/${encodeURIComponent(emptyAuditId)}/trace?page=1&pageSize=20`,
    headers,
  );
  assert.equal(emptyOperationTrace.items.length, 0);
  assert.equal(emptyOperationTrace.operation.outcomeCode, 'SUPPRESSED_GLOBAL_MANAGER');
  await captureDrawer(page, 'event-trace-empty-operation.png');

  await page.goto('/dashboard/event-outbox');
  await expect(page.getByTestId(`event-trace-event-${legacyEventId}`)).toBeVisible();
  await page.getByTestId(`event-trace-event-${legacyEventId}`).click();
  await expect(page.getByTestId('event-trace-drawer')).toContainText(messages.eventTraceHistoryPartial);
  const legacyApi = await readJson(
    context,
    `/api/platform/event-outbox/${encodeURIComponent(legacyEventId)}/trace?page=1&pageSize=20`,
    headers,
  );
  assert.equal(legacyApi.historyStatus, 'PARTIAL');
  assert.equal(legacyApi.history.total, 0);
  assert.equal(legacyApi.event.result.known, false);
  await captureDrawer(page, 'event-trace-partial-history.png');

  await page.goto('/dashboard/event-outbox');
  await expect(page.getByTestId(`event-trace-event-${unrecordedEventId}`)).toBeVisible();
  await page.getByTestId(`event-trace-event-${unrecordedEventId}`).click();
  await expect(page.getByTestId('event-trace-history')).toContainText(messages.eventTraceHistoryNotRecorded);
  const unrecordedApi = await readJson(
    context,
    `/api/platform/event-outbox/${encodeURIComponent(unrecordedEventId)}/trace?page=1&pageSize=20`,
    headers,
  );
  assert.equal(unrecordedApi.historyStatus, 'NOT_RECORDED');
  assert.equal(unrecordedApi.history.total, 0);
  assert.equal(unrecordedApi.event.result.known, false);
  await captureDrawer(page, 'event-trace-unrecorded-history.png');

  sql(retryFixtureSql());
  const failedTrace = await waitForFailureAttempts(context, retryEventId, headers);
  assert.equal(failedTrace.event.status, 'DEAD_LETTER');
  assert.equal(failedTrace.event.attempts, 8);
  assert.equal(failedTrace.event.lastErrorCode, 'CONSUMER_FAILED');
  assert.equal(failedTrace.history.items.filter(entry => entry.phase === 'CLAIMED').length, 8);
  assert.equal(failedTrace.history.items.filter(entry => entry.phase === 'FAILED').length, 8);
  assert.deepEqual(
    failedTrace.history.items.map(entry => entry.sequence),
    [...failedTrace.history.items.map(entry => entry.sequence)].sort((a, b) => a - b),
  );

  const adminRecipientId = sql("SELECT id::text FROM platform_user WHERE username='admin' AND enabled=true AND deleted_at IS NULL LIMIT 1;");
  assert.match(adminRecipientId, /^[a-f0-9-]{36}$/i, 'The repaired notice must target the authenticated QA admin.');
  const replayTitle = `QA replay repaired ${Date.now()}`;
  const replayBody = `Private replay body ${randomUUID()}`;
  const repairedPayload = JSON.stringify({
    broadcastId: retryBroadcastId,
    chunk: 0,
    recipients: [adminRecipientId],
    title: replayTitle,
    body: replayBody,
    href: null,
    expiresAt: null,
  }).replaceAll("'", "''");
  assert.equal(
    sql(`UPDATE platform_event_outbox SET payload='${repairedPayload}'::jsonb,updated_at=now() WHERE id='${retryEventId}' AND status='DEAD_LETTER';`),
    'UPDATE 1',
    'Only the dead-letter QA event payload is repaired before manual approval.',
  );

  await page.goto('/dashboard/event-outbox');
  const deadLetterRow = page.getByRole('row').filter({ hasText: retryBroadcastId });
  await expect(deadLetterRow).toBeVisible();
  await expect(deadLetterRow).toContainText(messages.eventDeadLetter);
  await deadLetterRow.getByRole('button', { name: messages.eventReplay, exact: true }).click();
  const replayDialog = page.getByRole('dialog', { name: messages.eventReplay });
  await expect(replayDialog).toBeVisible();
  const replayReason = `QA acceptance replay after payload repair ${randomUUID()}`;
  const replayReasonInput = replayDialog.getByRole('textbox', { name: messages.eventReplayReason });
  const approveReplay = replayDialog.getByRole('button', { name: messages.eventReplay, exact: true });
  await expect(approveReplay).toBeDisabled();
  await replayReasonInput.fill(replayReason);
  await expect(approveReplay).toBeEnabled();
  const replayResponse = page.waitForResponse(response =>
    response.url().endsWith(`/api/platform/event-outbox/${retryEventId}/replay`)
      && response.request().method() === 'POST',
  );
  await approveReplay.click();
  const replayed = await replayResponse;
  assert.equal(replayed.status(), 200, await replayed.text());

  const replayOperationTrace = await waitForDelivery(context, retryAuditId, headers);
  assert.equal(replayOperationTrace.operation.operationType, 'notification.broadcast');
  assert.equal(replayOperationTrace.operation.result.known, true);
  assert.equal(replayOperationTrace.operation.result.expectedInboxCount, 1);
  assert.equal(replayOperationTrace.operation.result.inboxCreated, 1);
  assert.equal(replayOperationTrace.operation.result.inboxSkipped, 0);
  const replayedEvent = replayOperationTrace.items.find(({ event }) => event.id === retryEventId)?.event;
  assert.ok(replayedEvent, 'The exact broadcast operation must retain its replayed event association.');
  assert.equal(replayedEvent.status, 'DELIVERED');
  assert.equal(replayedEvent.generation, 1);
  assert.equal(replayedEvent.result.known, true);

  const replayedEventTrace = await readJson(
    context,
    `/api/platform/event-outbox/${encodeURIComponent(retryEventId)}/trace?page=1&pageSize=100`,
    headers,
  );
  assert.equal(replayedEventTrace.historyStatus, 'AVAILABLE');
  assert.equal(replayedEventTrace.event.attempts, 1);
  assert.equal(replayedEventTrace.event.result.inboxCreated, 1);
  assert.ok(replayedEventTrace.history.items.filter(entry => entry.phase === 'FAILED').length >= 8);
  assert.ok(replayedEventTrace.history.items.some(entry => entry.phase === 'REPLAY_APPROVED' && entry.actor === 'admin' && entry.reason === replayReason));
  assert.ok(replayedEventTrace.history.items.some(entry => entry.phase === 'SUCCEEDED' && entry.generation === 1));
  assert.equal(JSON.stringify(replayedEventTrace).includes(replayBody), false);
  const replayApproval = replayedEventTrace.history.items.find(entry => entry.phase === 'REPLAY_APPROVED');
  assert.match(replayApproval.auditOperationId, /^[a-f0-9-]{36}$/i);
  const replayAuditTrace = await readJson(
    context,
    `/api/platform/event-outbox/operations/${encodeURIComponent(replayApproval.auditOperationId)}/trace?page=1&pageSize=20`,
    headers,
  );
  assert.equal(replayAuditTrace.operation.operationType, 'event.replay');
  assert.equal(replayAuditTrace.operation.businessId, retryEventId);
  assert.equal(replayAuditTrace.operation.completedEventCount, 1);
  assert.equal(replayAuditTrace.operation.result.known, true);
  assert.equal(replayAuditTrace.operation.result.inboxCreated, 1);

  assert.equal(
    sql(`SELECT count(*) FROM platform_notification WHERE event_id='${retryEventId}' AND recipient_id='${adminRecipientId}' AND params->>'title'='${replayTitle.replaceAll("'", "''")}' AND params->>'body'='${replayBody.replaceAll("'", "''")}'`),
    '1',
    'Manual replay must create exactly one inbox row linked to the repaired event.',
  );
  await page.goto('/dashboard/event-outbox');
  const deliveredReplayRow = page.getByRole('row').filter({ hasText: retryBroadcastId });
  await expect(deliveredReplayRow).toContainText(messages.eventDelivered);
  await deliveredReplayRow.getByTestId(`event-trace-event-${retryEventId}`).click();
  const replayDrawer = page.getByTestId('event-trace-drawer');
  const replayHistory = page.getByTestId('event-trace-history');
  await expect(replayDrawer).toContainText(replayReason);
  await expect(replayHistory).toContainText(messages.eventTraceReplayApproved);
  await expect(replayHistory).toContainText(messages.eventTraceSucceeded);
  await expect(page.getByTestId('event-trace-result')).toContainText('1');
  await captureDrawer(page, 'event-trace-retry-replay-desktop-top.png');
  await page.locator('.ant-drawer-body').last().evaluate(element => { element.scrollTop = element.scrollHeight; });
  await captureDrawer(page, 'event-trace-retry-replay-desktop-history.png', false);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('.ant-drawer-body').last().evaluate(element => { element.scrollTop = element.scrollHeight; });
  await expect(replayHistory).toContainText(replayReason);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  await captureDrawer(page, 'event-trace-retry-replay-mobile-history.png', false);

  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  assert.deepEqual(errors, []);
  const report = {
    databaseName,
    broadcastId: published.broadcastId,
    operationId: published.operationId,
    auditOperationId: published.auditOperationId,
    eventId: deliveredEvent.id,
    status: deliveredEvent.status,
    inboxCreated: operationTrace.operation.result.inboxCreated,
    inboxSkipped: operationTrace.operation.result.inboxSkipped,
    historyEntries: eventTrace.history.total,
    invalidPagination: true,
    inboxLinkedToEvent: true,
    notificationsEntry: true,
    auditEntry: true,
    outboxEntry: true,
    monitoringEntry: true,
    emptyOperation: true,
    legacyHistoryPartial: true,
    zeroAttemptHistoryNotRecorded: true,
    deadLetterAfterEightAttempts: true,
    replayReasonRequired: true,
    repairedPayloadReplayDelivered: true,
    replayInboxCreated: replayAuditTrace.operation.result.inboxCreated,
    replayHistoryEntries: replayedEventTrace.history.total,
    anonymousDenied: true,
    desktopAndMobileScreenshots: true,
    mobileNoOverflow: true,
  };
  const reportPath = path.join(evidenceDirectory, 'event-trace-acceptance.json');
  await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
  console.log(`PASS: real notification -> exact audit operation -> delivered event history/result -> inbox event_id; audit/outbox/monitoring, invalid pagination, empty operation, PARTIAL and NOT_RECORDED history, and anonymous denial checked. Evidence: ${path.relative(projectRoot, evidenceDirectory)}`);
} finally {
  await context.close();
  await browser.close();
}
