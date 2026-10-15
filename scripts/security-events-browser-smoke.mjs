import {chromium,expect} from '@playwright/test';
import {readFileSync,mkdirSync} from 'node:fs';import {execFileSync} from 'node:child_process';import {randomUUID} from 'node:crypto';import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8')),id=randomUUID(),name='qa_security_ui_'+id.replaceAll('-','');
mkdirSync('.runtime/account-settings',{recursive:true});
const sql=s=>execFileSync('python3',['-c','import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],{cwd:'scripts',input:s,stdio:['pipe','pipe','pipe']}).toString().trim();
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886},userAgent:'Lumira Security QA <script>client</script>'});
try{
 sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${id}','${name}','Security UI QA',password_hash FROM platform_user WHERE username='admin'; INSERT INTO platform_audit(id,actor,action,target_id) VALUES(gen_random_uuid(),'${name}','account.emailVerified','${id}')`);
 const t=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:name,password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[t.headerName]:t.token}})).status(),200);
 const p=await context.newPage(),errors=[];p.on('pageerror',e=>errors.push(e.message));await p.goto('/dashboard/home');await expect(p.getByRole('main')).toBeVisible();await p.locator('.pro-account-trigger').click();await p.getByRole('menuitem',{name:'个人资料',exact:true}).click();await p.waitForURL('**/dashboard/account-security');
 const profile=p.locator('.account-settings-panel');await profile.locator('.account-settings-menu').getByRole('menuitem',{name:'账号安全',exact:true}).click();await p.waitForURL('**/dashboard/account-authentication-security');await profile.getByRole('button',{name:'安全记录',exact:true}).click();const history=p.getByRole('dialog',{name:'安全记录',exact:true});
 await expect(history.getByText('邮箱验证成功。',{exact:true})).toBeVisible();
 await expect(history.getByText('登录成功',{exact:true})).toBeVisible();await expect(history.getByText('Lumira Security QA <script>client</script>',{exact:true})).toBeVisible();
 const pageSize=history.getByRole('combobox');await pageSize.focus();await pageSize.press('ArrowDown');await expect(pageSize).toHaveAttribute('aria-expanded','true');await pageSize.press('Escape');await expect(pageSize).toHaveAttribute('aria-expanded','false');await expect(history).toBeVisible();
 await p.route('**/api/account/security-events?*',r=>r.fulfill({status:503,contentType:'application/json',body:'{"detail":"unavailable"}'}));
 await history.getByRole('button',{name:'刷新页面',exact:true}).click();await expect(p.locator('.ant-message-notice')).toContainText('服务暂时不可用，请稍后重试');await expect(history.getByText('邮箱验证成功。',{exact:true})).toBeVisible();
 await p.unroute('**/api/account/security-events?*');await history.getByRole('button',{name:'刷新页面',exact:true}).click();await expect(p.locator('.ant-message-notice')).toHaveCount(0); 
 await p.screenshot({path:'.runtime/account-settings/security-events-desktop.png'});
 await p.setViewportSize({width:390,height:844});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 await expect.poll(async()=>{const b=await history.boundingBox();return !!b&&b.x>=0&&b.x+b.width<=390;}).toBe(true);
 await p.screenshot({path:'.runtime/account-settings/security-events-mobile.png'});
 await history.getByRole('button',{name:'关闭',exact:true}).click();await expect(history).not.toBeVisible();await expect(profile).toBeVisible();await expect(p).toHaveURL(/\/dashboard\/account-authentication-security$/);assert.deepEqual(errors,[]);
 console.log('PASS: personal security history, localized event, refresh failure/retry, profile page preservation and mobile bounds');
}finally{try{const t=await(await context.request.get('/api/auth/csrf')).json();await context.request.post('/api/auth/logout',{headers:{[t.headerName]:t.token}});}finally{await browser.close();sql(`DELETE FROM platform_audit WHERE actor='${name}'; DELETE FROM platform_user WHERE id='${id}'`);}}
