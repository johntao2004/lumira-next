import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { readFileSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { chromium } from '@playwright/test';

// This runner is deliberately tied to ordinary_user_closeout's isolated QA copy.
// It authenticates through the visible login page; the private handoff file is
// read once into memory and is never printed, copied, or included in a trace.
const metadataPath = process.env.LUMIRA_SETTINGS_CLOSEOUT_META
  ?? '/tmp/lumira-ordinary-user-closeout-20260930-mhb5vdfj/.runtime/ordinary-environment.json';
const privatePath = process.env.LUMIRA_SETTINGS_CLOSEOUT_PRIVATE
  ?? '/tmp/lumira-ordinary-user-closeout-20260930-mhb5vdfj/.runtime/ordinary-qa-private.json';
const metadata = JSON.parse(readFileSync(metadataPath, 'utf8'));
const privateMode = statSync(privatePath).mode & 0o777;
assert.equal(privateMode, 0o600, 'Private QA handoff must be mode 0600');
const privateData = JSON.parse(readFileSync(privatePath, 'utf8'));
const phase = process.argv[2] ?? 'all';

function firstRecord(...values) {
  return values.find(value => value && typeof value === 'object' && !Array.isArray(value));
}
function firstString(...values) {
  return values.find(value => typeof value === 'string' && value.length > 0);
}
const credentials = firstRecord(privateData.credentials, privateData.accounts) ?? privateData;
const adminRecord = firstRecord(credentials.admin, privateData.admin, privateData.bootstrapAdmin) ?? {};
const ordinaryRecord = firstRecord(credentials.ordinary, credentials.normalUser, privateData.ordinaryUser, privateData.user) ?? {};
const admin = {
  username: firstString(adminRecord.username, privateData.adminUsername, 'admin'),
  password: firstString(adminRecord.password, adminRecord.bootstrapPassword, privateData.adminPassword, privateData.bootstrapPassword),
};
const ordinary = {
  username: firstString(ordinaryRecord.username, privateData.ordinaryUsername, privateData.normalUsername),
  password: firstString(ordinaryRecord.password, privateData.ordinaryPassword, privateData.normalPassword),
};
const fixtureRoot = firstRecord(privateData.fixtures, privateData.profileFixture, privateData.profileSelect) ?? privateData;
const profileFixture = firstRecord(fixtureRoot.profileSelect, fixtureRoot.dictionarySelect, privateData.profileSelect) ?? fixtureRoot;
const fixture = {
  dictionaryCode: firstString(profileFixture.dictionaryCode, privateData.dictionaryCode, profileFixture.code),
  dictionaryName: firstString(profileFixture.dictionaryName, privateData.dictionaryName, profileFixture.name),
  dictionaryId: firstString(profileFixture.dictionaryId, privateData.dictionaryId),
  itemId: firstString(profileFixture.itemId, profileFixture.optionId, privateData.dictionaryItemId, privateData.itemId),
  itemLabel: firstString(profileFixture.itemLabel, profileFixture.optionLabel, privateData.dictionaryItemLabel),
  fieldKey: firstString(profileFixture.fieldKey, profileFixture.key, privateData.selectFieldKey),
  fieldLabel: firstString(profileFixture.fieldLabel, profileFixture.label, privateData.selectFieldLabel),
};
assert.ok(admin.password, 'Private handoff is missing the temporary admin password');
if (phase === 'all' || phase === 'readonly-dictionary') {
  assert.ok(ordinary.username && ordinary.password, 'Private handoff is missing the temporary ordinary-user login');
  assert.ok(fixture.dictionaryCode && fixture.itemLabel && fixture.fieldKey, 'Private handoff is missing the completed SELECT fixture markers');
}

const frontendPort = Number(metadata.frontendPort);
const backendPort = Number(metadata.backendPort);
assert.equal(frontendPort, 8024, 'Refusing to use a non-QA frontend');
assert.equal(backendPort, 8094, 'Refusing to use a non-QA backend');
const baseURL = `http://127.0.0.1:${frontendPort}`;
const readyURL = `http://127.0.0.1:${backendPort}/actuator/health/readiness`;
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
const adminContext = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
const adminPage = await adminContext.newPage();
let readerContext;
let readerPage;
let readerUsername;
let readerPassword;
let readerRoleCode;
let readerRoleName;
let readerRoleId;
let readerId;
let readerCleanupDone = false;
let smsReceiver;
let smsReceiverPort;
let smsSnapshot;
let smsChanged = false;
let agreementsSnapshot;
let agreementsChanged = false;
let watermarkSnapshot;
let watermarkChanged = false;
let maintenanceSnapshot;
let maintenanceChanged = false;
const results = [];

function note(result) { results.push(result); }
function notSecretText(value) { return String(value ?? ''); }
function localized(names) {
  const escaped = names.map(name => name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  return new RegExp(escaped.join('|'), 'i');
}
const labels = {
  agreementUser: ['用户协议', '使用者協議', 'User Agreement'],
  agreementPrivacy: ['隐私协议', '隱私協議', 'Privacy policy', 'Privacy Policy'],
  readOnly: ['页面访问', '頁面存取', 'Page access'],
  replaceKey: ['更换密钥', '更換金鑰', 'Replace key'],
  clearKey: ['清除密钥', '清除金鑰', 'Clear key'],
  createRole: ['新增角色', '创建角色', 'Create role'],
  createUser: ['新增用户', '新增使用者', 'Create user'],
  assignRoles: ['分配角色', '指派角色', 'Assign roles'],
  settingHistory: ['配置历史', '設定歷史', 'Configuration history'],
  details: ['详情', '詳情', 'Details'],
  edit: ['编辑', 'Edit'],
  delete: ['删除', 'Delete'],
  confirm: ['确定', '確認', 'OK', 'Confirm'],
};

async function waitHealthy() {
  const response = await fetch(readyURL, { signal: AbortSignal.timeout(5000) });
  assert.equal(response.status, 200, 'Isolated backend readiness check failed');
}

async function loginUI(context, username, password, expectSuccess = true) {
  const page = await context.newPage();
  await page.goto('/user/login');
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill(password);
  const loginResponse = page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.request().method() === 'POST');
  await page.locator('form button[type="submit"]').click();
  const response = await loginResponse;
  if (expectSuccess) {
    assert.equal(response.status(), 200, 'Visible login did not succeed');
    await page.waitForURL(/\/dashboard\//, { timeout: 15000 });
  } else {
    assert.equal(response.status(), 503, 'Maintenance must reject this ordinary account with 503');
    await page.getByText(/维护中|維護中|Maintenance is active/i).waitFor({ timeout: 10000 });
  }
  return page;
}

async function csrfHeaders(context) {
  const response = await context.request.get('/api/auth/csrf');
  assert.equal(response.status(), 200);
  const token = await response.json();
  return { [token.headerName]: token.token, 'content-type': 'application/json' };
}

async function visibleWrite(context, path, method, data) {
  const headers = await csrfHeaders(context);
  return context.request.fetch(path, { method, data, headers });
}

async function settingSnapshot(context, section) {
  const response = await context.request.get(`/api/platform/settings/${section}`);
  assert.equal(response.status(), 200, `Could not read ${section} in authenticated QA session`);
  return response.json();
}

async function saveSettings(page, section) {
  const responsePromise = page.waitForResponse(response => response.url().endsWith(`/api/platform/settings/${section}`) && response.request().method() === 'PUT');
  await page.locator('.settings-form button[type="submit"]').click();
  const response = await responsePromise;
  assert.equal(response.status(), 200, `${section} UI save failed`);
  return response.json();
}

async function setSwitch(locator, wanted) {
  const current = await locator.getAttribute('aria-checked');
  assert.ok(current === 'true' || current === 'false', 'Expected an accessible switch control');
  if ((current === 'true') !== wanted) await locator.click();
}

async function pickOption(page, control, choices) {
  await control.click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
    .getByText(localized(choices), { exact: false }).last().click();
}

async function fillSettingData(page, data) {
  for (const [key, value] of Object.entries(data)) {
    const locator = page.locator(`#data_${key}`);
    if (!await locator.count()) continue;
    if (typeof value === 'boolean') await setSwitch(locator, value);
    else if (Array.isArray(value)) await locator.fill(value.join('\n'));
    else if (typeof value === 'string') await locator.fill(value);
    else if (typeof value === 'number') await locator.fill(String(value));
  }
}

async function testAgreements() {
  agreementsSnapshot = await settingSnapshot(adminContext, 'agreements');
  await adminPage.goto('/dashboard/agreements');
  const userMarker = `Lumira QA agreement ${randomUUID()}`;
  const privacyMarker = `Lumira QA privacy ${randomUUID()}`;
  await adminPage.locator('#data_userAgreementMarkdown').fill(`# ${userMarker}\n\n**Visible UI fixture.**`);
  await adminPage.locator('#data_privacyAgreementMarkdown').fill(`# ${privacyMarker}\n\n**Visible UI fixture.**`);
  await saveSettings(adminPage, 'agreements');
  agreementsChanged = true;

  const guest = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
  try {
    const guestPage = await guest.newPage();
    await guestPage.goto('/user/login');
    const publishedResponse = await guest.request.get('/api/public/agreements');
    assert.equal(publishedResponse.status(), 200);
    const published = await publishedResponse.json();
    assert.ok(published.userAgreementMarkdown.includes(userMarker), 'Public agreements endpoint did not return the UI-saved user agreement');
    assert.ok(published.privacyAgreementMarkdown.includes(privacyMarker), 'Public agreements endpoint did not return the UI-saved privacy agreement');
    await guestPage.getByRole('button', { name: localized(labels.agreementUser) }).click();
    const userModal = guestPage.locator('.ant-modal:visible .ant-modal-body');
    await userModal.waitFor();
    assert.ok((await userModal.innerText()).includes(userMarker), 'Visible user agreement modal omitted saved Markdown content');
    await guestPage.locator('.ant-modal-close').click();
    await guestPage.getByRole('button', { name: localized(labels.agreementPrivacy) }).click();
    const privacyModal = guestPage.locator('.ant-modal:visible .ant-modal-body');
    await privacyModal.waitFor();
    assert.ok((await privacyModal.innerText()).includes(privacyMarker), 'Visible privacy agreement modal omitted saved Markdown content');
  } finally {
    await guest.close();
  }

  await adminPage.reload();
  assert.ok((await adminPage.locator('#data_userAgreementMarkdown').inputValue()).includes(userMarker));
  assert.ok((await adminPage.locator('#data_privacyAgreementMarkdown').inputValue()).includes(privacyMarker));
  await adminPage.locator('#data_userAgreementMarkdown').fill(agreementsSnapshot.data.userAgreementMarkdown);
  await adminPage.locator('#data_privacyAgreementMarkdown').fill(agreementsSnapshot.data.privacyAgreementMarkdown);
  await saveSettings(adminPage, 'agreements');
  const restored = await settingSnapshot(adminContext, 'agreements');
  assert.deepEqual(restored.data, agreementsSnapshot.data);
  agreementsChanged = false;
  note('agreements: admin UI save/reload, anonymous login consumer shows both saved Markdown values, original UI values restored');
}

async function startSmsReceiver(expectedCredential) {
  let resolveReceipt;
  const receiptPromise = new Promise(resolve => { resolveReceipt = resolve; });
  smsReceiver = createServer((request, response) => {
    const address = request.socket.remoteAddress ?? '';
    const loopback = address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1';
    const chunks = [];
    let size = 0;
    request.on('data', chunk => {
      size += chunk.length;
      if (size <= 8192) chunks.push(chunk);
    });
    request.on('end', () => {
      let body = null;
      try { if (size <= 8192) body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch {}
      const record = {
        loopback,
        method: request.method,
        path: request.url,
        authorizationMatches: request.headers.authorization === `Bearer ${expectedCredential}`,
        to: body?.to,
        sender: body?.sender,
        message: body?.message,
      };
      resolveReceipt(record);
      response.writeHead(loopback && request.method === 'POST' && request.url === '/send' ? 202 : 403, { 'content-type': 'application/json' });
      response.end('{}');
    });
  });
  await new Promise((resolve, reject) => {
    smsReceiver.once('error', reject);
    smsReceiver.listen(0, '127.0.0.1', resolve);
  });
  const address = smsReceiver.address();
  assert.ok(address && typeof address === 'object' && address.address === '127.0.0.1');
  smsReceiverPort = address.port;
  return { receipt: receiptPromise };
}

async function testSms() {
  smsSnapshot = await settingSnapshot(adminContext, 'sms');
  assert.equal(smsSnapshot.data.enabled, false, 'SMS QA precondition requires delivery disabled');
  assert.equal(smsSnapshot.credentialConfigured, false, 'SMS QA precondition requires no existing provider credential');
  assert.equal(smsSnapshot.encryptionConfigured, true, 'SMS secret encryption must be available');
  const credential = randomBytes(32).toString('base64url');
  const { receipt } = await startSmsReceiver(credential);
  await adminPage.goto('/dashboard/sms');
  await setSwitch(adminPage.locator('#data_enabled'), true);
  await adminPage.locator('#data_endpoint').fill(`http://127.0.0.1:${smsReceiverPort}/send`);
  await adminPage.locator('#data_sender').fill('Lumira QA');
  await adminPage.locator('#data_template').fill('Lumira QA code: {code}');
  await pickOption(adminPage, adminPage.getByRole('combobox', { name: localized(['访问密钥操作', '存取金鑰操作', 'Access key action']), exact: true }), labels.replaceKey);
  await adminPage.locator('.settings-form input[type="password"]').fill(credential);
  await saveSettings(adminPage, 'sms');
  smsChanged = true;
  await adminPage.reload();
  assert.equal(await adminPage.locator('#data_endpoint').inputValue(), `http://127.0.0.1:${smsReceiverPort}/send`);
  assert.equal(await adminPage.locator('#data_sender').inputValue(), 'Lumira QA');
  assert.equal(await adminPage.locator('#data_template').inputValue(), 'Lumira QA code: {code}');
  assert.equal(await adminPage.locator('.settings-form input[type="password"]').count(), 0, 'Write-only provider key must not reappear in the UI');

  const send = await adminPage.evaluate(async () => {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
    const csrf = await csrfResponse.json();
    const response = await fetch('/api/platform/settings/sms/test', { method: 'POST', credentials: 'same-origin', headers: { [csrf.headerName]: csrf.token } });
    const body = await response.json();
    return { status: response.status, result: body.result };
  });
  assert.deepEqual(send, { status: 200, result: 'ACCEPTED' }, 'Authenticated in-page SMS test action must report acceptance');
  const received = await Promise.race([receipt, new Promise((_, reject) => setTimeout(() => reject(new Error('Loopback SMS receiver did not receive a request')), 10000))]);
  assert.equal(received.loopback, true);
  assert.equal(received.method, 'POST');
  assert.equal(received.path, '/send');
  assert.equal(received.authorizationMatches, true);
  assert.equal(received.to, '+15555550123');
  assert.equal(received.sender, 'Lumira QA');
  assert.equal(received.message, 'Lumira local SMS transport test. No account code is included.');

  await adminPage.goto('/dashboard/audit');
  await adminPage.locator('.ant-table').first().waitFor();
  await adminPage.getByText('sms.localTest.ACCEPTED', { exact: false }).waitFor();

  await adminPage.goto('/dashboard/sms');
  await setSwitch(adminPage.locator('#data_enabled'), false);
  await adminPage.locator('#data_endpoint').fill(smsSnapshot.data.endpoint);
  await adminPage.locator('#data_sender').fill(smsSnapshot.data.sender);
  await adminPage.locator('#data_template').fill(smsSnapshot.data.template);
  await adminPage.locator('#data_timeoutSeconds').fill(String(smsSnapshot.data.timeoutSeconds));
  await pickOption(adminPage, adminPage.getByRole('combobox', { name: localized(['访问密钥操作', '存取金鑰操作', 'Access key action']), exact: true }), labels.clearKey);
  await saveSettings(adminPage, 'sms');
  smsChanged = false;
  await adminPage.reload();
  const restored = await settingSnapshot(adminContext, 'sms');
  assert.deepEqual(restored.data, smsSnapshot.data);
  assert.equal(restored.credentialConfigured, false);
  note('SMS: settings UI save/reload/restore; no visible delivery-test control exists, so same-origin browser-session POST returned ACCEPTED; fixed synthetic payload and bearer matched only by the 127.0.0.1 receiver; Audit UI showed sms.localTest.ACCEPTED');
}

async function testWatermark() {
  watermarkSnapshot = await settingSnapshot(adminContext, 'watermark');
  assert.equal(watermarkSnapshot.data.enabled, false, 'Watermark QA precondition requires disabled watermark');
  const marker = `Lumira QA watermark ${randomUUID()}`;
  await adminPage.addInitScript(() => {
    window.__lumiraQAWatermarks = [];
    const original = CanvasRenderingContext2D.prototype.fillText;
    CanvasRenderingContext2D.prototype.fillText = function (text, ...args) {
      window.__lumiraQAWatermarks.push(String(text));
      return original.call(this, text, ...args);
    };
  });
  await adminPage.goto('/dashboard/watermark');
  await setSwitch(adminPage.locator('#data_enabled'), true);
  await pickOption(adminPage, adminPage.locator('#data_mode'), ['固定文本', '固定文字', 'Fixed text']);
  await adminPage.locator('#data_textLines').fill(marker);
  await saveSettings(adminPage, 'watermark');
  watermarkChanged = true;
  await adminPage.goto('/dashboard/home');
  await adminPage.waitForFunction(text => window.__lumiraQAWatermarks?.includes(text), marker, { timeout: 15000 });
  await adminPage.locator('.platform-watermark').waitFor();
  assert.equal(await adminPage.locator('.platform-watermark').evaluate(element => getComputedStyle(element).pointerEvents), 'none');
  await adminPage.reload();
  await adminPage.waitForFunction(text => window.__lumiraQAWatermarks?.includes(text), marker, { timeout: 15000 });
  await adminPage.goto('/dashboard/watermark');
  await adminPage.locator('#data_textLines').waitFor();
  assert.ok((await adminPage.locator('#data_textLines').inputValue()).includes(marker));
  await setSwitch(adminPage.locator('#data_enabled'), false);
  await adminPage.locator('#data_textLines').fill((watermarkSnapshot.data.textLines ?? []).join('\n'));
  await saveSettings(adminPage, 'watermark');
  watermarkChanged = false;
  await adminPage.reload();
  const restored = await settingSnapshot(adminContext, 'watermark');
  assert.deepEqual(restored.data, watermarkSnapshot.data);
  note('watermark: UI enable/text save, dashboard canvas consumer, click-through overlay, reload persistence, original values restored');
}

async function saveRoleSelection(roleCode, enabled) {
  await adminPage.goto('/dashboard/users');
  const userResponse = await adminContext.request.get(`/api/platform/users?${new URLSearchParams({ page: '1', pageSize: '50', search: readerUsername })}`);
  assert.equal(userResponse.status(), 200);
  const userPage = await userResponse.json();
  const user = userPage.items.find(item => item.username === readerUsername);
  assert.ok(user, 'Synthetic read-only account was not returned by the authenticated directory UI backing API');
  readerId = user.id;
  const row = adminPage.getByRole('row').filter({ hasText: readerUsername });
  if (!await row.count()) {
    const searchBox = adminPage.locator('.resource-table').getByRole('textbox').first();
    await searchBox.fill(readerUsername);
    await searchBox.press('Enter');
  }
  const visibleRow = adminPage.getByRole('row').filter({ hasText: readerUsername });
  await visibleRow.getByRole('button', { name: localized(labels.edit) }).click();
  await adminPage.getByRole('tab', { name: localized(labels.assignRoles) }).click();
  const roleCodeSearch = adminPage.getByRole('search').getByLabel(localized(['角色编码', '角色代碼', 'Role code']));
  await roleCodeSearch.fill(roleCode);
  await roleCodeSearch.press('Enter');
  const roleRow = adminPage.getByRole('row').filter({ hasText: roleCode });
  await roleRow.waitFor();
  const checkbox = roleRow.locator('input[type="checkbox"]');
  if (enabled) await checkbox.check({ force: true }); else await checkbox.uncheck({ force: true });
  const save = adminPage.waitForResponse(response => response.url().endsWith(`/api/platform/users/${readerId}/editor`) && response.request().method() === 'PUT');
  await adminPage.locator('button[form="user-editor"]').click();
  assert.equal((await save).status(), 200, 'Temporary role assignment through the user editor failed');
  await adminPage.waitForTimeout(250);
}

async function createReadonlyUserRole() {
  const suffix = randomUUID().replaceAll('-', '');
  readerUsername = `qa_settings_read_${suffix}`;
  readerPassword = randomBytes(24).toString('base64url');
  readerRoleCode = `qa_settings_ro_${suffix}`;
  readerRoleName = `QA settings read only ${suffix.slice(0, 8)}`;

  await adminPage.goto('/dashboard/roles');
  await adminPage.getByRole('button', { name: localized(labels.createRole) }).click();
  const roleForm = adminPage.locator('#role-editor');
  await roleForm.locator('#code').fill(readerRoleCode);
  await roleForm.locator('#name').fill(readerRoleName);
  await roleForm.locator('#dataScope').click();
  await adminPage.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText(localized(['全部', 'All']), { exact: true }).last().click();
  await adminPage.locator('input[type="checkbox"][value="platform:settings:read"]').check({ force: true });
  await adminPage.locator('input[type="checkbox"][value="platform:dictionaries:read"]').check({ force: true });
  const roleResponse = adminPage.waitForResponse(response => response.url().endsWith('/api/platform/roles') && response.request().method() === 'POST');
  await adminPage.locator('button[form="role-editor"]').click();
  const createdRole = await roleResponse;
  assert.equal(createdRole.status(), 201, 'Temporary read-only role UI creation failed');
  readerRoleId = (await createdRole.json()).id;
  assert.ok(readerRoleId);

  await adminPage.goto('/dashboard/users');
  await adminPage.getByRole('button', { name: localized(labels.createUser) }).click();
  const userForm = adminPage.locator('#user-editor');
  await userForm.locator('#username').fill(readerUsername);
  await userForm.locator('#displayName').fill('QA settings read only');
  await userForm.locator('#password').fill(readerPassword);
  const userCreate = adminPage.waitForResponse(response => response.url().endsWith('/api/platform/users') && response.request().method() === 'POST');
  await adminPage.locator('button[form="user-editor"]').click();
  const createdUser = await userCreate;
  assert.equal(createdUser.status(), 201, 'Temporary read-only account UI creation failed');
  readerId = (await createdUser.json()).id;
  await saveRoleSelection(readerRoleCode, true);

  readerContext = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
  readerPage = await loginUI(readerContext, readerUsername, readerPassword);
  const identityResponse = await readerContext.request.get('/api/auth/me');
  assert.equal(identityResponse.status(), 200);
  const identity = await identityResponse.json();
  assert.ok(identity.globalAuthorities.includes('platform:settings:read'));
  assert.ok(!identity.globalAuthorities.includes('platform:settings:write'));
  note('read-only user and role were created/assigned through admin UI; account signed in via the login page');
}

async function testReadonlySettings() {
  for (const section of ['agreements', 'watermark', 'sms']) {
    const snapshot = await settingSnapshot(readerContext, section);
    await readerPage.goto(`/dashboard/${section}`);
    await readerPage.locator('.settings-form').waitFor();
    assert.equal(await readerPage.locator('.settings-form button[type="submit"]').isDisabled(), true, `${section} save must be disabled for a read-only account`);
    const enabledDataControls = await readerPage.locator('.settings-form [id^="data_"]').evaluateAll(elements => elements
      .filter(element => ['INPUT', 'TEXTAREA', 'BUTTON', 'SELECT'].includes(element.tagName) || ['switch', 'combobox'].includes(element.getAttribute('role')))
      .filter(element => !element.disabled && element.getAttribute('aria-disabled') !== 'true').length);
    assert.equal(enabledDataControls, 0, `${section} data fields must expose no editable controls`);
    const denied = await visibleWrite(readerContext, `/api/platform/settings/${section}`, 'PUT', {
      version: snapshot.version,
      data: snapshot.data,
      ...(section === 'sms' ? { credentials: { action: 'KEEP' } } : {}),
    });
    assert.equal(denied.status(), 403, `${section} direct authenticated UI-session write must be denied`);
    assert.equal((await settingSnapshot(adminContext, section)).version, snapshot.version, `${section} revision must remain unchanged`);
  }
  await readerPage.goto('/dashboard/agreements');
  await readerPage.getByRole('button', { name: localized(labels.settingHistory) }).click();
  await readerPage.locator('.ant-drawer').getByRole('row').first().waitFor();
  const firstDetails = readerPage.locator('.ant-drawer').getByRole('button', { name: localized(labels.details) }).first();
  if (await firstDetails.count()) await firstDetails.click();
  assert.equal(await readerPage.locator('button[form="restore-agreements"]').count(), 0, 'Read-only history must not expose restore action');
  agreementsSnapshot ??= await settingSnapshot(adminContext, 'agreements');
  const rollback = await visibleWrite(readerContext, '/api/platform/settings/agreements/rollback', 'POST', { version: agreementsSnapshot.version, sourceVersion: agreementsSnapshot.version || 1 });
  assert.equal(rollback.status(), 403);
  await readerPage.goto('/dashboard/dictionaries');
  await readerPage.locator('.ant-table').first().waitFor();
  assert.equal(await readerPage.getByRole('button', { name: localized(['新增字典', 'Create dictionary']) }).count(), 0);
  const dictionaryResponse = await readerContext.request.get(`/api/platform/dictionaries?${new URLSearchParams({ page: '1', pageSize: '50' })}`);
  assert.equal(dictionaryResponse.status(), 200);
  const dictionaries = await dictionaryResponse.json();
  let selectedDictionary;
  let selectedItem;
  for (const dictionary of dictionaries.items) {
    const itemResponse = await readerContext.request.get(`/api/platform/dictionaries/${dictionary.id}/items?${new URLSearchParams({ page: '1', pageSize: '50' })}`);
    assert.equal(itemResponse.status(), 200);
    const items = await itemResponse.json();
    if (items.items[0]) {
      selectedDictionary = dictionary;
      selectedItem = items.items[0];
      break;
    }
  }
  assert.ok(selectedDictionary && selectedItem, 'The isolated QA database needs one readable dictionary item');
  const dictionaryRow = readerPage.getByRole('row').filter({ hasText: selectedDictionary.code });
  await dictionaryRow.getByRole('button', { name: localized(labels.details) }).click();
  await readerPage.locator('#dictionary-editor').waitFor();
  assert.equal(await readerPage.locator('button[form="dictionary-editor"]').isDisabled(), true, 'Read-only dictionary details must not expose a save action');
  await readerPage.locator('.ant-drawer-close').click();
  await dictionaryRow.getByRole('button', { name: localized(['字典项', 'Dictionary items']) }).click();
  const itemRow = readerPage.getByRole('row').filter({ hasText: selectedItem.value });
  await itemRow.waitFor();
  await itemRow.getByRole('button', { name: localized(labels.details) }).click();
  await readerPage.locator('#dictionary-item-editor').waitFor();
  assert.equal(await readerPage.locator('button[form="dictionary-item-editor"]').isDisabled(), true, 'Read-only dictionary item details must not expose a save action');
  const deniedDictionaryWrite = await visibleWrite(readerContext, `/api/platform/dictionaries/${selectedDictionary.id}/items/${selectedItem.id}`, 'PUT', {
    parentId: selectedItem.parentId,
    value: selectedItem.value,
    label: selectedItem.label,
    sortOrder: selectedItem.sortOrder,
    enabled: selectedItem.enabled,
    remark: selectedItem.remark,
    version: selectedItem.version,
  });
  assert.equal(deniedDictionaryWrite.status(), 403);
  note('read-only settings: agreements/watermark/SMS form controls and saves disabled, authenticated writes and rollback rejected with 403, versions unchanged');
  note('read-only dictionary access: list visible without create action; item update rejected with 403');
}

async function visitCustomValues(page) {
  await page.goto('/dashboard/home');
  await page.locator('.pro-account-trigger').click();
  await page.getByRole('menuitem', { name: localized(['个人资料', '個人資料', 'Profile']) }).click();
  await page.getByRole('button', { name: localized(['自定义资料', '自訂資料', 'Custom profile']) }).click();
  await page.locator(`#${fixture.fieldKey}`).waitFor();
}

async function selectProfileOption(page) {
  const field = page.locator(`#${fixture.fieldKey}`);
  await field.click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText(fixture.itemLabel, { exact: true }).click();
}

async function openDictionaryItemEditor() {
  await adminPage.goto('/dashboard/dictionaries');
  const search = adminPage.locator('.resource-table').getByRole('textbox').first();
  await search.fill(fixture.dictionaryCode);
  await search.press('Enter');
  const row = adminPage.getByRole('row').filter({ hasText: fixture.dictionaryCode });
  await row.waitFor();
  await row.getByRole('button', { name: localized(['字典项', 'Dictionary items']) }).click();
  const itemSearch = adminPage.locator('.resource-table').getByRole('textbox').first();
  await itemSearch.fill(fixture.itemLabel);
  await itemSearch.press('Enter');
  const itemRow = adminPage.getByRole('row').filter({ hasText: fixture.itemLabel });
  await itemRow.waitFor();
  await itemRow.getByRole('button', { name: localized(['编辑', 'Edit']) }).click();
  await adminPage.locator('#dictionary-item-editor').waitFor();
}

async function setDictionaryItemEnabled(enabled) {
  await openDictionaryItemEditor();
  await setSwitch(adminPage.locator('#dictionary-item-editor #enabled'), enabled);
  const responsePromise = adminPage.waitForResponse(response => response.url().includes('/api/platform/dictionaries/') && response.request().method() === 'PUT');
  await adminPage.locator('button[form="dictionary-item-editor"]').click();
  const response = await responsePromise;
  assert.equal(response.status(), 200, `Dictionary item UI toggle to ${enabled} failed`);
}

async function resolveDictionaryFixture() {
  if (!fixture.dictionaryId) {
    const response = await adminContext.request.get(`/api/platform/dictionaries?${new URLSearchParams({ page: '1', pageSize: '100', search: fixture.dictionaryCode })}`);
    assert.equal(response.status(), 200);
    const dictionaries = await response.json();
    fixture.dictionaryId = dictionaries.items.find(item => item.code === fixture.dictionaryCode)?.id;
  }
  assert.ok(fixture.dictionaryId, 'Completed SELECT dictionary could not be found by its non-secret handoff marker');
  if (!fixture.itemId) {
    const response = await adminContext.request.get(`/api/platform/dictionaries/${fixture.dictionaryId}/items?${new URLSearchParams({ page: '1', pageSize: '100', search: fixture.itemLabel })}`);
    assert.equal(response.status(), 200);
    const items = await response.json();
    fixture.itemId = items.items.find(item => item.label === fixture.itemLabel)?.id;
  }
  assert.ok(fixture.itemId, 'Completed SELECT item could not be found by its non-secret handoff marker');
  const current = await adminContext.request.get(`/api/platform/dictionaries/${fixture.dictionaryId}/items/${fixture.itemId}`);
  assert.equal(current.status(), 200, 'Synthetic SELECT item must exist before the test');
  assert.equal((await current.json()).enabled, true, 'Synthetic SELECT item must be active before the test');
}

async function verifyReferencedItemCannotBeDeleted() {
  await openDictionaryItemEditor();
  await adminPage.locator('.ant-drawer-close').click();
  const itemRow = adminPage.getByRole('row').filter({ hasText: fixture.itemLabel });
  const deleteButton = itemRow.getByRole('button', { name: localized(['删除记录', 'Delete record', '删除']) });
  await deleteButton.waitFor();
  const deleted = adminPage.waitForResponse(response => response.request().method() === 'DELETE'
    && new URL(response.url()).pathname === `/api/platform/dictionaries/${fixture.dictionaryId}/items/${fixture.itemId}`);
  await deleteButton.click();
  const confirm = adminPage.locator('.ant-popconfirm').getByRole('button', { name: localized(['删除记录', 'Delete record', '删除']) });
  if (await confirm.count()) await confirm.click();
  else await adminPage.locator('.ant-modal-confirm-btns button.ant-btn-primary').click();
  const response = await deleted;
  assert.equal(response.status(), 409, 'A referenced dictionary item must remain protected from deletion');
  const remains = await adminContext.request.get(`/api/platform/dictionaries/${fixture.dictionaryId}/items/${fixture.itemId}`);
  assert.equal(remains.status(), 200);
  assert.equal((await remains.json()).enabled, false, 'Rejected deletion must leave the synthetic item disabled and intact');
  note('dictionary reference guard: attempted admin UI deletion of a selected synthetic item returned 409; the item remained present and disabled');
}

async function testDisabledDictionaryValue() {
  await resolveDictionaryFixture();
  const ordinaryContext = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
  let selected = false;
  let disabled = false;
  let valueCleared = false;
  let testCompleted = false;
  try {
    const page = await loginUI(ordinaryContext, ordinary.username, ordinary.password);
    const originalProfileResponse = await ordinaryContext.request.get('/api/account/profile-values');
    assert.equal(originalProfileResponse.status(), 200);
    const originalProfile = await originalProfileResponse.json();
    const originalValue = originalProfile.values[fixture.fieldKey] ?? null;
    assert.ok(!originalValue || originalValue === fixture.itemId, 'Old-value reproduction found an unrelated value in the synthetic field; refusing to overwrite it');
    await visitCustomValues(page);
    selected = true;
    if (!originalValue) {
      await selectProfileOption(page);
      const save = page.waitForResponse(response => response.url().endsWith('/api/account/profile-values') && response.request().method() === 'PUT');
      await page.locator('button[form="account-profile-values"]').click();
      assert.equal((await save).status(), 200, 'Synthetic option could not be saved through profile UI');
      await visitCustomValues(page);
    }
    assert.ok(await page.locator(`#${fixture.fieldKey}`).count());

    await setDictionaryItemEnabled(false);
    disabled = true;
    await verifyReferencedItemCannotBeDeleted();
    const detailPath = `/api/dictionaries/${encodeURIComponent(fixture.dictionaryCode)}/items/${fixture.itemId}`;
    let detailQueries = 0;
    page.on('request', request => {
      if (request.method() === 'GET' && new URL(request.url()).pathname === detailPath) detailQueries += 1;
    });
    await page.reload();
    await visitCustomValues(page);
    const oldValueLookup = await ordinaryContext.request.get(detailPath);
    assert.equal(oldValueLookup.status(), 404, 'Public dictionary lookup must continue hiding disabled values');
    assert.equal(detailQueries, 0, 'The UI must use profile snapshot metadata instead of looking up a disabled value publicly');
    const fieldFormItem = page.locator(`#${fixture.fieldKey}`).locator('xpath=ancestor::div[contains(concat(" ", normalize-space(@class), " "), " ant-form-item ")][1]');
    const uiValue = await fieldFormItem.innerText();
    const beforeNoopResponse = await ordinaryContext.request.get('/api/account/profile-values');
    assert.equal(beforeNoopResponse.status(), 200);
    const beforeNoopProfile = await beforeNoopResponse.json();
    assert.equal(beforeNoopProfile.values[fixture.fieldKey], fixture.itemId, 'Disabled old selection was not retained before the unchanged profile save');
    assert.deepEqual(beforeNoopProfile.dictionarySelections[fixture.fieldKey], { itemId: fixture.itemId, label: fixture.itemLabel, enabled: false }, 'Profile snapshot did not return the disabled current-value metadata');
    assert.ok(uiValue.includes(fixture.itemLabel), 'Disabled current value label is not displayed from the profile snapshot');

    await page.locator(`#${fixture.fieldKey}`).click();
    const selectedDropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
    const historicalOption = selectedDropdown.locator('.ant-select-item-option').filter({ hasText: fixture.itemLabel });
    await historicalOption.waitFor();
    const historicalOptionClass = await historicalOption.getAttribute('class') ?? '';
    assert.ok(historicalOptionClass.includes('ant-select-item-option-disabled')
      || (await historicalOption.getAttribute('aria-disabled')) === 'true', 'Disabled historical value must be visible but not selectable');
    await page.keyboard.press('Escape');

    const saveAttempt = page.waitForResponse(response => response.url().endsWith('/api/account/profile-values') && response.request().method() === 'PUT');
    await page.locator('button[form="account-profile-values"]').click();
    const saveResponse = await saveAttempt;
    assert.equal(saveResponse.status(), 200, 'Saving an unchanged disabled dictionary value must preserve the stored value');
    await page.reload();
    await visitCustomValues(page);
    const afterNoopResponse = await ordinaryContext.request.get('/api/account/profile-values');
    assert.equal(afterNoopResponse.status(), 200);
    const afterNoopProfile = await afterNoopResponse.json();
    assert.equal(afterNoopProfile.values[fixture.fieldKey], fixture.itemId, 'Unchanged profile save cleared or changed the disabled old selection');
    assert.deepEqual(afterNoopProfile.dictionarySelections[fixture.fieldKey], { itemId: fixture.itemId, label: fixture.itemLabel, enabled: false }, 'Reloaded profile snapshot lost the disabled historical value metadata');
    const reloadedField = page.locator(`#${fixture.fieldKey}`).locator('xpath=ancestor::div[contains(concat(" ", normalize-space(@class), " "), " ant-form-item ")][1]');
    const reloadedLabelVisible = (await reloadedField.innerText()).includes(fixture.itemLabel);
    assert.ok(reloadedLabelVisible, 'Reloaded UI no longer displays the disabled historical value label');
    assert.equal(detailQueries, 0, 'Reloaded UI must not issue a public disabled-item detail lookup');
    note(`disabled dictionary old value: public selected-value lookup=${oldValueLookup.status()}; UI detail lookups=${detailQueries}; unchanged profile-form save=${saveResponse.status()}, refresh retained=${afterNoopProfile.values[fixture.fieldKey] === fixture.itemId}, disabled metadata and label persisted`);
    const selectControl = page.locator(`#${fixture.fieldKey}`).locator('xpath=ancestor::div[contains(concat(" ", normalize-space(@class), " "), " ant-select ")][1]');
    const clearChoice = selectControl.locator('.ant-select-clear');
    assert.ok(await clearChoice.count(), 'The disabled historical value must remain clearable from the profile form');
    await clearChoice.click();
    const fieldInput = page.locator(`#${fixture.fieldKey}`);
    const disabledSearchPath = `/api/dictionaries/${encodeURIComponent(fixture.dictionaryCode)}/items`;
    const disabledSearchResponse = page.waitForResponse(response => {
      if (response.request().method() !== 'GET') return false;
      const url = new URL(response.url());
      return url.pathname === disabledSearchPath && url.searchParams.get('search') === fixture.itemLabel;
    });
    await fieldInput.fill(fixture.itemLabel);
    const disabledSearch = await disabledSearchResponse;
    assert.equal(disabledSearch.status(), 200, 'The SELECT UI search for a disabled historical value did not complete');
    const disabledSearchPage = await disabledSearch.json();
    assert.ok(!disabledSearchPage.items.some(item => item.id === fixture.itemId), 'Disabled historical value was returned as a new selectable option');
    const clearedDropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
    assert.equal(await clearedDropdown.locator('.ant-select-item-option').filter({ hasText: fixture.itemLabel }).count(), 0, 'Cleared disabled historical value remained in the selectable option list');
    await page.keyboard.press('Escape');
    const clearSave = page.waitForResponse(response => response.url().endsWith('/api/account/profile-values') && response.request().method() === 'PUT');
    await page.locator('button[form="account-profile-values"]').click();
    const clearResponse = await clearSave;
    assert.equal(clearResponse.status(), 200, 'Could not clear the disabled synthetic value through the profile UI');
    await page.reload();
    await visitCustomValues(page);
    const clearedProfileResponse = await ordinaryContext.request.get('/api/account/profile-values');
    assert.equal(clearedProfileResponse.status(), 200);
    const clearedProfile = await clearedProfileResponse.json();
    assert.ok(!clearedProfile.values[fixture.fieldKey], 'Disabled synthetic value was not cleared after reload');
    assert.ok(!clearedProfile.dictionarySelections?.[fixture.fieldKey], 'Cleared profile snapshot retained stale disabled-value metadata');
    valueCleared = true;
    note('dictionary reproduction: unchanged disabled value retained with label/status from profile snapshot; after clear the disabled item was absent from UI search results and the field stayed empty after reload');
    testCompleted = true;
    return { lookupStatus: oldValueLookup.status(), detailQueries, saveStatus: saveResponse.status(), retained: afterNoopProfile.values[fixture.fieldKey] === fixture.itemId, labelVisible: reloadedLabelVisible, clearStatus: clearResponse.status(), reselectionItems: disabledSearchPage.items.length, cleared: !clearedProfile.values[fixture.fieldKey] };
  } finally {
    let cleanupError;
    try {
      if (selected && !valueCleared) {
        const page = ordinaryContext.pages()[0] ?? await ordinaryContext.newPage();
        const currentResponse = await ordinaryContext.request.get('/api/account/profile-values');
        if (currentResponse.status() === 200 && (await currentResponse.json()).values[fixture.fieldKey]) {
          await visitCustomValues(page);
          const selectControl = page.locator(`#${fixture.fieldKey}`).locator('xpath=ancestor::div[contains(concat(" ", normalize-space(@class), " "), " ant-select ")][1]');
          const clearChoice = selectControl.locator('.ant-select-clear');
          if (await clearChoice.count()) {
            await clearChoice.click();
            const clearSave = page.waitForResponse(response => response.url().endsWith('/api/account/profile-values') && response.request().method() === 'PUT');
            await page.locator('button[form="account-profile-values"]').click();
            assert.equal((await clearSave).status(), 200);
          }
        }
      }
    } catch {
      console.error('PROFILE_CLEAR_RECOVERY_REQUIRED: flat QA field may still hold the synthetic value; database remains isolated');
      cleanupError = new Error('Flat QA profile field could not be cleared through the UI');
    } finally {
      try {
        if (disabled) {
          await setDictionaryItemEnabled(true);
          const restoredItem = await adminContext.request.get(`/api/platform/dictionaries/${fixture.dictionaryId}/items/${fixture.itemId}`);
          assert.equal(restoredItem.status(), 200);
          assert.equal((await restoredItem.json()).enabled, true, 'Synthetic dictionary item was not re-enabled after reproduction');
        }
      } catch {
        console.error('DICTIONARY_RESTORE_REQUIRED: flat QA item may remain disabled; database remains isolated');
        cleanupError ??= new Error('Flat QA dictionary item could not be restored through the UI');
      } finally {
        try {
          await ordinaryContext.close();
        } catch {
          console.error('BROWSER_CONTEXT_CLOSE_REQUIRED: flat QA browser context did not close cleanly');
        }
      }
    }
    if (cleanupError && testCompleted) throw cleanupError;
  }
}

async function chooseMaintenanceRole(searchTerm, rowName, checked) {
  const picker = adminPage.locator('#data_allowedRoleIds');
  const search = picker.locator('.resource-table').getByRole('textbox').first();
  await search.fill(searchTerm);
  await search.press('Enter');
  const row = picker.getByRole('row').filter({ hasText: rowName });
  await row.waitFor();
  const checkbox = row.locator('input[type="checkbox"]');
  if (checked) await checkbox.check({ force: true }); else await checkbox.uncheck({ force: true });
}

async function testMaintenanceLast() {
  maintenanceSnapshot = await settingSnapshot(adminContext, 'maintenance');
  assert.equal(maintenanceSnapshot.data.enabled, false, 'Maintenance QA precondition requires maintenance disabled');
  await adminPage.goto('/dashboard/maintenance');
  await adminPage.locator('#data_title').fill('Lumira QA maintenance window');
  await adminPage.locator('#data_message').fill('Temporary local maintenance acceptance');
  await setSwitch(adminPage.locator('#data_enabled'), true);
  await saveSettings(adminPage, 'maintenance');
  maintenanceChanged = true;

  const blockedContext = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
  try {
    await loginUI(blockedContext, readerUsername, readerPassword, false);
  } finally {
    await blockedContext.close();
  }

  // The configured role is a page-read-only role, and maintenance admission is
  // intentionally independent of that read-only settings permission.
  await chooseMaintenanceRole(readerRoleCode, readerRoleName, true);
  await saveSettings(adminPage, 'maintenance');
  const allowedContext = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
  try {
    const allowedPage = await loginUI(allowedContext, readerUsername, readerPassword, true);
    await allowedPage.goto('/dashboard/agreements');
    await allowedPage.locator('.settings-form button[type="submit"]').waitFor();
    assert.equal(await allowedPage.locator('.settings-form button[type="submit"]').isDisabled(), true);
  } finally {
    await allowedContext.close();
  }

  // The still-authenticated administrator uses the real maintenance UI to close
  // the window. No process restart or direct database edit is involved.
  await adminPage.goto('/dashboard/maintenance');
  await setSwitch(adminPage.locator('#data_enabled'), false);
  await chooseMaintenanceRole(readerRoleCode, readerRoleName, false);
  await adminPage.locator('#data_title').fill(maintenanceSnapshot.data.title);
  await adminPage.locator('#data_message').fill(maintenanceSnapshot.data.message);
  await saveSettings(adminPage, 'maintenance');
  maintenanceChanged = false;
  const afterCloseContext = await browser.newContext({ baseURL, viewport: { width: 1280, height: 900 } });
  try {
    await loginUI(afterCloseContext, readerUsername, readerPassword, true);
  } finally {
    await afterCloseContext.close();
  }
  await adminPage.reload();
  const restored = await settingSnapshot(adminContext, 'maintenance');
  assert.deepEqual(restored.data, maintenanceSnapshot.data);
  note('maintenance (last): ordinary read-only account rejected while active; selected role admitted it; administrator closed maintenance; ordinary login recovered; original maintenance values restored');
}

async function cleanupReaderThroughUI() {
  if (readerCleanupDone) return;
  let retainedOrphanRoles = 0;
  const usersResponse = await adminContext.request.get('/api/platform/users?page=1&pageSize=100');
  assert.equal(usersResponse.status(), 200);
  const usersPage = await usersResponse.json();
  const ownedUsers = usersPage.items.filter(user => user.username.startsWith('qa_settings_read_'));
  const detachedRoleIds = new Set();
  // Detach our role through the live user editor before the user's soft-delete.
  // Soft-deleted accounts no longer have an unassign UI, while the role guard
  // intentionally continues to count their role links.
  for (const user of ownedUsers) {
    const assignmentsResponse = await adminContext.request.get(`/api/platform/users/${user.id}/roles`);
    assert.equal(assignmentsResponse.status(), 200);
    const assignments = await assignmentsResponse.json();
    for (const roleId of assignments.roleIds) {
      const roleResponse = await adminContext.request.get(`/api/platform/roles/${roleId}`);
      if (roleResponse.status() !== 200) continue;
      const role = await roleResponse.json();
      if (!role.code.startsWith('qa_settings_ro_')) continue;
      readerUsername = user.username;
      readerRoleCode = role.code;
      await saveRoleSelection(role.code, false);
      detachedRoleIds.add(role.id);
    }
  }
  if (readerId && !ownedUsers.some(user => user.id === readerId)) {
    const current = await adminContext.request.get(`/api/platform/users/${readerId}`);
    assert.equal(current.status(), 404, 'Temporary read-only account is missing from list but still exists');
  }
  if (ownedUsers.length) {
    await adminPage.goto('/dashboard/users');
    await adminPage.locator('.ant-table').first().waitFor();
    for (const user of ownedUsers) {
      let userRow = adminPage.getByRole('row').filter({ hasText: user.username });
      if (!await userRow.count()) {
        const table = adminPage.locator('.resource-table');
        await table.getByRole('textbox').first().fill(user.username);
        await table.getByRole('button', { name: /查\s*询|Search/i }).click();
        userRow = adminPage.getByRole('row').filter({ hasText: user.username });
        await userRow.waitFor();
      }
      const deleteButton = userRow.getByRole('button', { name: localized(labels.delete) });
      const deleted = adminPage.waitForResponse(response => response.request().method() === 'DELETE' && new URL(response.url()).pathname === `/api/platform/users/${user.id}`);
      await deleteButton.click();
      const confirm = adminPage.locator('.ant-popconfirm').getByRole('button', { name: localized(['删除记录', 'Delete record', '删除']) });
      if (await confirm.count()) await confirm.click();
      else await adminPage.locator('.ant-modal-confirm-btns button.ant-btn-primary').click();
      assert.ok([200, 204].includes((await deleted).status()), 'Temporary read-only account deletion did not succeed');
      assert.equal((await adminContext.request.get(`/api/platform/users/${user.id}`)).status(), 404, 'Temporary read-only account remains after UI cleanup');
      await adminPage.locator('.ant-table').first().waitFor();
    }
  }

  const rolesResponse = await adminContext.request.get('/api/platform/roles?page=1&pageSize=100');
  assert.equal(rolesResponse.status(), 200);
  const rolesPage = await rolesResponse.json();
  const ownedRoles = rolesPage.items.filter(role => role.code.startsWith('qa_settings_ro_'));
  if (readerRoleId && !ownedRoles.some(role => role.id === readerRoleId)) {
    const current = await adminContext.request.get(`/api/platform/roles/${readerRoleId}`);
    assert.equal(current.status(), 404, 'Temporary read-only role is missing from list but still exists');
  }
  if (ownedRoles.length) {
    await adminPage.goto('/dashboard/roles');
    await adminPage.locator('.ant-table').first().waitFor();
    for (const role of ownedRoles) {
      if (!detachedRoleIds.has(role.id)) {
        retainedOrphanRoles += 1;
        continue;
      }
      let roleRow = adminPage.getByRole('row').filter({ hasText: role.code });
      if (!await roleRow.count()) {
        const table = adminPage.locator('.resource-table');
        await table.getByRole('textbox').first().fill(role.code);
        await table.getByRole('button', { name: /查\s*询|Search/i }).click();
        roleRow = adminPage.getByRole('row').filter({ hasText: role.code });
        await roleRow.waitFor();
      }
      const deleteButton = roleRow.getByRole('button', { name: localized(labels.delete) });
      const deleted = adminPage.waitForResponse(response => response.request().method() === 'DELETE' && new URL(response.url()).pathname === `/api/platform/roles/${role.id}`);
      await deleteButton.click();
      const confirm = adminPage.locator('.ant-popconfirm').getByRole('button', { name: localized(['删除记录', 'Delete record', '删除']) });
      if (await confirm.count()) await confirm.click();
      else await adminPage.locator('.ant-modal-confirm-btns button.ant-btn-primary').click();
      const response = await deleted;
      if ([200, 204].includes(response.status())) {
        assert.equal((await adminContext.request.get(`/api/platform/roles/${role.id}`)).status(), 404, 'Temporary read-only role remains after UI cleanup');
      } else {
        assert.equal(response.status(), 409, 'Temporary read-only role deletion returned an unexpected result');
        const current = await adminContext.request.get(`/api/platform/roles/${role.id}`);
        assert.equal(current.status(), 200);
        retainedOrphanRoles += 1;
      }
      await adminPage.locator('.ant-table').first().waitFor();
    }
  }
  if (retainedOrphanRoles) note(`cleanup: ${retainedOrphanRoles} prior QA role(s) remain assigned after their users were soft-deleted; no UI unassign path exists, parent will remove them with the isolated QA database`);
  else note('cleanup: all active settings QA accounts and roles were removed and verified absent');
  readerCleanupDone = true;
  readerId = null;
  readerRoleId = null;
}

async function restoreSettingsIfNeeded() {
  if (maintenanceChanged && maintenanceSnapshot) {
    await adminPage.goto('/dashboard/maintenance');
    const current = await settingSnapshot(adminContext, 'maintenance');
    for (const id of current.data.allowedRoleIds ?? []) {
      const roleResponse = await adminContext.request.get(`/api/platform/roles/${id}`);
      if (roleResponse.status() === 200) {
        const role = await roleResponse.json();
        await chooseMaintenanceRole(role.code, role.name, false);
      }
    }
    await fillSettingData(adminPage, {
      enabled: maintenanceSnapshot.data.enabled,
      title: maintenanceSnapshot.data.title,
      message: maintenanceSnapshot.data.message,
    });
    await saveSettings(adminPage, 'maintenance');
  }
  if (agreementsChanged && agreementsSnapshot) {
    await adminPage.goto('/dashboard/agreements');
    await adminPage.locator('#data_userAgreementMarkdown').fill(agreementsSnapshot.data.userAgreementMarkdown);
    await adminPage.locator('#data_privacyAgreementMarkdown').fill(agreementsSnapshot.data.privacyAgreementMarkdown);
    await saveSettings(adminPage, 'agreements');
  }
  if (watermarkChanged && watermarkSnapshot) {
    await adminPage.goto('/dashboard/watermark');
    await setSwitch(adminPage.locator('#data_enabled'), Boolean(watermarkSnapshot.data.enabled));
    await adminPage.locator('#data_textLines').fill((watermarkSnapshot.data.textLines ?? []).join('\n'));
    await saveSettings(adminPage, 'watermark');
  }
  if (smsChanged && smsSnapshot) {
    await adminPage.goto('/dashboard/sms');
    await setSwitch(adminPage.locator('#data_enabled'), Boolean(smsSnapshot.data.enabled));
    await adminPage.locator('#data_endpoint').fill(smsSnapshot.data.endpoint ?? '');
    await adminPage.locator('#data_sender').fill(smsSnapshot.data.sender);
    await adminPage.locator('#data_template').fill(smsSnapshot.data.template);
    await adminPage.locator('#data_timeoutSeconds').fill(String(smsSnapshot.data.timeoutSeconds));
    await pickOption(adminPage, adminPage.getByRole('combobox', { name: localized(['访问密钥操作', '存取金鑰操作', 'Access key action']), exact: true }), labels.clearKey);
    await saveSettings(adminPage, 'sms');
  }
}

try {
  await waitHealthy();
  adminPage.on('pageerror', error => { throw new Error(`Browser page error: ${notSecretText(error.message)}`); });
  await loginUI(adminContext, admin.username, admin.password, true);
  if (phase === 'settings-core') {
    await testAgreements();
    await testWatermark();
    await testSms();
    note('settings-core: all UI settings consumers passed and original values restored');
  } else if (phase === 'readonly-settings') {
    await createReadonlyUserRole();
    await testReadonlySettings();
    await cleanupReaderThroughUI();
    note('read-only settings and dictionary consumer passed; temporary account and role removed through UI');
  } else if (phase === 'readonly-dictionary') {
    await createReadonlyUserRole();
    await testReadonlySettings();
    const dictionaryResult = await testDisabledDictionaryValue();
    await cleanupReaderThroughUI();
    note(dictionaryResult.saveStatus === 200
      ? 'dictionary old value: disabled selection remained visible and its unchanged form save passed'
      : `dictionary old value: observed selected-value lookup ${dictionaryResult.lookupStatus}; unchanged form save ${dictionaryResult.saveStatus}${dictionaryResult.saveDetail ? ` (${dictionaryResult.saveDetail})` : ''}; labelVisible=${dictionaryResult.labelVisible}`);
  } else if (phase === 'maintenance') {
    await createReadonlyUserRole();
    await testMaintenanceLast();
    await cleanupReaderThroughUI();
    note('maintenance phase completed and temporary account/role removed through UI');
  } else if (phase === 'all') {
    await testAgreements();
    await testWatermark();
    await testSms();
    await createReadonlyUserRole();
    await testReadonlySettings();
    const dictionaryResult = await testDisabledDictionaryValue();
    await testMaintenanceLast();
    await cleanupReaderThroughUI();
    note(dictionaryResult.saveStatus === 200
      ? 'dictionary old value: disabled selection remained visible and its unchanged form save passed'
      : `dictionary old value: observed selected-value lookup ${dictionaryResult.lookupStatus}; unchanged form save ${dictionaryResult.saveStatus}${dictionaryResult.saveDetail ? ` (${dictionaryResult.saveDetail})` : ''}; labelVisible=${dictionaryResult.labelVisible}`);
    note('overall: browser acceptance scenarios completed; see per-scenario outcome for any observed product defect');
  } else {
    throw new Error('Supported phases: settings-core, readonly-settings, readonly-dictionary, maintenance, or all');
  }
  for (const result of results) console.log(`PASS: ${result}`);
} finally {
  try { await cleanupReaderThroughUI(); } catch { console.error('CLEANUP_REQUIRED: temporary read-only account or role cleanup needs manual UI recovery'); }
  try { await restoreSettingsIfNeeded(); } catch { console.error('RESTORE_REQUIRED: settings restoration failed; isolated QA services remain available for manual UI recovery'); }
  if (smsReceiver?.listening) await new Promise(resolve => smsReceiver.close(resolve));
  if (readerContext) await readerContext.close();
  await adminContext.close();
  await browser.close();
}
