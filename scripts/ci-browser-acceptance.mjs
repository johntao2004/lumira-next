/** 在一次性 CI 部署上验证真实登录和权限组件，不访问已有或生产数据。
 * Verify real login and permission components in disposable CI, never saved or production data. */
import assert from 'node:assert/strict';
import { verifyDynamicPlugins } from './ci-browser-dynamic-plugins.mjs';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { chromium, expect as baseExpect } from '@playwright/test';
const expect = baseExpect.configure({ timeout: 20000 });

assert.equal(process.env.GITHUB_ACTIONS, 'true', 'Only a disposable GitHub CI runner is supported');
const config = JSON.parse(readFileSync(process.env.LUMIRA_BROWSER_CI_CONFIG, 'utf8'));
assert.equal(config.runId, process.env.GITHUB_RUN_ID);
const baseURL = 'http://127.0.0.1:8010';
assert.ok(['immutable','dynamic'].includes(config.mode));
const work = `.runtime/browser-ci/${config.mode}`;
const out = `${work}/evidence`;
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
  await page.getByRole('menuitem', {name:'退出登录',exact:true}).click();
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
  assert.equal((await write(admin,'/api/platform/users','GET')).status(),401);
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
  stage = 'logout-after-bootstrap';
  await uiLogout(page);
  stage = 'normal-login-after-bootstrap';
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
  assert.equal((await write(member,'/api/platform/users','GET')).status(),403);
  assert.equal((await write(member,'/api/platform/modules','GET')).status(),403);
  await record('ordinary user cannot render admin controls or read direct APIs',memberPage);
  stage='office-http-and-scoped-export';
  const exportInput={scope:'filtered',page:1,pageSize:100,search:'',fields:['username'],filters:{},filterModes:{},fieldLabels:{username:'Username'},statusLabels:{true:'Enabled',false:'Disabled'}};
  // 解析真实 worker 返回的 XLSX，不能只凭状态码或 ZIP 魔数宣称导出成功。
  // Parse the real worker XLSX; status or ZIP magic alone does not establish a valid export.
  async function spreadsheet(response) {
    assert.equal(response.status(),200);
    const data=await response.body();
    assert.ok(data.length>0);
    return JSON.parse(execFileSync('python3',['-c',`import io,json,sys,zipfile,xml.etree.ElementTree as E
with zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read())) as z:
 assert all(name in z.namelist() for name in ['[Content_Types].xml','xl/workbook.xml','xl/_rels/workbook.xml.rels','xl/worksheets/sheet1.xml'])
 ns={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
 shared=[]
 if 'xl/sharedStrings.xml' in z.namelist():
  shared=[''.join(n.text or '' for n in item.iter() if n.tag.endswith('}t')) for item in E.fromstring(z.read('xl/sharedStrings.xml'))]
 rows=[]
 for row in E.fromstring(z.read('xl/worksheets/sheet1.xml')).findall('.//s:row',ns):
  cells=[]
  for cell in row.findall('s:c',ns):
   value=cell.find('s:v',ns)
   if cell.get('t')=='s': text=shared[int(value.text)]
   elif cell.get('t')=='inlineStr': text=''.join(n.text or '' for n in cell.iter() if n.tag.endswith('}t'))
   else: text=value.text if value is not None else ''
   cells.append(text)
  rows.append(cells)
 print(json.dumps(rows))`],{input:data,timeout:15000,encoding:'utf8'}));
  }
  const importFields=await write(admin,'/api/platform/users/import/fields','GET');assert.equal(importFields.status(),200);
  const importKeys=(await importFields.json()).map(field=>field.key);assert.ok(importKeys.includes('username'));
  assert.deepEqual(await spreadsheet(await write(admin,'/api/platform/users/import/template.xlsx','GET')),[importKeys]);
  const fullExport=await spreadsheet(await write(admin,'/api/platform/users/export.xlsx','POST',exportInput));
  assert.deepEqual(fullExport[0],['Username']);
  assert.ok(fullExport.some(row=>row[0]==='admin') && fullExport.some(row=>row[0]===username));
  assert.equal((await write(member,'/api/platform/users/export.xlsx','POST',exportInput)).status(),403);
  const exporterResponse=await write(admin,'/api/platform/roles','POST',{code:`${username}_export`,name:'CI Self Export',enabled:true,dataScope:'SELF',authorities:['platform:users:access','platform:users:export']});
  assert.equal(exporterResponse.status(),201);const exporter=await exporterResponse.json();
  assert.equal((await write(admin,`/api/platform/users/${user.id}/roles`,'PUT',{version:(await (await write(admin,`/api/platform/users/${user.id}`,'GET')).json()).version,roleIds:[exporter.id]})).status(),200);
  const ownExport=await spreadsheet(await write(member,'/api/platform/users/export.xlsx','POST',exportInput));
  assert.deepEqual(ownExport,[['Username'],[username]]);
  await record('real Office template and XLSX exports succeed; SELF export excludes admin and ordinary export is denied');
  const roleResponse=await write(admin,'/api/platform/roles','POST',{code:username,name:'CI Module Reader',enabled:true,dataScope:'ALL',authorities:['platform:modules:access']});
  assert.equal(roleResponse.status(),201);const role=await roleResponse.json();
  assert.equal((await write(admin,`/api/platform/users/${user.id}/roles`,'PUT',{version:(await (await write(admin,`/api/platform/users/${user.id}`,'GET')).json()).version,roleIds:[role.id]})).status(),200);
  await memberPage.goto('/dashboard/modules');
  await expect(memberPage.getByRole('tab',{name:'可管理模块',exact:true})).toBeVisible();
  await memberPage.getByRole('tab',{name:'可管理模块',exact:true}).click();
  await expect(memberPage.locator('.modules-card').filter({hasText:'feedback'}).getByRole('switch')).toBeDisabled();
  await expect(memberPage.getByRole('button',{name:'卸载插件',exact:true})).toHaveCount(0);
  assert.equal((await write(member,'/api/platform/modules','GET')).status(),200);
  await record('read-only role grant is visible in existing browser session',memberPage);
  assert.equal((await write(admin,`/api/platform/users/${user.id}/roles`,'PUT',{version:(await (await write(admin,`/api/platform/users/${user.id}`,'GET')).json()).version,roleIds:[]})).status(),200);
  await memberPage.reload();
  await expect(memberPage.getByRole('tab',{name:'可管理模块',exact:true})).toHaveCount(0);
  assert.equal((await write(member,'/api/platform/modules','GET')).status(),403);
  await record('role removal removes UI and direct API access after refresh',memberPage);
  if(config.mode === 'dynamic') {
    stage='dynamic-plugin-lifecycle';currentPage=page;
    await verifyDynamicPlugins({admin,page,member,write,record});
  }
  stage='session-revocation';
  assert.equal((await write(admin,`/api/platform/online-users/user/${user.id}`,'DELETE')).status(),200);
  await memberPage.reload();await expect(memberPage).toHaveURL(/\/user\/login/);
  assert.equal((await member.request.get('/api/auth/me')).status(),401);
  await record('admin session revocation rejects existing browser',memberPage);
  if(config.mode === 'immutable') {
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
  }
  writeFileSync(`${out}/results.json`,JSON.stringify({status:'passed',checks},null,2));
} catch(error) {
  // 不序列化可能含凭据的错误正文、请求、HTML 或 trace。
  // Do not serialize arbitrary errors, request bodies, HTML or network traces containing credentials.
  writeFileSync(`${out}/results.json`,JSON.stringify({status:'failed',stage,checks,errorType:error.name},null,2));
  if(currentPage) await currentPage.screenshot({path:`${out}/failure.png`,fullPage:true,mask:[currentPage.locator('input'),currentPage.locator('textarea')]}).catch(()=>{});
  for(const [index,failedPage] of contexts.flatMap(ctx=>ctx.pages()).entries()) {
    await failedPage.screenshot({path:`${out}/failure-page-${index}.png`,fullPage:true,mask:[failedPage.locator('input'),failedPage.locator('textarea')]}).catch(()=>{});
  }
  console.error(`Browser acceptance failed at ${stage}; ${error.name}`);
  process.exitCode=1;
} finally {
  for(const [index,ctx] of contexts.entries()) {
    await ctx.tracing.stop({path:`${work}/private-trace-${index}.zip`});
    await ctx.close();
  }
  await browser.close();
}
