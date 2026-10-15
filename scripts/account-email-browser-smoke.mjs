import {chromium,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const id=randomUUID(),username='qa_email_ui_'+id.replaceAll('-','');
const sql=s=>execFileSync('python3',['-c','import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],{cwd:'scripts',input:s,stdio:['pipe','pipe','pipe']}).toString().trim();
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});
try{
 sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${id}','${username}','Email Browser QA',password_hash FROM platform_user WHERE username='admin'`);
 const csrf=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username,password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);
 const p=await context.newPage(),errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.goto('/dashboard/home');await p.locator('.pro-account-trigger').click();await p.getByRole('menuitem',{name:'个人资料',exact:true}).click();
 await p.waitForURL('**/dashboard/account-security');
 const profile=p.locator('.account-settings-panel');await profile.locator('.account-settings-menu').getByRole('menuitem',{name:'账号安全',exact:true}).click();await p.waitForURL('**/dashboard/account-authentication-security');
 await profile.getByRole('button',{name:'账号邮箱',exact:true}).click();
 const email=p.getByRole('dialog',{name:'账号邮箱',exact:true});
 await expect(email.getByText('已验证邮箱: 尚未验证',{exact:true})).toBeVisible();
 let submissions=0;
 await p.route('**/api/account/email/request',async route=>{
  submissions++;const values=route.request().postDataJSON();assert.equal(values.email,'fixture@example.invalid');assert.equal(values.version,0);assert.equal(values.locale,'zh-CN');
  // Intercept only the producer; no email is enqueued or sent.
  await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'smtpUnavailable',message:'smtpUnavailable'})});
 });
 await email.getByLabel('账号邮箱',{exact:true}).fill('fixture@example.invalid');await email.getByLabel('当前密码',{exact:true}).fill('fixture-password');
 await email.getByRole('button',{name:'发送验证邮件',exact:true}).click();
 await expect(p.locator('.ant-message-notice').filter({hasText:'邮件服务未就绪，请联系管理员配置邮件服务器。'})).toBeVisible();
 await expect(email.getByLabel('当前密码',{exact:true})).toHaveValue('');assert.equal(submissions,1);
 await email.getByRole('button',{name:'关闭',exact:true}).click();await expect(email).not.toBeVisible();
 const code=randomBytes(32).toString('base64url'),hash=createHash('sha256').update(code).digest('hex');
 sql(`INSERT INTO platform_account_email(user_id,pending_email,token_hash,expires_at) VALUES('${id}','${username}@example.invalid','${hash}',CURRENT_TIMESTAMP+INTERVAL '10 minutes')`);
 await profile.getByRole('button',{name:'账号邮箱',exact:true}).click();await expect(email.getByText('待验证邮箱:',{exact:false})).toBeVisible();
 await email.getByLabel('邮件验证码',{exact:true}).fill(code);
 await email.getByRole('button',{name:'确认邮箱',exact:true}).click();await expect(p.locator('.ant-message-notice').filter({hasText:'邮箱验证成功。'})).toBeVisible();
 await expect(email.getByText('已验证邮箱: '+username+'@example.invalid',{exact:true})).toBeVisible();
 await email.getByRole('button',{name:'关闭',exact:true}).click();await expect(email).not.toBeVisible();await expect(profile).toBeVisible();
 await p.reload();await p.locator('.pro-account-trigger').click();await p.getByRole('menuitem',{name:'个人资料',exact:true}).click();await p.waitForURL('**/dashboard/account-security');await profile.locator('.account-settings-menu').getByRole('menuitem',{name:'账号安全',exact:true}).click();await p.waitForURL('**/dashboard/account-authentication-security');await profile.getByRole('button',{name:'账号邮箱',exact:true}).click();
 await expect(email.getByText('已验证邮箱: '+username+'@example.invalid',{exact:true})).toBeVisible();
 await p.setViewportSize({width:390,height:844});await expect.poll(()=>p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 assert.deepEqual(errors,[]);console.log('PASS: account security email drawer, request failure feedback/password clearing, real single-use confirmation, reload persistence and mobile bounds; no email sent');
}finally{
 try{const csrf=await(await context.request.get('/api/auth/csrf')).json();await context.request.post('/api/auth/logout',{headers:{[csrf.headerName]:csrf.token}});}finally{await browser.close();sql(`DELETE FROM platform_audit WHERE actor='${username}'; DELETE FROM platform_user WHERE id='${id}'; DELETE FROM platform_rate_bucket WHERE bucket_key='email-verify:${createHash('sha256').update(username).digest('hex')}'`);}
}
