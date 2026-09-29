import {chromium} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const context=await browser.newContext({baseURL:'http://127.0.0.1:8010'});let changed=false;
try{
 const csrf=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);
 assert.equal((await(await context.request.get('/api/platform/settings/agreements')).json()).version,0,'Refuse to overwrite configured agreements');
 const page=await context.newPage();await page.goto('/dashboard/agreements');
 await page.locator('#data_userAgreementMarkdown').fill('# Agreement QA\n\n**Important**\n\n<script>window.agreementScriptRan=true</script>\n\n[Unsafe](javascript:alert(1))');
 await page.locator('#data_privacyAgreementMarkdown').fill('# Privacy QA\n\nPrivacy text.');
 await page.locator('#changeReason').fill('Agreement browser verification');
 const saved=page.waitForResponse(r=>r.url().endsWith('/api/platform/settings/agreements')&&r.request().method()==='PUT');await page.locator('button[type="submit"]').click();assert.equal((await saved).status(),200);changed=true;
 const anonymous=await browser.newContext({baseURL:'http://127.0.0.1:8010'});const login=await anonymous.newPage();await login.goto('/user/login');
 await login.getByRole('button',{name:'用户协议',exact:true}).click();await login.getByRole('heading',{name:'Agreement QA',exact:true}).waitFor();
 assert.equal(await login.locator('strong').filter({hasText:'Important'}).count(),1);
 assert.equal(await login.evaluate(()=>window.agreementScriptRan),undefined);
 assert.equal(await login.locator('a[href^="javascript:"]').count(),0);
 await login.getByRole('button',{name:'关闭',exact:true}).click();await login.getByRole('button',{name:'隐私协议',exact:true}).click();await login.getByRole('heading',{name:'Privacy QA',exact:true}).waitFor();
 await anonymous.close();console.log('PASS: agreement form save, anonymous links, Markdown headings/emphasis, privacy dialog and executable-content exclusion');
}finally{
 if(changed)execFileSync('python3',['-c',`from smoke_support import sql; sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='agreements'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='agreements'); DELETE FROM platform_setting WHERE section='agreements'")`],{cwd:'scripts',stdio:'pipe'});
 await browser.close();
}
