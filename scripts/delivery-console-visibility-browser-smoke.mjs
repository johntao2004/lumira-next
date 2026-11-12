import {chromium,expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {loadQaRunContext} from './qa-run-guard.mjs';
const {config,baseURL}=loadQaRunContext();
const sql=statement=>execFileSync('python3',['-c','import sys; from smoke_support import sql; sql(sys.stdin.read())'],{cwd:'scripts',input:statement,stdio:['pipe','pipe','pipe']});
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
try {
 for(const allowed of ['mail','sms']) {
  const user=randomUUID(),role=randomUUID(),username='qa_delivery_tabs_'+randomUUID().replaceAll('-','');
  sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${user}','${username}','Delivery tabs QA',password_hash FROM platform_user WHERE username='admin'; INSERT INTO platform_role(id,code,name,data_scope) VALUES('${role}','${username}','Delivery tabs QA','ALL'); INSERT INTO platform_user_role(user_id,role_id) VALUES('${user}','${role}'); INSERT INTO platform_role_authority(role_id,authority) VALUES('${role}','platform:${allowed}-deliveries:access')`);
  const context=await browser.newContext({baseURL,viewport:{width:1213,height:886}});
  try {
   const csrf=await(await context.request.get('/api/auth/csrf')).json();
   assert.equal((await context.request.post('/api/auth/login',{form:{username,password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);
   const page=await context.newPage(),errors=[];let foreignReads=0;
   const other=allowed==='mail'?'sms':'mail';
   page.on('pageerror',e=>errors.push(e.message));
   page.on('request',request=>{if(request.url().includes('/api/platform/'+other+'-deliveries'))foreignReads++;});
   await page.goto('/dashboard/'+allowed+'-deliveries');
   const main=page.getByRole('main');await expect(main.locator('.resource-table')).toBeVisible();
   await expect(main.getByRole('tab')).toHaveCount(0);
   assert.equal(foreignReads,0,'An unauthorized delivery query was mounted');
   assert.deepEqual(errors,[]);
  } finally {await context.close();}
 }
 console.log('PASS: single mail/SMS grants expose only their permitted delivery page and never mount the other queue query');
} finally {await browser.close();}
