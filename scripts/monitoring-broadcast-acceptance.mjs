import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { chromium, expect } from '@playwright/test';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
import { loadQaRunContext } from './qa-run-guard.mjs';

const { config, baseURL, projectRoot, configPath, databaseName } = loadQaRunContext();
const messages = await loadLocaleMessages('zh-CN');
const evidenceDirectory = path.join(projectRoot, '.runtime', 'event-chain-qa');
mkdirSync(evidenceDirectory, { recursive: true });
const title = `QA event flow ${Date.now()}`;
const body = 'Monitoring event-flow QA broadcast.';
const escapedTitle = title.replaceAll("'", "''");
const authorizationRevisionHeader = 'X-Lumira-Authorization-Context-Revision';
let broadcastId;
let eventId;

function sql(statement) {
  return execFileSync(
    'python3',
    ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],
    {
      cwd: path.join(projectRoot, 'scripts'),
      env: { ...process.env, LUMIRA_SMOKE_CONFIG: configPath, BASE_URL: baseURL },
      input: statement,
      encoding: 'utf8',
      timeout: 15_000,
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  ).trim();
}

function assertConnectedTopology(flow) {
  assert.ok(flow, 'Monitoring flow must be present');
  assert.ok(['UNCONNECTED', 'IDLE', 'HEALTHY', 'PROCESSING', 'ATTENTION'].includes(flow.status), 'The new backend must provide a recognized flow status');
  assert.equal(flow.producerTypes, 2);
  assert.equal(flow.consumerTypes, 2);
  assert.equal(flow.connectedTypes, 2);
  assert.equal(flow.unmatchedConsumerTypes, 0);
  assert.deepEqual(flow.missingConsumers, []);
}

async function readMonitoringSnapshot(context) {
  const headers = await authorizationHeaders(context);
  const response = await context.request.get('/api/platform/monitoring/summary', { headers });
  assert.equal(response.status(), 200, await response.text());
  const snapshot = await response.json();
  assertConnectedTopology(snapshot.flow);
  return snapshot;
}

async function authorizationHeaders(context) {
  const response = await context.request.get('/api/auth/me');
  assert.equal(response.status(), 200, await response.text());
  const identity = await response.json();
  const authorizationContext = identity.authorizationContext;
  return authorizationContext?.rolePreviewSupported && authorizationContext.revision
    ? { [authorizationRevisionHeader]: authorizationContext.revision }
    : {};
}

