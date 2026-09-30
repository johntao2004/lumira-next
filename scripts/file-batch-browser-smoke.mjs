import { chromium, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
const cfg=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const marker=`qa_batch_${randomUUID().slice(0,8)}`;
const evidence='.runtime/qa/file-batch';mkdirSync(evidence,{recursive:true});
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1254,height:869}});
const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
const owned=[];const checks=[];let baseline;
const pass=(name,extra={})=>{checks.push({name,status:'passed',...extra});console.log('PASS',name);};
const responseFor=(id,method,suffix='')=>page.waitForResponse(r=>new URL(r.url()).pathname===`/api/account/files/${id}${suffix}`&&r.request().method()===method);
const visible=()=>page.getByRole('tabpanel');
const row=name=>visible().getByRole('row').filter({hasText:name});
const confirmButton=()=>page.getByRole('dialog').getByRole('button',{name:'批量移入回收站',exact:true});
async function headers(){const t=await(await context.request.get('/api/auth/csrf')).json();return {[t.headerName]:t.token};}
async function readUsage(){const r=await context.request.get('/api/account/files/usage');assert.equal(r.status(),200);return r.json();}
async function cleanup(){
 await page.unrouteAll({behavior:'ignoreErrors'});
 for(const f of owned){
  const r=await context.request.get(`/api/account/files/${f.id}`);if(r.status()===404)continue;assert.equal(r.status(),200);let x=await r.json();
  if(x.state==='READY'){assert.equal((await context.request.delete(`/api/account/files/${f.id}?version=${x.version}`,{headers:await headers()})).status(),204);x=await(await context.request.get(`/api/account/files/${f.id}`)).json();}
  if(x.state==='TRASHED')assert.equal((await context.request.delete(`/api/account/files/${f.id}/purge?version=${x.version}`,{headers:await headers()})).status(),204);
 }
 const end=Date.now()+180000;let usage;
 do {usage=await readUsage();if(owned.length===0||usage.chargedBytes===baseline.chargedBytes&&usage.fileCount===baseline.fileCount)break;await new Promise(r=>setTimeout(r,1000));}while(Date.now()<end);
 if(baseline){assert.equal(usage.chargedBytes,baseline.chargedBytes);assert.equal(usage.fileCount,baseline.fileCount);}
 return {onlyNewSyntheticFileIds:owned.map(f=>f.id),chargedBytesRestored:usage?.chargedBytes===baseline?.chargedBytes,fileCountRestored:usage?.fileCount===baseline?.fileCount};
}
let cleanupEvidence;
try{
 await page.goto('/user/login');await page.getByPlaceholder('用户名').fill(cfg.LUMIRA_BOOTSTRAP_USERNAME??'admin');await page.getByPlaceholder('密码', {exact:true}).fill(cfg.LUMIRA_BOOTSTRAP_PASSWORD);await page.getByRole('button',{name:/登\s*录/}).click();await page.waitForURL('**/dashboard/**');
 await page.goto('/dashboard/ai-console');await page.getByRole('heading',{name:'AI 对话',exact:true}).waitFor();assert.equal(await page.getByText('TypeSafe Jev 仅返回结构化判断结果',{exact:false}).count(),0);pass('targeted AI notice removed');
 await page.goto('/dashboard/files');await page.getByRole('heading',{name:'我的文件',exact:true}).waitFor();baseline=await readUsage();
 await visible().getByRole('button',{name:'批量移入回收站',exact:true}).waitFor();assert.equal(await visible().getByRole('button',{name:'批量移入回收站',exact:true}).isDisabled(),true);pass('empty selection action disabled');
 for(let i=0;i<2;i++){
  await expect(page.getByRole('button',{name:'上传文件',exact:true})).toBeEnabled(); const name=`${marker}_${i}.txt`;const wait=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/account/files'&&r.request().method()==='POST');
  await page.locator('input[type=file]').setInputFiles({name,mimeType:'text/plain',buffer:Buffer.from(`new synthetic batch fixture ${i}`)});
  const r=await wait;assert.equal(r.status(),201);const file=await r.json();owned.push({id:file.id,name});await row(name).waitFor();await expect(page.getByRole('button',{name:'上传文件',exact:true})).toBeEnabled(); console.log('Uploaded owned fixture',i);
 }
 for(const f of owned)await row(f.name).getByRole('checkbox').check();await visible().getByText('已选择 2 个文件',{exact:true}).waitFor();
 await visible().getByRole('button',{name:'批量移入回收站',exact:true}).click();const dialog=page.getByRole('dialog');await dialog.waitFor();for(const f of owned)await dialog.getByText(f.name,{exact:true}).waitFor();await dialog.getByRole('button',{name:/取\s*消/}).click();assert.equal((await(await context.request.get(`/api/account/files/${owned[0].id}`)).json()).state,'READY');pass('two selections and cancel preserve files');
 await visible().getByRole('button',{name:'清空选择',exact:true}).click();assert.equal(await row(owned[0].name).getByRole('checkbox').isChecked(),false);pass('clear selection');
 for(const f of owned)await row(f.name).getByRole('checkbox').check();
 // Corroborate partial failure: one authenticated real write succeeds; the other gets a controlled 409 before it reaches the server.
 const failed=owned[1];let observedVersion=false;
 const matcher=`**/api/account/files/${failed.id}?*`;
 await page.route(matcher,async route=>{if(route.request().method()==='DELETE'){observedVersion=new URL(route.request().url()).searchParams.has('version');await route.fulfill({status:409,contentType:'application/json',body:JSON.stringify({detail:'staleRecord'})});}else await route.continue();});
 const first=responseFor(owned[0].id,'DELETE');await visible().getByRole('button',{name:'批量移入回收站',exact:true}).click();await confirmButton().click();assert.equal((await first).status(),204);
 await visible().getByRole('alert').getByText('已完成 1 个，失败 1 个。请检查以下文件后重试。',{exact:true}).waitFor();await visible().getByRole('alert').getByText(failed.name,{exact:false}).waitFor();assert.equal(await row(failed.name).getByRole('checkbox').isChecked(),true);assert.equal(observedVersion,true);assert.equal((await(await context.request.get(`/api/account/files/${failed.id}`)).json()).state,'READY');
 await page.screenshot({path:`${evidence}/partial-failure.png`});pass('partial success, visible error, failed selection retained, version included');
 await page.unroute(matcher);const retried=responseFor(failed.id,'DELETE');await visible().getByRole('button',{name:'批量移入回收站',exact:true}).click();await confirmButton().click();assert.equal((await retried).status(),204);await row(failed.name).waitFor({state:'hidden'});pass('failed item retry succeeds');
 await page.reload();await page.getByRole('tab',{name:'回收站',exact:true}).click();for(const f of owned)await row(f.name).waitFor();for(const f of owned)await row(f.name).getByRole('checkbox').check();await visible().getByText('已选择 2 个文件',{exact:true}).waitFor();await page.screenshot({path:`${evidence}/trash-multiselect.png`});pass('trash persists on reload and allows multiselect');
 const waits=owned.map(f=>responseFor(f.id,'POST','/restore'));await visible().getByRole('button',{name:'批量恢复',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'批量恢复',exact:true}).click();for(const r of await Promise.all(waits))assert.equal(r.status(),204);for(const f of owned)await row(f.name).waitFor({state:'hidden'});pass('bulk restore two real files');
 await page.reload();for(const f of owned)await row(f.name).waitFor();for(const f of owned)assert.equal((await(await context.request.get(`/api/account/files/${f.id}`)).json()).state,'READY');pass('restored files persist on reload');
 await row(owned[0].name).getByRole('checkbox').check();await page.getByRole('tab',{name:'回收站',exact:true}).click();await page.getByRole('tab',{name:'文件',exact:true}).click();assert.equal(await row(owned[0].name).getByRole('checkbox').isChecked(),false);pass('switching tabs clears selection');
 await row(owned[0].name).getByRole('checkbox').check();await page.setViewportSize({width:390,height:844});await page.waitForTimeout(700);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>document.documentElement.clientWidth),false);await visible().getByRole('button',{name:'批量移入回收站',exact:true}).click();const rect=await page.getByRole('dialog').evaluate(e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,w:innerWidth,h:innerHeight};});assert.ok(Math.abs(rect.y+rect.height/2-rect.h/2)<2);await page.screenshot({path:`${evidence}/mobile-confirmation.png`});await page.getByRole('dialog').getByRole('button',{name:/取\s*消/}).click();pass('390px page no overflow and confirmation centered',rect);
 assert.deepEqual(errors,[]);pass('no browser page errors');
}finally{
 try{cleanupEvidence=await cleanup();pass('only owned synthetic files cleaned and quota restored');}finally{
  try{assert.equal((await context.request.post('/api/auth/logout',{headers:await headers()})).status(),204);}finally{await context.request.dispose();await context.close();await browser.close();}
  writeFileSync(`${evidence}/results.json`,JSON.stringify({recordedAt:new Date().toISOString(),environment:'main preview 8010/8090',checks,cleanup:cleanupEvidence,errors},null,2)+'\n');
 }
}
