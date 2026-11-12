import {chromium,expect} from '@playwright/test';
import {loadQaRunContext} from './qa-run-guard.mjs';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {observeResponse} from './browser-support.mjs';
const {config,baseURL}=loadQaRunContext();
const sql=s=>execFileSync('python3',['-c','import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],{cwd:'scripts',input:s,stdio:['pipe','pipe','pipe']}).toString().trim();
const ids=[randomUUID(),randomUUID(),randomUUID()];
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
try{
 for(let i=0;i<ids.length;i++)sql(`INSERT INTO platform_mail_delivery(id,payload,state,attempts,expires_at) VALUES('${ids[i]}','v1:fixture:abcdef','${['FAILED','ACCEPTED','UNCERTAIN'][i]}',1,now()+interval '1 hour')`);

 sql(`UPDATE platform_mail_delivery SET available_at=now()+interval '1 hour' WHERE id IN (${ids.map(id=>`'${id}'`).join(',')})`);
 const context=await browser.newContext({baseURL,viewport:{width:1213,height:886}});
 const token=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[token.headerName]:token.token}})).status(),200);
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/dashboard/mail-deliveries');
 const main=page.getByRole('main');await expect(main.locator(`[data-row-key="${ids[0]}"]`)).toBeVisible();
 const form=main.locator('form.resource-search');
  const expand=form.getByText('展开',{exact:true});if(await expand.isVisible())await expand.click();
  await form.getByLabel('状态',{exact:true}).click();await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText('投递失败',{exact:true}).click();
  await form.getByRole('button',{name:/^查\s*询$/}).click();
 await expect(main.locator(`[data-row-key="${ids[1]}"]`)).toHaveCount(0);await expect(main.locator(`[data-row-key="${ids[0]}"]`)).toBeVisible();
 const search=form.getByLabel('目标标识',{exact:true});await search.fill(ids[0]);await form.getByRole('button',{name:/^查\s*询$/}).click();await expect(main.locator('tbody tr[data-row-key]')).toHaveCount(1);
 await page.route('**/api/platform/mail-deliveries?**',route=>route.fulfill({status:503,contentType:'application/json',body:'{"detail":"unavailable"}'}));
 const failure=observeResponse(page,r=>r.url().includes('/api/platform/mail-deliveries?'));await main.locator('.ant-pro-table-list-toolbar .anticon-reload').click();assert.equal((await failure).status(),503);
 await expect(page.locator('.ant-message-notice')).toBeVisible();await expect(main.locator(`[data-row-key="${ids[0]}"]`)).toBeVisible();
 await page.unroute('**/api/platform/mail-deliveries?**');await main.locator('.ant-pro-table-list-toolbar .anticon-reload').click();await expect(page.locator('.ant-message-notice')).toHaveCount(0);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>document.documentElement.clientWidth),false);assert.deepEqual(errors,[]);
 console.log('PASS: mail status rows, filter, task-ID search, failed refresh preserves rows, successful retry, no pageerror or desktop overflow; no messages sent');
}finally{await browser.close();sql(`DELETE FROM platform_mail_delivery WHERE id IN (${ids.map(id=>`'${id}'`).join(',')})`);}
