import {chromium} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const context=await browser.newContext({baseURL:'http://127.0.0.1:8010'});let changed=false;const prefix=`qa_picker_${Date.now()}`;let fixtures=false;
try{
 const csrf=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);
 assert.equal((await(await context.request.get('/api/platform/settings/maintenance')).json()).version,0,'Refuse to overwrite configured maintenance');
 execFileSync('python3',['-c',`from smoke_support import sql; import sys; prefix=sys.argv[1]; sql("INSERT INTO platform_role(id,code,name) SELECT gen_random_uuid(),'"+prefix+"_'||n,'"+prefix+" Role '||lpad(n::text,2,'0') FROM generate_series(1,12) n")`,prefix],{cwd:'scripts',stdio:'pipe'});fixtures=true;
 const page=await context.newPage();await page.goto('/dashboard/maintenance');await page.locator('#data_enabled').click();
 const roleSearch=page.locator('.resource-table').getByRole('textbox').first();await roleSearch.fill(prefix);await roleSearch.press('Enter');
 await page.getByRole('row').filter({hasText:prefix+' Role 01'}).getByRole('checkbox').check();
 await page.locator('.ant-pagination-next').click();await page.getByRole('row').filter({hasText:prefix+' Role 11'}).getByRole('checkbox').check();
 await page.locator('.ant-pagination-prev').click();assert.equal(await page.getByRole('row').filter({hasText:prefix+' Role 01'}).getByRole('checkbox').isChecked(),true);
 await page.locator('#data_title').fill('Maintenance browser QA');await page.locator('#data_message').fill('Temporary maintenance notice');
 await page.locator('#data_endAt').fill('2099-01-02T03:04');await page.locator('#changeReason').fill('Maintenance browser enable');
 const saved=page.waitForResponse(r=>r.url().endsWith('/api/platform/settings/maintenance')&&r.request().method()==='PUT');await page.locator('button[type="submit"]').click();const result=await saved;assert.equal(result.status(),200);changed=true;assert.equal((await result.json()).data.allowedRoleIds.length,2);
 assert.equal((await result.json()).data.endAt,await page.evaluate(()=>new Date('2099-01-02T03:04').toISOString().replace('.000Z','Z')));
 const anonymous=await browser.newContext({baseURL:'http://127.0.0.1:8010'});const login=await anonymous.newPage();await login.goto('/user/login');await login.getByText('Maintenance browser QA',{exact:true}).waitFor();await login.getByText('Temporary maintenance notice',{exact:true}).waitFor();
 await page.reload();await page.locator('#data_endAt').waitFor();assert.equal(await page.locator('#data_endAt').inputValue(),'2099-01-02T03:04');
 await page.locator('#data_enabled').click();await page.locator('#changeReason').fill('Maintenance browser disable');const disabled=page.waitForResponse(r=>r.url().endsWith('/api/platform/settings/maintenance')&&r.request().method()==='PUT');await page.locator('button[type="submit"]').click();assert.equal((await disabled).status(),200);
 await login.reload();await login.getByRole('heading').waitFor();assert.equal(await login.getByText('Maintenance browser QA',{exact:true}).count(),0);
 await anonymous.close();console.log('PASS: role search/cross-page selection and persistence, maintenance UI save, local-time roundtrip, anonymous notice, reload and disable');
}finally{
 if(changed)execFileSync('python3',['-c',`from smoke_support import sql; sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='maintenance'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='maintenance'); DELETE FROM platform_setting WHERE section='maintenance'")`],{cwd:'scripts',stdio:'pipe'});
 if(fixtures)execFileSync('python3',['-c',`from smoke_support import sql; import sys; sql("DELETE FROM platform_role WHERE code LIKE '"+sys.argv[1]+"_%'")`,prefix],{cwd:'scripts',stdio:'pipe'});
 await browser.close();
}
