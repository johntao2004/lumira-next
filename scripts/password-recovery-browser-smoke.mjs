import {chromium,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {translate} from '../apps/web/src/platform/i18n/messages.ts';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const id=randomUUID(),username='qa_recovery_ui_'+id.replaceAll('-',''),password=randomBytes(24).toString('base64url');
const sql=s=>execFileSync('python3',['-c','import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],{cwd:'scripts',input:s,stdio:['pipe','pipe','pipe']}).toString().trim();
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:390,height:844}});
try{
 sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${id}','${username}','Recovery UI QA',password_hash FROM platform_user WHERE username='admin'`);
 sql(`INSERT INTO platform_account_email(user_id,email,verified_at) VALUES('${id}','${username}@example.invalid',now())`);
 const code=randomBytes(32).toString('base64url'),hash=createHash('sha256').update(code).digest('hex');
 sql(`INSERT INTO platform_password_reset(user_id,token_hash,password_snapshot,email_version,expires_at,requested_at,request_day,request_count) SELECT id,'${hash}',password_hash,0,now()+interval '10 minutes',now(),CURRENT_DATE,1 FROM platform_user WHERE id='${id}'`);
 const p=await context.newPage(),errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.route('**/api/auth/password-recovery/request',async route=>{
  assert.equal(route.request().postDataJSON().username,username);
  await route.fulfill({status:202,body:''}); // Presentation only; never send external email.
 });
 await p.goto('/user/login');const launch=p.getByRole('button',{name:'忘记密码',exact:true});await launch.click();
 const drawer=p.getByRole('dialog',{name:'忘记密码',exact:true});
 await drawer.getByLabel(translate('zh-CN','username'),{exact:true}).fill(username);await drawer.getByRole('button',{name:'发送重置邮件',exact:true}).click();
 await expect(p.locator('.ant-message-notice')).toContainText('如果账号符合找回条件，验证邮件将进入发送队列，请查收已验证邮箱。');
 await drawer.getByLabel('邮件验证码',{exact:true}).fill(randomBytes(32).toString('base64url'));
 await drawer.getByLabel('新密码',{exact:true}).fill(password);await drawer.getByLabel('确认新密码',{exact:true}).fill(password);
 await drawer.getByRole('button',{name:'重置密码',exact:true}).click();
 await expect(p.locator('.ant-message-notice').filter({hasText:'验证码无效、已过期或尝试次数已用尽。'})).toBeVisible();
 await expect(drawer.getByLabel('新密码',{exact:true})).toHaveValue('');
 await drawer.getByLabel('邮件验证码',{exact:true}).fill(code);await drawer.getByLabel('新密码',{exact:true}).fill(password);await drawer.getByLabel('确认新密码',{exact:true}).fill(password);
 await expect.poll(()=>p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await drawer.getByRole('button',{name:'重置密码',exact:true}).click();await expect(drawer).not.toBeVisible();
 await expect(p.locator('.ant-message-notice').filter({hasText:'密码已重置，请使用新密码登录。'})).toBeVisible();await expect(p.getByLabel(translate('zh-CN','username'),{exact:true})).toHaveValue(username);
 await p.getByLabel('密码',{exact:true}).fill(password);await p.getByRole('button',{name:translate('zh-CN','signIn'),exact:true}).click();await expect(p).toHaveURL(/\/dashboard\/home$/);
 assert.deepEqual(errors,[]);console.log('PASS: recovery request presentation, real invalid/valid proof, password clearing, mobile bounds and new-password login; no email sent');
}finally{
 try{const t=await(await context.request.get('/api/auth/csrf')).json();await context.request.post('/api/auth/logout',{headers:{[t.headerName]:t.token}});}finally{await browser.close();sql(`DELETE FROM platform_audit WHERE actor='${username}'; DELETE FROM platform_user WHERE id='${id}'; DELETE FROM platform_rate_bucket WHERE bucket_key='recovery-user:${createHash('sha256').update(username).digest('hex')}'`);}
}