async function findBroadcastEvent(context) {
  const headers = await authorizationHeaders(context);
  const response = await context.request.get(
    `/api/platform/event-outbox?page=1&pageSize=20&search=${encodeURIComponent(broadcastId)}`,
    { headers },
  );
  assert.equal(response.status(), 200, await response.text());
  const page = await response.json();
  return page.items.find(
    (entry) => entry.sourceType === 'notifications'
      && entry.eventType === 'notification.broadcast.requested'
      && entry.eventKey === `broadcast:${broadcastId}:0`,
  ) ?? null;
}

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const context = await browser.newContext({ baseURL, viewport: { width: 1213, height: 886 } });
try {
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  const login = await context.request.post('/api/auth/login', {
    form: { username: 'admin', password: config.LUMIRA_BOOTSTRAP_PASSWORD },
    headers: { [csrf.headerName]: csrf.token },
  });
  assert.equal(login.status(), 200, await login.text());

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));

  const initialMonitoringResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/platform/monitoring/summary'),
  );
  await page.goto('/dashboard/monitoring');
  const main = page.getByRole('main');
  await expect(main.getByRole('heading', { name: messages.monitoring, exact: true })).toBeVisible();
  const initialResponse = await initialMonitoringResponse;
  assert.equal(initialResponse.status(), 200);
  const initialSnapshot = await initialResponse.json();
  assertConnectedTopology(initialSnapshot.flow);
  assert.equal(initialSnapshot.flow.status, 'IDLE', 'A new QA database starts with no recent event activity');
  assert.equal(initialSnapshot.flow.createdLast5m, 0);
  assert.equal(initialSnapshot.flow.deliveredLast5m, 0);
  assert.deepEqual(initialSnapshot.flow.recentEvents, []);
  await expect(main.getByText(messages.eventFlowNoActivity, { exact: true }).first()).toBeVisible();
  await page.screenshot({ path: path.join(evidenceDirectory, 'monitoring-idle-desktop.png'), fullPage: true });

  await page.goto('/dashboard/notifications');
  await expect(page.getByRole('heading', { name: messages.notificationCenter, exact: true })).toBeVisible();
  await page.getByRole('button', { name: messages.publishNotification, exact: true }).click();
  const editor = page.locator('#notification-editor');
  await editor.getByLabel(messages.notificationTarget, { exact: true }).click();
  await page.getByText(messages.notificationSelectedUsers, { exact: true }).last().click();
  await editor.getByLabel(messages.notificationRecipients, { exact: true }).click();
  await page.locator('.ant-select-item-option').filter({ hasText: '(admin)' }).click();
  await editor.getByLabel(messages.notificationTitle, { exact: true }).fill(title);
  await editor.getByLabel(messages.notificationBody, { exact: true }).fill(body);
  const publishResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/platform/notifications')
      && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: messages.save, exact: true }).click();
  const published = await publishResponse;
  assert.equal(published.status(), 201, await published.text());
  const publishResult = await published.json();
  broadcastId = publishResult.broadcastId;
  assert.match(broadcastId, /^[a-f0-9-]{36}$/i);
  assert.equal(publishResult.recipientCount, 1);

  let broadcastEntry;
  await expect.poll(
    async () => {
      broadcastEntry = await findBroadcastEvent(context);
      eventId = broadcastEntry?.id;
      return broadcastEntry?.status ?? 'MISSING';
    },
    { timeout: 45_000, intervals: [250, 500, 1_000, 1_500] },
  ).toBe('DELIVERED');
  assert.ok(eventId, 'The broadcast must be recorded in the outbox with an event id');
  assert.equal(broadcastEntry.sourceType, 'notifications');
  assert.equal(broadcastEntry.eventType, 'notification.broadcast.requested');

  let deliveredSnapshot;
  await expect.poll(
    async () => {
      deliveredSnapshot = await readMonitoringSnapshot(context);
      return deliveredSnapshot.flow.status === 'HEALTHY'
        && deliveredSnapshot.flow.recentEvents.some(
          (event) => event.sourceType === 'notifications'
            && event.eventType === 'notification.broadcast.requested'
            && event.status === 'DELIVERED',
        );
    },
    { timeout: 15_000, intervals: [250, 500, 1_000] },
  ).toBe(true);
  assert.ok(deliveredSnapshot.flow.deliveredLast5m >= 1);

  const inboxHeaders = await authorizationHeaders(context);
  const inboxResponse = await context.request.get('/api/account/notifications?page=1&pageSize=50&unread=true', { headers: inboxHeaders });
  assert.equal(inboxResponse.status(), 200, await inboxResponse.text());
  const inbox = await inboxResponse.json();
  assert.ok(inbox.items.some((entry) => {
    try {
      return JSON.parse(entry.params).title === title && JSON.parse(entry.params).body === body;
    } catch {
      return false;
    }
  }), 'The admin inbox API must return the broadcast written by the event consumer');
  assert.equal(
    sql(`SELECT count(*) FROM platform_notification WHERE event_id='${eventId}' AND recipient_id=(SELECT id FROM platform_user WHERE username='admin') AND params->>'title'='${escapedTitle}'`),
    '1',
    'The notification row must reference the delivered event id',
  );
  assert.equal(
    sql(`SELECT count(*) FROM platform_event_outbox WHERE id='${eventId}' AND source_type='notifications' AND event_type='notification.broadcast.requested' AND status='DELIVERED'`),
    '1',
  );

  const healthyMonitoringResponse = page.waitForResponse(
    (response) => response.url().endsWith('/api/platform/monitoring/summary'),
  );
  await page.goto('/dashboard/monitoring');
  const healthyResponse = await healthyMonitoringResponse;
  assert.equal(healthyResponse.status(), 200);
  const healthySnapshot = await healthyResponse.json();
  assertConnectedTopology(healthySnapshot.flow);
  assert.equal(healthySnapshot.flow.status, 'HEALTHY');
  assert.ok(healthySnapshot.flow.recentEvents.some(
    (event) => event.sourceType === 'notifications'
      && event.eventType === 'notification.broadcast.requested'
      && event.status === 'DELIVERED',
  ));
  await expect(main.getByText(messages.eventFlowHealthy, { exact: true }).first()).toBeVisible();
  await page.screenshot({ path: path.join(evidenceDirectory, 'monitoring-healthy-desktop.png'), fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false);
  await expect(main.getByText(messages.eventFlowHealthy, { exact: true }).first()).toBeVisible();
  await page.screenshot({ path: path.join(evidenceDirectory, 'monitoring-healthy-mobile.png'), fullPage: true });
  assert.deepEqual(errors, []);

  writeFileSync(
    path.join(evidenceDirectory, 'monitoring-broadcast-acceptance.json'),
    `${JSON.stringify({
      baseURL,
      databaseName,
      initialStatus: initialSnapshot.flow.status,
      topology: {
        producerTypes: healthySnapshot.flow.producerTypes,
        consumerTypes: healthySnapshot.flow.consumerTypes,
        connectedTypes: healthySnapshot.flow.connectedTypes,
      },
      broadcastId,
      eventId,
      eventStatus: broadcastEntry.status,
      inboxLinkedToEvent: true,
      finalStatus: healthySnapshot.flow.status,
      desktopNoOverflow: true,
      mobileNoOverflow: true,
    }, null, 2)}\n`,
    'utf8',
  );
  console.log(`PASS: IDLE -> real broadcast ${eventId} DELIVERED -> inbox linked by event_id -> HEALTHY; evidence in .runtime/event-chain-qa`);
} finally {
  if (broadcastId) {
    const eventPredicate = eventId
      ? `id='${eventId}'`
      : `payload->>'broadcastId'='${broadcastId}'`;
    try {
      sql(`DELETE FROM platform_notification WHERE event_id IN (SELECT id FROM platform_event_outbox WHERE payload->>'broadcastId'='${broadcastId}') OR params->>'title'='${escapedTitle}'; DELETE FROM platform_audit WHERE action='notification.broadcast' AND target_id='${broadcastId}'; DELETE FROM platform_event_outbox WHERE ${eventPredicate}`);
    } catch (error) {
      console.error('QA fixture cleanup failed; inspect the guarded QA database before retrying.', error.message);
    }
  }
  await context.close();
  await browser.close();
}
