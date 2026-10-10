import { chromium, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

const config = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
let owner = randomUUID();
let username = `qa_diffuse_avatar_${owner.replaceAll('-', '')}`;
const pluginId = process.env.DIFFUSE_AVATAR_PLUGIN_ID ?? 'diffuse-avatar';
const adminStorageState = process.env.DIFFUSE_AVATAR_ADMIN_STORAGE_STATE;
const qaFixturePath = process.env.DIFFUSE_AVATAR_QA_FIXTURE;
const qaFixture = qaFixturePath ? JSON.parse(readFileSync(qaFixturePath, 'utf8')) : null;
if (qaFixture) {
  assert.match(qaFixture.owner, /^[a-f0-9-]{36}$/i, 'QA fixture owner must be a UUID');
  assert.match(qaFixture.username, /^[A-Za-z0-9_]{1,64}$/, 'QA fixture username must be safe for cleanup SQL');
  assert.ok(qaFixture.storageState, 'QA fixture must include a Playwright storageState');
  owner = qaFixture.owner;
  username = qaFixture.username;
}
const artifacts = '.runtime/diffuse-avatar';
const sql = statement => execFileSync(
  'python3',
  ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],
  { cwd: 'scripts', input: statement, stdio: ['pipe', 'pipe', 'pipe'] },
).toString().trim();
const uploadedPng = execFileSync('python3', ['-c', String.raw`import sys,struct,zlib
def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d))
sys.stdout.buffer.write(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',2,1,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\xff\x00\x00\xff\x00\xff\x00\xff'))+chunk(b'IEND',b''))`]);
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
let adminContext;
let qaContext;
let adminPage;
let originalModuleEnabled;
let qaUserCreated = Boolean(qaFixture);
let adminHeaders = {};
let qaHeaders = {};

async function login(context, name, password) {
  const token = await (await context.request.get('/api/auth/csrf')).json();
  const response = await context.request.post('/api/auth/login', {
    form: { username: name, password },
    headers: { [token.headerName]: token.token },
  });
  assert.equal(response.status(), 200, `Login failed for ${name}`);
}

async function authorizationHeaders(context) {
  const response = await context.request.get('/api/auth/me');
  assert.equal(response.status(), 200, 'Current session must be available to read its authorization revision');
  const identity = await response.json();
  const revision = identity.authorizationContext?.revision;
  return typeof revision === 'string' && revision.length > 0
    ? { 'X-Lumira-Authorization-Context-Revision': revision }
    : {};
}

async function avatar(context, headers = {}) {
  const response = await context.request.get('/api/account/avatar', { headers });
  assert.equal(response.status(), 200, 'Account avatar metadata must be readable');
  return response.json();
}

