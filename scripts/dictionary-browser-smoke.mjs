import {observeResponse} from "./browser-support.mjs";
import {loadQaRunContext} from "./qa-run-guard.mjs";
import {chromium,expect} from '@playwright/test';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
const {config,baseURL}=loadQaRunContext();
async function run(format) {
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const context=await browser.newContext({baseURL,viewport:{width:1213,height:886}});
const page=await context.newPage();const code=`qa_browser_${Date.now()}`;let id;
const csrf=await (await context.request.get('/api/auth/csrf')).json();
try {
 const login=await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}});assert.equal(login.status(),200);
 await page.goto('/dashboard/dictionaries');
 await page.getByRole('button',{name:'导入字典',exact:true}).click();
 const drawer=page.getByRole('dialog');
 await drawer.locator('input[id="dictionary-file-import_code"]').fill(code);
 await drawer.locator('input[id="dictionary-file-import_name"]').fill('Browser file QA');
 const file=format==='txt'?{name:'dictionary.txt',mimeType:'text/plain',buffer:Buffer.from('itemValue\titemLabel\nalpha\tAlpha\nbeta\tBeta\n')}:{name:`tree.${format}`,mimeType:'application/octet-stream',buffer:readFileSync(`backend/target/browser-fixtures/tree.${format}`)};
 await drawer.locator('input[type="file"]').setInputFiles({...file,name:'invalid.txt',buffer:Buffer.from('itemValue\titemLabel\n\nbad\t\n')});
 await drawer.getByRole('button',{name:'预览并校验',exact:true}).click();
 await drawer.getByRole('cell',{name:'itemLabel',exact:true}).waitFor();
 assert.equal(await drawer.getByRole('button',{name:/保.*存/}).isDisabled(),true);
 if(format!=="txt"){await drawer.getByRole("combobox",{name:"\u5b57\u5178\u7ed3\u6784"}).click();await page.getByText("\u6811\u5f62",{exact:true}).last().click();}
 await drawer.locator('input[type="file"]').setInputFiles(file);
 // Hold the request to verify the same accessible name while loading, not just at rest.
 let release;const gate=new Promise(resolve=>{release=resolve;});
 const previewUrl='**/api/platform/dictionaries/file-preview?*';
 const hold=async route=>{await gate;await route.continue();};
 await page.route(previewUrl,hold);
 const previewButton=drawer.getByRole('button',{name:'预览并校验',exact:true});
 try{
  await previewButton.click();
  await expect(previewButton).toHaveAttribute('aria-busy','true');
  await expect(previewButton).toBeDisabled();
 }finally{release();}
 await drawer.getByRole('cell',{name:format==='txt'?'Alpha':'Child',exact:true}).waitFor();
 await page.unroute(previewUrl,hold);
 await expect(previewButton).toHaveAttribute('aria-busy','false');
 await expect(previewButton).toBeEnabled();
 const save=drawer.getByRole('button',{name:/保.*存/});assert.equal(await save.isEnabled(),true);
 await drawer.locator('input[id="dictionary-file-import_name"]').fill('Browser file QA updated');assert.equal(await save.isDisabled(),true);
 await drawer.getByRole('button',{name:'预览并校验',exact:true}).click();
 await drawer.getByRole('cell',{name:format==='txt'?'Alpha':'Child',exact:true}).waitFor();
 const imported=observeResponse(page,r=>r.url().includes('/file-import?')&&r.request().method()==='POST');
 await save.click();const response=await imported;assert.equal(response.status(),201);id=(await response.json()).id;
 await drawer.waitFor({state:'hidden'});
 const row=page.getByRole('row').filter({hasText:code});await row.waitFor();
 const downloaded=page.waitForEvent('download');await row.getByRole('button',{name:'导出字典',exact:true}).click();
 const download=await downloaded;assert.equal(await download.failure(),null);
 const exported=JSON.parse(readFileSync(await download.path(),'utf8'));assert.equal(exported.code,code);assert.equal(exported.name,'Browser file QA updated');assert.equal(exported.items.length,2);if(format!=='txt'){assert.equal(exported.structure,'TREE');assert.equal(exported.items.find(x=>x.value==='child').parentValue,'root');}
 await page.reload();await page.getByRole('row').filter({hasText:code}).waitFor();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>document.documentElement.clientWidth),false);
 console.log(format, 'PASS: browser invalid-file row feedback and save denial, file replacement, preview, metadata invalidation, save, JSON download, refresh persistence and desktop width');
 if(format==='txt'){
  await page.getByRole('button',{name:'导入字典',exact:true}).click();
  const unified=page.getByRole('dialog');
  const jsonCode=`qa_json_preview_${Date.now()}`;
  const jsonDataset={schemaVersion:1,code:jsonCode,name:'Browser JSON Preview',enabled:true,structure:'TREE',remark:'',items:[{value:'root',label:'Root',parentValue:null,sortOrder:0,enabled:true,remark:''},{value:'child',label:'Child',parentValue:'root',sortOrder:1,enabled:true,remark:''}]};
  const jsonSave=unified.getByRole('button',{name:/保.*存/});
  await unified.locator('input[type="file"]').setInputFiles({name:'dictionary.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(jsonDataset))});
  assert.equal(await unified.locator('input[id="dictionary-file-import_code"]').count(),0);
  assert.equal(await unified.getByText(jsonCode,{exact:true}).count(),0);
  assert.equal(await jsonSave.isDisabled(),true);
  const jsonPreview=observeResponse(page,r=>r.url().includes('/api/platform/dictionaries/preview')&&r.request().method()==='POST');
  await unified.getByRole('button',{name:'预览并校验',exact:true}).click();
  assert.equal((await jsonPreview).status(),200);
  await unified.getByText(jsonCode,{exact:true}).waitFor();
  assert.equal(await jsonSave.isEnabled(),true);
  await unified.locator('input[type="file"]').setInputFiles({name:'switch.txt',mimeType:'text/plain',buffer:Buffer.from('itemValue\titemLabel\nalpha\tAlpha\n')});
  await unified.locator('input[id="dictionary-file-import_code"]').fill(`qa_switch_${Date.now()}`);
  await unified.locator('input[id="dictionary-file-import_name"]').fill('Browser format switch');
  assert.equal(await unified.getByText(jsonCode,{exact:true}).count(),0);
  assert.equal(await jsonSave.isDisabled(),true);
  const switchedPreview=observeResponse(page,r=>r.url().includes('/api/platform/dictionaries/file-preview?')&&r.request().method()==='POST');
  await unified.getByRole('button',{name:'预览并校验',exact:true}).click();
  assert.equal((await switchedPreview).status(),200);
  await unified.getByRole('cell',{name:'Alpha',exact:true}).waitFor();
  assert.equal(await jsonSave.isEnabled(),true);
  await unified.locator('.ant-upload-list-item-actions button').last().click();
  await unified.getByRole('cell',{name:'Alpha',exact:true}).waitFor({state:'detached'});
  assert.equal(await jsonSave.isDisabled(),true);
 }
} catch(error) {
 mkdirSync('.runtime/browser-failures',{recursive:true});
 await page.screenshot({path:`.runtime/browser-failures/dictionary-${format}.png`,fullPage:true});
 writeFileSync(`.runtime/browser-failures/dictionary-${format}.txt`,await page.locator('body').innerText());
 writeFileSync(`.runtime/browser-failures/dictionary-${format}.html`,await page.content());
 writeFileSync(`.runtime/browser-failures/dictionary-${format}.aria.txt`,await page.locator('body').ariaSnapshot());
 throw error;
} finally {
 if(id){assert.match(id,/^[a-f0-9-]{36}$/);execFileSync('python3',['-c',`from smoke_support import sql; import sys; id=sys.argv[1]; sql("DELETE FROM platform_dictionary_item WHERE dictionary_id='"+id+"' AND parent_id IS NOT NULL; DELETE FROM platform_dictionary_item WHERE dictionary_id='"+id+"'; DELETE FROM platform_audit WHERE target_id='"+id+"'; DELETE FROM platform_dictionary WHERE id='"+id+"'")`,id],{cwd:'scripts',stdio:'pipe'});}
 await browser.close();
}

}
for(const format of ["txt","xls","xlsx"])await run(format);
