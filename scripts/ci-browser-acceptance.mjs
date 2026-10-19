/** 在一次性 CI 部署上验证真实登录和权限组件，不访问已有或生产数据。
 * Verify real login and permission components in disposable CI, never saved or production data. */
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { chromium, expect as baseExpect } from '@playwright/test';
const expect = baseExpect.configure({ timeout: 20000 });

assert.equal(process.env.GITHUB_ACTIONS, 'true', 'Only a disposable GitHub CI runner is supported');
const config = JSON.parse(readFileSync(process.env.LUMIRA_BROWSER_CI_CONFIG, 'utf8'));
assert.equal(config.runId, process.env.GITHUB_RUN_ID);
const baseURL = 'http://127.0.0.1:8010';
const out = '.runtime/browser-ci/evidence';
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const contexts = [];
const checks = [];
let stage = 'startup';
let currentPage;
const secret = () => `Aa9!${randomBytes(24).toString('base64url')}`;
const adminPassword = secret();
const userPassword = secret();
async function context() {
  const value = await browser.newContext({ baseURL, locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  await value.addCookies([{ name: 'lumira-next.preferences', value: encodeURIComponent(JSON.stringify({locale:'zh-CN'})), url:baseURL }]);
  // 原始 trace 包含合成凭据，只允许上传独立 evidence 目录。
  // Raw traces contain synthetic credentials. Keep local only; artifact upload MUST allowlist evidence/.
  await value.tracing.start({screenshots:true,snapshots:true,sources:false});
  contexts.push(value);
  return value;
}
async function write(ctx, path, method, data) {
  const csrf = await (await ctx.request.get('/api/auth/csrf')).json();
  const me = await ctx.request.get('/api/auth/me');
  const identity = me.ok() ? await me.json() : null;
  const revision = identity?.authorizationContext?.revision;
  return ctx.request.fetch(path, { method, data, headers: {
    [csrf.headerName]: csrf.token,
    ...(revision ? {'X-Lumira-Authorization-Context-Revision':revision}:{}),
  }});
}
async function uiLogin(page, name, password) {
  await page.goto('/user/login');
  await page.getByLabel('用户名', {exact:true}).fill(name);
  await page.getByLabel('密码', {exact:true}).fill(password);
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login' && r.request().method() === 'POST');
  await page.getByRole('button', {name:/^登\s*录$/}).click();
  return (await response).json();
}
async function uiLogout(page) {
  await page.locator('.pro-account-trigger').click();
  await page.getByRole('menuitem', {name:'退出',exact:true}).click();
  await expect(page).toHaveURL(/\/user\/login/);
}
async function record(name, page) {
  checks.push(name);
  if (page) await page.screenshot({path:`${out}/${String(checks.length).padStart(2,'0')}.png`, fullPage:true, mask:[page.locator('input')]});
}
try {
  const admin = await context();
  const page = await admin.newPage(); currentPage = page;
  stage = 'first-login-cannot-bypass';
  const pending = await uiLogin(page, 'admin', config.bootstrapPassword);
  assert.equal(pending.state, 'PASSWORD_CHANGE_REQUIRED');
  const challengeId = pending.challengeId;
  assert.ok(challengeId);
  await expect(page.getByLabel('新密码',{exact:true})).toBeVisible();
  assert.equal((await admin.request.get('/api/auth/me')).status(),401);
  assert.equal((await admin.request.get('/api/platform/users')).status(),401);
  await page.goto('/dashboard/users');
  await expect(page).toHaveURL(/\/user\/login/);
  await expect(page.getByLabel('新密码',{exact:true})).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('新密码',{exact:true})).toBeVisible();
  const cancelled = page.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login/cancel' && r.request().method() === 'POST');
  await page.getByRole('button',{name:'暂不设置，取消登录',exact:true}).click();
  assert.equal((await cancelled).status(),204);
  await expect(page.getByLabel('新密码',{exact:true})).toBeHidden();
  const restarted = await uiLogin(page,'admin',config.bootstrapPassword);
  assert.equal(restarted.state,'PASSWORD_CHANGE_REQUIRED');
  await record('first-login gate survives direct route, reload and cancellation',page);
  stage = 'first-login-complete';
  await page.getByLabel('新密码',{exact:true}).fill(adminPassword);
  await page.getByLabel('确认新密码',{exact:true}).fill(adminPassword);
  await page.getByRole('button',{name:'完成密码设置',exact:true}).click();
  await expect(page).toHaveURL(/\/dashboard\//);
  await expect(page.locator('.pro-account-trigger')).toBeVisible();
  // 已消费的挑战不能再次完成登录。
  // Challenge replay must not produce another successful login transition.
  const replayPassword=secret();
  const replay = await write(admin,'/api/auth/login/password-change','POST',{challengeId:restarted.challengeId,newPassword:replayPassword,confirmPassword:replayPassword});
  assert.ok([400,401,403,409].includes(replay.status()), `Replay status ${replay.status()}`);
  await uiLogout(page);
  const normal = await uiLogin(page,'admin',adminPassword);
  assert.equal(normal.state,'COMPLETE');
  await expect(page).toHaveURL(/\/dashboard\//);
  await expect(page.getByLabel('新密码',{exact:true})).toHaveCount(0);
  // 仅本次合成凭据，不进入结果或上传附件。
  // Only synthetic credentials, never returned in evidence or uploaded artifacts.
  config.currentAdminPassword = adminPassword;
  writeFileSync(process.env.LUMIRA_BROWSER_CI_CONFIG,JSON.stringify(config),{mode:0o600});
  await record('bootstrap completes once; UI logout and normal login',page);
  stage = 'user-editor-persistence';
  await page.goto('/dashboard/users');
  await page.getByRole('button',{name:'新增用户',exact:true}).click();
  const form=page.locator('#user-editor');
  const username=`qa_browser_${randomBytes(5).toString('hex')}`;
  await form.locator('[id$="_username"]').fill(username);
  await form.locator('[id$="_displayName"]').fill(username);
  await form.locator('[id$="_password"]').fill(userPassword);
  await form.locator('[id$="_confirmPassword"]').fill(userPassword);
  const createdResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/platform/users'&&r.request().method()==='POST');
  await page.locator('button[form="user-editor"]').click();
  const created=await createdResponse;assert.equal(created.status(),201);
  const user=await created.json();
  await expect(form).toBeHidden();await page.reload();
  await page.getByRole('row').filter({hasText:username}).getByRole('button',{name:'编辑',exact:true}).click();
  await form.locator('[id$="_displayName"]').fill(`${username}_updated`);
  const edited=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/platform/users/${user.id}/editor`&&r.request().method()==='PUT');
  await page.locator('button[form="user-editor"]').click();assert.equal((await edited).status(),200);
  await expect(form).toBeHidden();await page.reload();
  await expect(page.getByRole('row').filter({hasText:`${username}_updated`})).toBeVisible();
  await record('user creation and edit through UI persist after refresh',page);
  stage='role-navigation-and-denial';
  const member=await context();const memberPage=await member.newPage();currentPage=memberPage;
  assert.equal((await uiLogin(memberPage,username,userPassword)).state,'COMPLETE');
  await expect(memberPage).toHaveURL(/\/dashboard\//);
  await memberPage.goto('/dashboard/users');
  await expect(memberPage.getByRole('button',{name:'新增用户',exact:true})).toHaveCount(0);
  assert.equal((await member.request.get('/api/platform/users')).status(),403);
  assert.equal((await member.request.get('/api/platform/modules')).status(),403);
  await record('ordinary user cannot render admin controls or read direct APIs',memberPage);
  const roleResponse=await write(admin,'/api/platform/roles','POST',{code:username,name:'CI Module Reader',enabled:true,dataScope:'ALL',authorities:['platform:modules:access']});
  assert.equal(roleResponse.status(),201);const role=await roleResponse.json();
  assert.equal((await write(admin,`/api/platform/users/${user.id}/roles`,'PUT',{version:(await (await write(admin,`/api/platform/users/${user.id}`,'GET')).json()).version,roleIds:[role.id]})).status(),200);
  await memberPage.goto('/dashboard/modules');
  await expect(memberPage.getByRole('tab',{name:'可管理模块',exact:true})).toBeVisible();
  await memberPage.getByRole('tab',{name:'可管理模块',exact:true}).click();
  await expect(memberPage.locator('.modules-card').filter({hasText:'feedback'}).getByRole('switch')).toBeDisabled();
  await expect(memberPage.getByRole('button',{name:'卸载插件',exact:true})).toHaveCount(0);
  assert.equal((await member.request.get('/api/platform/modules')).status(),200);
  await record('read-only role grant is visible in existing browser session',memberPage);
  assert.equal((await write(admin,`/api/platform/users/${user.id}/roles`,'PUT',{version:(await (await write(admin,`/api/platform/users/${user.id}`,'GET')).json()).version,roleIds:[]})).status(),200);
  await memberPage.reload();
  await expect(memberPage.getByRole('tab',{name:'可管理模块',exact:true})).toHaveCount(0);
  assert.equal((await member.request.get('/api/platform/modules')).status(),403);
  await record('role removal removes UI and direct API access after refresh',memberPage);
  stage='session-revocation';
  assert.equal((await write(admin,`/api/platform/online-users/user/${user.id}`,'DELETE')).status(),200);
  await memberPage.reload();await expect(memberPage).toHaveURL(/\/user\/login/);
  assert.equal((await member.request.get('/api/auth/me')).status(),401);
  await record('admin session revocation rejects existing browser',memberPage);
  stage='fixed-graph-plugin-lifecycle-denied';currentPage=page;
  await page.goto('/dashboard/modules');
  await page.getByRole('tab',{name:'可管理模块',exact:true}).click();
  const card=page.locator('.modules-card').filter({hasText:'feedback'});
  await expect(card).toBeVisible();
  await expect(card.getByRole('switch')).toBeDisabled();
  await expect(card.getByRole('button',{name:'卸载插件',exact:true})).toBeDisabled();
  const plan=await write(admin,'/api/platform/modules/plugins/feedback/uninstall-plan','POST',{});
  assert.equal(plan.status(),200);
  const blockedPlan=await plan.json();
  assert.ok(blockedPlan.blockers.includes('pluginFixedGraph'));
  const denied=await write(admin,'/api/platform/modules/plugins/feedback/uninstall','POST',{revision:blockedPlan.revision,dataMode:'PRESERVE'});
  assert.equal(denied.status(),409);
  await page.reload();
  await expect(card.getByRole('button',{name:'卸载插件',exact:true})).toBeDisabled();
  await record('immutable production graph blocks switch and uninstall through UI and API',page);
  writeFileSync(`${out}/results.json`,JSON.stringify({status:'passed',checks},null,2));
} catch(error) {
  // 不序列化可能含凭据的错误正文、请求、HTML 或 trace。
  // Do not serialize arbitrary errors, request bodies, HTML or network traces containing credentials.
  writeFileSync(`${out}/results.json`,JSON.stringify({status:'failed',stage,checks,errorType:error.name},null,2));
  if(currentPage) await currentPage.screenshot({path:`${out}/failure.png`,fullPage:true,mask:[currentPage.locator('input'),currentPage.locator('textarea')]}).catch(()=>{});
  console.error(`Browser acceptance failed at ${stage}; ${error.name}`);
  process.exitCode=1;
} finally {
  for(const [index,ctx] of contexts.entries()) {
    await ctx.tracing.stop({path:`.runtime/browser-ci/private-trace-${index}.zip`});
    await ctx.close();
  }
  await browser.close();
}