async function avatarPng(context, version, headers = {}) {
  const response = await context.request.get(`/api/account/avatar/content?version=${version}`, { headers });
  assert.equal(response.status(), 200, 'The selected account avatar must be served');
  assert.match(response.headers()['content-type'] ?? '', /^image\/png(?:;|$)/i);
  const body = await response.body();
  assert.deepEqual(body.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  return body;
}

async function moduleState() {
  const response = await adminContext.request.get('/api/platform/modules', { headers: adminHeaders });
  assert.equal(response.status(), 200, 'Admin must be able to read module state');
  const modules = await response.json();
  const module = modules.find(item => item.id === pluginId);
  assert.ok(module, `Module ${pluginId} must be present`);
  return module;
}

async function moduleRow() {
  await adminPage.goto('/dashboard/modules');
  await adminPage.getByRole('tab', { name: '可管理模块', exact: true }).click();
  const row = adminPage.getByRole('row').filter({ hasText: pluginId });
  await expect(row).toBeVisible();
  return row;
}

async function toggleModule(enabled) {
  const actionName = enabled ? '启用模块' : '停用模块';
  const row = await moduleRow();
  await row.getByRole('button', { name: actionName, exact: true }).click();
  const dialog = adminPage.getByRole('dialog', { name: actionName, exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('变更立即影响新请求和页面入口；不会删除已有数据。', { exact: true })).toBeVisible();
  const response = adminPage.waitForResponse(result =>
    new URL(result.url()).pathname === `/api/platform/modules/${pluginId}/state`
      && result.request().method() === 'PUT');
  await dialog.getByRole('button', { name: /确\s*认/ }).click();
  assert.equal((await response).status(), 200, `Module ${pluginId} toggle must succeed`);
  await expect(dialog).not.toBeVisible();
  await expect.poll(async () => (await moduleState()).enabled).toBe(enabled);
}

async function openAvatarEditor(page) {
  await page.locator('.pro-account-trigger').click();
  await page.getByRole('menuitem', { name: '个人资料', exact: true }).click();
  const editor = page.getByRole('region', { name: '头像', exact: true });
  await editor.waitFor();
  return editor;
}

try {
  mkdirSync(artifacts, { recursive: true });
  adminContext = await browser.newContext({
    baseURL: 'http://127.0.0.1:8010',
    locale: 'zh-CN',
    viewport: { width: 1213, height: 886 },
    ...(adminStorageState ? { storageState: adminStorageState } : {}),
  });
  if (!adminStorageState) await login(adminContext, 'admin', config.LUMIRA_BOOTSTRAP_PASSWORD);
  adminHeaders = await authorizationHeaders(adminContext);

  const pluginResponse = await adminContext.request.get('/api/platform/modules/plugins', { headers: adminHeaders });
  assert.equal(pluginResponse.status(), 200, 'Admin must be able to read plugin state');
  const plugins = await pluginResponse.json();
  const plugin = plugins.find(item => item.id === pluginId);
  assert.ok(plugin, `Plugin ${pluginId} must be installed before this focused smoke`);
  assert.equal(plugin.installed, true, 'Diffuse avatar plugin must be installed');
  assert.equal(plugin.enabled, true, 'Diffuse avatar plugin must start enabled');
  originalModuleEnabled = (await moduleState()).enabled;
  assert.equal(originalModuleEnabled, true, 'Diffuse avatar module must start enabled');

  if (!qaFixture) {
    sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${owner}','${username}','Diffuse Avatar QA',password_hash FROM platform_user WHERE username='admin'`);
  }
  qaUserCreated = true;
  qaContext = await browser.newContext({
    baseURL: 'http://127.0.0.1:8010',
    locale: 'zh-CN',
    viewport: { width: 1213, height: 886 },
    ...(qaFixture ? { storageState: qaFixture.storageState } : {}),
  });
  if (!qaFixture) await login(qaContext, username, config.LUMIRA_BOOTSTRAP_PASSWORD);
  qaHeaders = await authorizationHeaders(qaContext);
  const page = await qaContext.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto('/dashboard/home');

  let metadata = await avatar(qaContext, qaHeaders);
  assert.equal(metadata.fileId, null, 'The temporary QA account must begin without an uploaded avatar');
  assert.equal(metadata.generated, true, 'Enabled diffuse avatar plugin must generate an avatar');
  let generatedPng = await avatarPng(qaContext, metadata.version, qaHeaders);
  assert.equal(generatedPng.readUInt32BE(16), 256, 'Generated avatar width must be 256 pixels');
  assert.equal(generatedPng.readUInt32BE(20), 256, 'Generated avatar height must be 256 pixels');
  const headerAvatar = page.locator('.pro-account-trigger img[alt="头像"]');
  await expect(headerAvatar).toBeVisible();
  await page.waitForFunction(() => {
    const image = document.querySelector('.pro-account-trigger img[alt="头像"]');
    return image instanceof HTMLImageElement && image.complete && image.naturalWidth === 256;
  });
  await page.screenshot({ path: `${artifacts}/generated.png` });

  await page.reload();
  await page.waitForFunction(() => {
    const image = document.querySelector('.pro-account-trigger img[alt="头像"]');
    return image instanceof HTMLImageElement && image.complete && image.naturalWidth === 256;
  });
  const refreshedMetadata = await avatar(qaContext, qaHeaders);
  const refreshedPng = await avatarPng(qaContext, refreshedMetadata.version, qaHeaders);
  assert.deepEqual(refreshedPng, generatedPng, 'Generated avatar colors must remain stable after reload');
  const editor = await openAvatarEditor(page);
  await expect(page.locator('img[alt="头像"]')).toHaveCount(2);
  await expect(editor.getByRole('button', { name: '移除头像', exact: true })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(async () => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();
  await page.screenshot({ path: `${artifacts}/profile-generated-390.png`, fullPage: true });

  adminContext.setDefaultTimeout(10_000);
  adminPage = await adminContext.newPage();
  adminPage.on('pageerror', error => pageErrors.push(error.message));
  await adminPage.goto('/dashboard/modules');
  await adminPage.getByRole('tab', { name: '可管理模块', exact: true }).click();
  const moduleActionRow = adminPage.getByRole('row').filter({ hasText: pluginId });
  await expect(moduleActionRow.getByRole('button', { name: '停用模块', exact: true })).toBeVisible();
  await adminPage.setViewportSize({ width: 390, height: 844 });
  await expect.poll(async () => adminPage.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy();
  await toggleModule(false);
  await expect.poll(async () => (await avatar(qaContext, qaHeaders)).generated).toBe(false);
  await expect.poll(async () => (await avatar(qaContext, qaHeaders)).fileId).toBeNull();
  await expect(page.locator('img[alt="头像"]')).toHaveCount(0);
  await expect(page.getByRole('region', { name: '头像', exact: true })).toBeVisible();
  await page.screenshot({ path: `${artifacts}/disabled.png` });

  await toggleModule(true);
  await expect.poll(async () => (await avatar(qaContext, qaHeaders)).generated).toBe(true);
  await expect.poll(async () => (await avatar(qaContext, qaHeaders)).fileId).toBeNull();
  await page.waitForFunction(() => [...document.querySelectorAll('img[alt="头像"]')].length === 2
    && [...document.querySelectorAll('img[alt="头像"]')].every(image => image.complete && image.naturalWidth === 256));
  await expect(editor.getByRole('button', { name: '移除头像', exact: true })).toHaveCount(0);
  await adminPage.screenshot({ path: `${artifacts}/modules-390.png`, fullPage: true });
  assert.equal(await adminPage.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false, 'Module management must not overflow at 390px');

  const uploadChooser = page.waitForEvent('filechooser');
  await editor.getByRole('button', { name: '更换头像', exact: true }).click();
  const uploadResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/account/avatar' && response.request().method() === 'POST');
  await (await uploadChooser).setFiles({ name: 'diffuse-avatar-override.png', mimeType: 'image/png', buffer: uploadedPng });
  assert.equal((await uploadResponse).status(), 200, 'Avatar upload must succeed');
  await expect.poll(async () => (await avatar(qaContext, qaHeaders)).generated).toBe(false);
  metadata = await avatar(qaContext, qaHeaders);
  assert.ok(metadata.fileId, 'Uploaded avatar must take priority over generated avatar');
  assert.equal(metadata.generated, false, 'Uploaded avatar must not be marked as generated');
  const uploadedBody = await avatarPng(qaContext, metadata.version, qaHeaders);
  assert.notDeepEqual(uploadedBody, generatedPng, 'Uploaded avatar must replace generated image content');
  await page.waitForFunction(() => [...document.querySelectorAll('img[alt="头像"]')].length === 2
    && [...document.querySelectorAll('img[alt="头像"]')].every(image => image.complete && image.naturalWidth === 2));
  await expect(editor.getByRole('button', { name: '移除头像', exact: true })).toBeVisible();

  await editor.getByRole('button', { name: '移除头像', exact: true }).click();
  const confirm = page.locator('.ant-popconfirm');
  await expect(confirm).toBeVisible();
  const removedResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/account/avatar' && response.request().method() === 'DELETE');
  await confirm.getByRole('button', { name: '移除头像', exact: true }).click();
  assert.equal((await removedResponse).status(), 200, 'Uploaded avatar removal must succeed');
  await expect.poll(async () => avatar(qaContext, qaHeaders)).toMatchObject({ fileId: null, generated: true });
  await expect(editor.getByRole('button', { name: '移除头像', exact: true })).toHaveCount(0);
  metadata = await avatar(qaContext, qaHeaders);
  const restoredPng = await avatarPng(qaContext, metadata.version, qaHeaders);
  assert.deepEqual(restoredPng, generatedPng, 'Removing uploaded avatar must restore the same generated colors');
  await page.screenshot({ path: `${artifacts}/restored.png` });

  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false, 'Profile must not overflow at 390px');
  assert.deepEqual(pageErrors, [], `Unexpected browser page errors: ${pageErrors.join('\n')}`);
  console.log(`PASS: ${pluginId} generates a stable PNG; real module UI toggles update header and profile immediately; uploads override generation and removal restores it; 390px module screenshot saved under ${artifacts}`);
} finally {
  try {
    if (adminContext && originalModuleEnabled !== undefined) {
      try {
        const current = await moduleState();
        if (current.enabled !== originalModuleEnabled) {
          const csrf = await (await adminContext.request.get('/api/auth/csrf')).json();
          const restore = await adminContext.request.put(`/api/platform/modules/${pluginId}/state`, {
            data: { enabled: originalModuleEnabled, version: current.stateVersion, reason: 'Restore state after diffuse avatar browser QA' },
            headers: { ...adminHeaders, [csrf.headerName]: csrf.token },
          });
          assert.equal(restore.status(), 200, 'Module state must be restored after the focused smoke');
        }
      } finally {
        if (adminContext) {
          const csrf = await (await adminContext.request.get('/api/auth/csrf')).json();
          await adminContext.request.post('/api/auth/logout', { headers: { ...adminHeaders, [csrf.headerName]: csrf.token } });
        }
      }
    }
  } finally {
    try {
      if (qaUserCreated) {
        const ids = sql(`SELECT id FROM platform_file WHERE owner_id='${owner}'`).split('\n').filter(Boolean);
        sql(`DELETE FROM platform_account_media WHERE user_id='${owner}'`);
        for (const id of ids) {
          assert.match(id, /^[a-f0-9-]{36}$/);
          execFileSync('python3', [
            '-c',
            'from pathlib import Path; import sys; key=sys.argv[1]; [(Path(".runtime/files")/key[:2]/(key+suffix)).unlink(missing_ok=True) for suffix in [".blob",".upload"]]',
            id,
          ]);
        }
        sql(`DELETE FROM platform_file WHERE owner_id='${owner}'; DELETE FROM platform_audit WHERE actor='${username}'; DELETE FROM spring_session WHERE principal_name='${username}'; DELETE FROM platform_user WHERE id='${owner}'`);
      }
    } finally {
      await adminPage?.close();
      await qaContext?.close();
      await adminContext?.close();
      await browser.close();
    }
  }
}
