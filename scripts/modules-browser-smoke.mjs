import {chromium,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});let original,authorizationHeaders={};
async function write(path,data){const t=await(await context.request.get('/api/auth/csrf')).json();return context.request.put(path,{data,headers:{...authorizationHeaders,[t.headerName]:t.token}});}
async function state(){const response=await context.request.get('/api/platform/modules',{headers:authorizationHeaders});assert.equal(response.status(),200,'Module state must be read after successful login and with the current authorization context');const modules=await response.json();assert.ok(Array.isArray(modules),'Authenticated module list response must be an array');const feedback=modules.find(m=>m.id==='feedback');assert.ok(feedback,'Feedback module must be present');return feedback;}
async function restore(enabled){const row=await state();assert.equal((await write('/api/platform/modules/feedback/state',{enabled,version:row.stateVersion,reason:'Browser module verification restore'})).status(),200);}
try{
 const token=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[token.headerName]:token.token}})).status(),200);
 const authMe=await(await context.request.get('/api/auth/me')).json();if(authMe.authorizationContext?.rolePreviewSupported&&authMe.authorizationContext.revision)authorizationHeaders={'X-Lumira-Authorization-Context-Revision':authMe.authorizationContext.revision};
 original=(await state()).enabled;await restore(true);
 const p=await context.newPage(),other=await context.newPage(),errors=[];for(const page of [p,other])page.on('pageerror',e=>errors.push(e.message));
 await other.goto('/dashboard/feedback');await expect(other.getByRole('table')).toBeVisible();
 await p.goto('/dashboard/modules');await p.getByRole('tab',{name:'可管理模块',exact:true}).click();const row=p.getByRole('row').filter({hasText:'feedback'});await expect(row).toBeVisible();
 assert.equal(await row.getByRole('button',{name:'停用模块',exact:true}).count(),1);
 async function requestToggle(label){
  await row.getByRole('button',{name:label,exact:true}).click();const d=p.getByRole('dialog',{name:label,exact:true});await expect(d).toBeVisible();
  await expect(d.getByText('变更立即影响新请求和页面入口；不会删除已有数据。',{exact:true})).toBeVisible();
  assert.equal(await d.locator('#changeReason,textarea').count(),0,'Module confirmation must not ask for a change reason');
  return d;
 }
 const cancelled=await requestToggle('停用模块');const cancelledRequest=p.waitForRequest(r=>r.url().endsWith('/api/platform/modules/feedback/state')&&r.method()==='PUT',{timeout:500}).catch(()=>null);await cancelled.getByRole('button',{name:/取\s*消/}).click();assert.equal(await cancelledRequest,null,'Cancelling module confirmation must not send a state request');await expect(cancelled).not.toBeVisible();
 await expect(row.getByRole('button',{name:'停用模块',exact:true})).toBeVisible();
 async function toggle(label){
  const before=await state(),d=await requestToggle(label);const request=p.waitForRequest(r=>r.url().endsWith('/api/platform/modules/feedback/state')&&r.method()==='PUT'),response=p.waitForResponse(r=>r.url().endsWith('/api/platform/modules/feedback/state')&&r.request().method()==='PUT');
  await d.getByRole('button',{name:/确\s*认/}).click();const submitted=await request;assert.deepEqual(submitted.postDataJSON(),{enabled:label==='启用模块',version:before.stateVersion});
  assert.equal((await response).status(),200);await expect(d).not.toBeVisible();
 }
 const planResponse=p.waitForResponse(r=>new URL(r.url()).pathname==='/api/platform/modules/plugins/feedback/uninstall-plan'&&r.request().method()==='POST');await row.getByRole('button',{name:'卸载插件',exact:true}).click();assert.equal((await planResponse).status(),200);
 const planDrawer=p.getByRole('dialog',{name:'卸载插件',exact:true}).first();await expect(planDrawer.getByRole('radio',{name:'仅卸载插件，保留数据'})).toBeChecked();
 const uninstallRequests=[];const captureUninstall=request=>{if(request.url().endsWith('/api/platform/modules/plugins/feedback/uninstall')&&request.method()==='POST')uninstallRequests.push(request);};p.on('request',captureUninstall);
 await planDrawer.getByRole('button',{name:/确\s*认/}).click();const preserveConfirm=p.getByRole('dialog',{name:'卸载插件',exact:true}).last();await expect(preserveConfirm.getByText('仅卸载插件，保留数据',{exact:true})).toBeVisible();
 await preserveConfirm.getByRole('button',{name:/取\s*消/}).click();await p.waitForTimeout(150);p.off('request',captureUninstall);assert.equal(uninstallRequests.length,0,'Cancelling the preserve-data confirmation must not uninstall the plugin');await planDrawer.locator('.ant-drawer-close').click();
 await toggle('停用模块');await expect(other.getByText('模块当前不可用',{exact:true})).toBeVisible();
 assert.equal((await context.request.get('/api/feedback',{headers:authorizationHeaders})).status(),404);
 await other.reload();await expect(other.getByText('模块当前不可用',{exact:true})).toBeVisible();await expect(other.locator('aside').getByText('操作审计',{exact:true})).not.toBeVisible();
 await toggle('启用模块');await expect(other.getByRole('table')).toBeVisible();assert.equal((await context.request.get('/api/feedback',{headers:authorizationHeaders})).status(),200);
 await p.setViewportSize({width:390,height:844});const d=await requestToggle('停用模块');
 await expect.poll(async()=>{const b=await d.boundingBox();return b&&b.x>=0&&b.x+b.width<=390;}).toBeTruthy();await p.screenshot({path:'.runtime/modules-mobile.png'});await d.getByRole('button',{name:/取\s*消/}).click();
 assert.equal(await p.getByRole('button',{name:'模块历史',exact:true}).count(),0);assert.deepEqual(errors,[]);
 console.log('PASS: module action confirmation and cancel, versioned toggle request, cross-tab capability update, direct-route denial/reload, re-enable, core protection and mobile confirmation');
}finally{try{if(original!==undefined){await restore(original);assert.equal((await state()).enabled,original,'Feedback module must return to its original enabled state');}const t=await(await context.request.get('/api/auth/csrf')).json();await context.request.post('/api/auth/logout',{headers:{[t.headerName]:t.token}});}finally{await browser.close();}}
