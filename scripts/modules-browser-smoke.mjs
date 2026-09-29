import {chromium,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});let original;
async function write(path,data){const t=await(await context.request.get('/api/auth/csrf')).json();return context.request.put(path,{data,headers:{[t.headerName]:t.token}});}
async function state(){return (await(await context.request.get('/api/platform/modules')).json()).find(m=>m.id==='audit-console');}
async function restore(enabled){const row=await state();assert.equal((await write('/api/platform/modules/audit-console/state',{enabled,version:row.stateVersion,reason:'Browser module verification restore'})).status(),200);}
try{
 const token=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[token.headerName]:token.token}})).status(),200);
 original=(await state()).enabled;await restore(true);
 const p=await context.newPage(),other=await context.newPage(),errors=[];for(const page of [p,other])page.on('pageerror',e=>errors.push(e.message));
 await other.goto('/dashboard/audit');await expect(other.getByRole('table')).toBeVisible();
 await p.goto('/dashboard/modules');const row=p.getByRole('row').filter({hasText:'audit-console'});await expect(row).toBeVisible();
 assert.equal(await row.getByRole('button',{name:'停用模块',exact:true}).count(),1);
 async function toggle(label){
  await row.getByRole('button',{name:label,exact:true}).click();const d=p.getByRole('dialog',{name:label,exact:true});await d.getByLabel('修改原因',{exact:true}).fill('Browser module verification');
  const response=p.waitForResponse(r=>r.url().endsWith('/api/platform/modules/audit-console/state')&&r.request().method()==='PUT');await d.locator('button[type="submit"]').click();assert.equal((await response).status(),200);await expect(d).not.toBeVisible();
 }
 await toggle('停用模块');await expect(other.getByText('模块当前不可用',{exact:true})).toBeVisible();
 assert.equal((await context.request.get('/api/platform/audit')).status(),503);
 await other.reload();await expect(other.getByText('模块当前不可用',{exact:true})).toBeVisible();await expect(other.locator('aside').getByText('操作审计',{exact:true})).not.toBeVisible();
 await toggle('启用模块');await expect(other.getByRole('table')).toBeVisible();assert.equal((await context.request.get('/api/platform/audit')).status(),200);
 await row.getByRole('button',{name:'模块历史',exact:true}).click();const history=p.getByRole('dialog',{name:'模块历史',exact:true});
 await expect(history.getByText('Browser module verification',{exact:true}).first()).toBeVisible();
 await history.getByRole('button',{name:'刷新页面',exact:true}).click();await expect(history.getByRole('table')).toBeVisible();await history.getByRole('button',{name:'关闭',exact:true}).click();await expect(history).not.toBeVisible();
 await p.setViewportSize({width:390,height:844});await row.getByRole('button',{name:'停用模块',exact:true}).click();const d=p.getByRole('dialog',{name:'停用模块',exact:true});
 await expect.poll(async()=>{const b=await d.boundingBox();return b&&b.x>=0&&b.x+b.width<=390;}).toBeTruthy();await p.screenshot({path:'.runtime/modules-mobile.png'});await d.getByRole('button',{name:'关闭',exact:true}).click();
 await row.getByRole('button',{name:'模块历史',exact:true}).click();await expect(history).toBeVisible();await expect.poll(async()=>{const b=await history.boundingBox();return b&&b.x>=0&&b.x+b.width<=390;}).toBeTruthy();await history.locator('.ant-table-content').evaluate(e=>{e.scrollLeft=e.scrollWidth;});await p.screenshot({path:'.runtime/module-history-mobile.png'});await history.getByRole('button',{name:'关闭',exact:true}).click();assert.deepEqual(errors,[]);
 console.log('PASS: module toggle UI, cross-tab capability update, direct-route denial/reload, re-enable, core protection, history refresh and mobile drawers');
}finally{try{if(original!==undefined)await restore(original);const t=await(await context.request.get('/api/auth/csrf')).json();await context.request.post('/api/auth/logout',{headers:{[t.headerName]:t.token}});}finally{await browser.close();}}
