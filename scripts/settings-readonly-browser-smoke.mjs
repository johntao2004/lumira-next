import {chromium} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const roleId=randomUUID();
const id=randomUUID(),username='qa_readonly_'+id.replaceAll('-','');
const sql=statement=>execFileSync('python3',['-c','import sys; from smoke_support import sql; sql(sys.stdin.read())'],{cwd:'scripts',input:statement,stdio:['pipe','pipe','pipe']});
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const created=[];
async function login(context,name){const csrf=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:name,password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);}
async function write(context,path,method,data){const csrf=await(await context.request.get('/api/auth/csrf')).json();return context.request.fetch(path,{method,data,headers:{[csrf.headerName]:csrf.token}});}
try{
 const admin=await browser.newContext({baseURL:'http://127.0.0.1:8010'});await login(admin,'admin');
 for(const section of ['branding','agreements','watermark','maintenance']){
  const path='/api/platform/settings/'+section;const snapshot=await(await admin.request.get(path)).json();
  if(snapshot.version===0){assert.equal((await write(admin,path,'PUT',{...snapshot,changeReason:'Readonly browser fixture'})).status(),200);created.push(section);}
 }
 sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${id}','${username}','Readonly QA',password_hash FROM platform_user WHERE username='admin'; INSERT INTO platform_authority VALUES('${id}','platform:settings:read'); INSERT INTO platform_role(id,code,name,data_scope) VALUES('${roleId}','qa_readonly_${roleId}','Readonly picker QA','SELF'); INSERT INTO platform_role_authority VALUES('${roleId}','platform:settings:write'); INSERT INTO platform_user_role VALUES('${id}','${roleId}'); INSERT INTO platform_role_authority SELECT '${roleId}',authority FROM (VALUES ('platform:users:read'),('platform:users:write'),('platform:roles:read'),('platform:roles:write'),('platform:organizations:read'),('platform:organizations:write'),('platform:dictionaries:read'),('platform:dictionaries:write')) permissions(authority);`);
 const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});await login(context,username);const identity=await(await context.request.get('/api/auth/me')).json();assert.ok(identity.authorities.includes('platform:settings:write'));assert.ok(!identity.globalAuthorities.includes('platform:settings:write'));assert.ok(identity.globalAuthorities.includes('platform:settings:read'));const page=await context.newPage();
 for(const section of ['branding','agreements','watermark','maintenance']){
  const path='/api/platform/settings/'+section;const snapshot=await(await context.request.get(path)).json();
  await page.goto('/dashboard/'+section);await page.locator('#changeReason').waitFor();
  assert.equal(await page.locator('#changeReason').isDisabled(),true);
  assert.equal(await page.locator('button[type="submit"]').isDisabled(),true);
  for(const control of await page.locator('[id^="data_"]').all()){
   if(await control.evaluate(el=>['INPUT','TEXTAREA','BUTTON','SELECT'].includes(el.tagName)))assert.equal(await control.isDisabled(),true);
  }
  if(section==='maintenance'){const search=page.locator('.resource-table').getByRole('textbox').first();assert.equal(await search.isEnabled(),true);await search.fill('Readonly picker QA');await search.press('Enter');const row=page.getByRole('row').filter({hasText:'Readonly picker QA'});await row.waitFor();assert.equal(await row.getByRole('checkbox').isDisabled(),true);}
  assert.equal((await write(context,path,'PUT',{...snapshot,changeReason:'Must reject'})).status(),403);
  await page.getByRole('button',{name:'配置历史',exact:true}).click();
  await page.getByRole('button',{name:'详情',exact:true}).first().click();
  assert.equal(await page.locator(`#restore-${section}_changeReason`).count(),0);
  for(const control of await page.locator(`[id^="history-${section}_data_"]`).all()){if(await control.evaluate(el=>['INPUT','TEXTAREA','BUTTON','SELECT'].includes(el.tagName)))assert.equal(await control.isDisabled(),true);}
  assert.equal(await page.locator(`button[form="restore-${section}"]`).count(),0);
  assert.equal((await write(context,path+'/rollback','POST',{version:snapshot.version,sourceVersion:snapshot.version,changeReason:'Must reject'})).status(),403);
  assert.equal((await(await admin.request.get(path)).json()).version,snapshot.version);
 }
 for(const [section,label] of [['users','新增用户'],['roles','新增角色'],['organizations','新增组织'],['dictionaries','新增字典']]){await page.goto('/dashboard/'+section);await page.locator('.ant-table').first().waitFor();assert.equal(await page.getByRole('button',{name:label,exact:true}).count(),0);}
 sql(`UPDATE platform_role SET data_scope='ALL' WHERE id='${roleId}';`);
 assert.ok((await(await context.request.get('/api/auth/me')).json()).globalAuthorities.includes('platform:settings:write'));
 await page.goto('/dashboard/branding');await page.locator('#changeReason').waitFor();assert.equal(await page.locator('#changeReason').isEnabled(),true);
 sql(`UPDATE platform_role SET data_scope='SELF' WHERE id='${roleId}';`);
 await page.reload();await page.locator('#changeReason').waitFor();assert.equal(await page.locator('#changeReason').isDisabled(),true);
 console.log('PASS: SELF writer stays read-only; ALL promotion and scope revocation update current session/UI; read-only account opens all four settings sections and history; fields/save/rollback disabled; direct writes and rollback denied without version changes');
}finally{
 sql(`DELETE FROM spring_session WHERE principal_name='${username}'; DELETE FROM platform_user WHERE id='${id}'; DELETE FROM platform_role WHERE id='${roleId}';`);
 for(const section of created)sql(`DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='${section}'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='${section}'); DELETE FROM platform_setting WHERE section='${section}';`);
 await browser.close();
}
