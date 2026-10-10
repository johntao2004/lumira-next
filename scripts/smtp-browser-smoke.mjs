import {chromium,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {observeResponse} from './browser-support.mjs';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const sql=statement=>execFileSync('python3',['-c','import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],{cwd:'scripts',input:statement,stdio:['pipe','pipe','pipe']}).toString().trim();
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
let created;const readerId=randomUUID(),readerName='qa_smtp_ui_'+readerId.replaceAll('-','');
try{
 const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});
 const csrf=await(await context.request.get('/api/auth/csrf')).json();
 assert.equal((await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);
 const initial=await(await context.request.get('/api/platform/settings/smtp')).json();assert.equal(initial.version,0,'Refuse to overwrite configured SMTP');assert.equal(initial.encryptionConfigured,true);
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('/dashboard/smtp');await page.locator('#data_host').fill('smtp.example.test');
 await page.locator('#data_username').fill('smtp-browser');await page.locator('#data_fromAddress').fill('noreply@example.test');
 async function choose(scope,label){await scope.getByRole('combobox',{name:'密码操作',exact:true}).click();await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText(label,{exact:true}).click();}
 const main=page.getByRole('main');await choose(main,'更换密码');await main.getByLabel('密码',{exact:true}).fill('smtp-browser-fixture');
 assert.equal(await main.locator('#changeReason').count(),0,'SMTP form must not ask for a change reason');
 async function save(status=200){const response=observeResponse(page,r=>r.url().endsWith('/api/platform/settings/smtp')&&r.request().method()==='PUT');await main.getByRole('button',{name:'保存',exact:true}).click();assert.equal((await response).status(),status);}
 await save();created=sql("SELECT id FROM platform_setting WHERE section='smtp'");
 await expect(main.getByText('密码已配置',{exact:true})).toBeVisible();await expect(main.getByLabel('密码',{exact:true})).toHaveCount(0);
 await page.reload();await expect(main.getByText('密码已配置',{exact:true})).toBeVisible();assert.equal(await main.locator('#data_host').inputValue(),'smtp.example.test');
 await main.locator('#data_host').fill('other.example.test');await save(400);await expect(page.locator('.ant-message-notice').filter({hasText:'服务器、端口或账号已变更，请更换或清除密码。'})).toBeVisible();
 await choose(main,'清除密码');await save();await expect(main.getByText('密码未配置',{exact:true})).toBeVisible();
 await main.getByRole('button',{name:'配置历史',exact:true}).click();
 await page.getByRole('row').filter({hasText:'SMTP browser first'}).getByRole('button',{name:'详情',exact:true}).click();
 const restore=page.locator('#restore-smtp');await choose(restore,'更换密码');await restore.getByLabel('密码',{exact:true}).fill('smtp-browser-restored');assert.equal(await restore.locator('#restore-smtp_changeReason').count(),0,'SMTP restore form must not ask for a change reason');
 const response=observeResponse(page,r=>r.url().endsWith('/api/platform/settings/smtp/rollback'));await page.locator('button[form="restore-smtp"]').click();assert.equal((await response).status(),200);
 await page.reload();await expect(main.getByText('密码已配置',{exact:true})).toBeVisible();assert.equal(await main.locator('#data_host').inputValue(),'smtp.example.test');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>document.documentElement.clientWidth),false);assert.deepEqual(errors,[]);
 sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${readerId}','${readerName}','SMTP Reader',password_hash FROM platform_user WHERE username='admin'; INSERT INTO platform_authority(user_id,authority) VALUES('${readerId}','platform:settings:read')`);
 const readonly=await browser.newContext({baseURL:'http://127.0.0.1:8010'});
 const rt=await(await readonly.request.get('/api/auth/csrf')).json();
 assert.equal((await readonly.request.post('/api/auth/login',{form:{username:readerName,password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[rt.headerName]:rt.token}})).status(),200);
 const rp=await readonly.newPage();await rp.goto('/dashboard/smtp');await expect(rp.locator('#data_host')).toBeDisabled();await expect(rp.getByRole('main').getByRole('button',{name:'保存',exact:true})).toBeDisabled();
 await rp.getByRole('main').getByRole('button',{name:'配置历史',exact:true}).click();await rp.getByRole('row').filter({hasText:'SMTP browser first'}).getByRole('button',{name:'详情',exact:true}).click();await expect(rp.locator('button[form="restore-smtp"]')).toHaveCount(0);
 console.log('PASS: SMTP page replacement/reset, reload, destination rejection, clear, history restoration with explicit password, read-only save/restore disabled, no pageerror or desktop overflow; no mail sent');
}finally{
 await browser.close();
 sql(`DELETE FROM spring_session WHERE principal_name='${readerName}'; DELETE FROM platform_user WHERE id='${readerId}'`);
 if(created)sql(`DELETE FROM platform_secret WHERE secret_context='settings:${created}:smtp-password'; DELETE FROM platform_setting_revision WHERE setting_id='${created}'; DELETE FROM platform_audit WHERE target_id='${created}'; DELETE FROM platform_setting WHERE id='${created}'`);
}
