import { loadQaRunContext } from './qa-run-guard.mjs';
import {chromium,expect} from '@playwright/test';
import {writeFileSync,mkdirSync} from 'node:fs';
import assert from 'node:assert/strict';
import {loadLocaleMessages} from '../apps/web/src/platform/i18n/messages.ts';
import {providerDefaultBaseUrl,providerKindLabel} from '../plugins/ai/frontend/types.ts';
const { config, baseURL } = loadQaRunContext();
const browser=await chromium.launch({headless:true});const context=await browser.newContext({baseURL:baseURL,viewport:{width:1254,height:869}});const page=await context.newPage();const errors=[];const writes=[];const checks=[];const t=await loadLocaleMessages('zh-CN');const folder='.runtime/qa/provider-form-defaults';mkdirSync(folder,{recursive:true});
page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(new URL(r.url()).pathname==='/api/platform/ai/providers'&&r.method()!=='GET')writes.push(r.method());});
const pass=name=>{checks.push({name,status:'passed'});console.log('PASS',name);};const drawer=()=>page.getByRole('dialog');
const item=label=>drawer().locator('.ant-form-item').filter({has:page.locator('label').filter({hasText:new RegExp(`^${label}$`)})});
async function choose(kind){const control=drawer().getByLabel('服务类型',{exact:true});await control.click();if(kind==='TYPESAFE_JEV'){for(let i=0;i<10;i++)await control.press('ArrowDown');await control.press('Enter');}else await page.locator('.ant-select-dropdown').getByText(t[providerKindLabel[kind]],{exact:true}).click();}
async function blank(){await expect(drawer().locator('#providerKind')).toHaveValue('');await expect(drawer().locator('#name')).toHaveValue('');await expect(drawer().locator('#baseUrl')).toHaveValue('');await expect(drawer().locator('#modelName')).toHaveValue('');await expect(drawer().locator('#baseUrl')).toBeDisabled();assert.equal(await drawer().getByRole('button',{name:'获取模型列表',exact:true}).count(),0);}
async function cancel(){await drawer().getByRole('button',{name:/取\s*消/}).click();await drawer().waitFor({state:'hidden'});}
let baseline;let logoutStatus;
try{
 await page.goto('/user/login');await page.getByLabel('用户名',{exact:true}).fill(config.LUMIRA_BOOTSTRAP_USERNAME??'admin');await page.getByLabel('密码',{exact:true}).fill(config.LUMIRA_BOOTSTRAP_PASSWORD);await page.getByRole('button',{name:/登\s*录/}).click();await page.waitForURL('**/dashboard/**');await page.goto('/dashboard/ai-models');await page.getByRole('heading',{name:'模型接入',exact:true}).waitFor();baseline=await(await context.request.get('/api/platform/ai/providers')).json();
 await page.getByRole('button',{name:'添加模型服务',exact:true}).click();await blank();const initialLabels=(await drawer().locator('.ant-form-item-label label').allTextContents()).map(label=>label.trim());assert.deepEqual(initialLabels.slice(0,4),['启用此服务','服务类型','服务地址','显示名称']);assert.deepEqual(initialLabels.filter(label=>label!=='启用此服务').slice(0,3),['服务类型','服务地址','显示名称']);await page.screenshot({path:`${folder}/initial-empty.png`});pass('new drawer empty and order enabled-kind-url-name');
 const blankSave=drawer().getByRole('button',{name:/保\s*存/});await expect(blankSave).toBeDisabled();assert.deepEqual(writes,[]);pass('blank form keeps save disabled and prevents provider creation');
 await choose('OLLAMA');await expect(drawer().locator('#name')).toHaveValue('Ollama');await expect(drawer().locator('#baseUrl')).toHaveValue(providerDefaultBaseUrl.OLLAMA);await expect(drawer().locator('#baseUrl')).toBeEnabled();await expect(drawer().locator('#credentialValue')).toHaveValue('');await page.screenshot({path:`${folder}/ollama-populated.png`});pass('first type selection populates name and editable URL');
 await choose('LM_STUDIO');await expect(drawer().locator('#name')).toHaveValue('LM Studio');await expect(drawer().locator('#baseUrl')).toHaveValue(providerDefaultBaseUrl.LM_STUDIO);pass('switching type replaces automatically filled defaults');
 await drawer().locator('#name').fill('My custom provider');await drawer().locator('#baseUrl').fill('http://127.0.0.1:29999/v1');await expect(drawer().locator('#baseUrl')).toHaveValue('http://127.0.0.1:29999/v1');await drawer().locator('#credentialValue').fill('synthetic-unsaved-key');await choose('OPENAI');await expect(drawer().locator('#name')).toHaveValue('My custom provider');await expect(drawer().locator('#baseUrl')).toHaveValue(providerDefaultBaseUrl.OPENAI);await expect(drawer().locator('#credentialValue')).toHaveValue('');await expect(drawer().locator('#modelName')).toHaveValue('');pass('custom name retained, address editable, switched connection clears key/model');
 await cancel();await page.getByRole('button',{name:'添加模型服务',exact:true}).click();await blank();pass('close and reopen resets blank fields');
 await drawer().locator('#name').fill('Name before type');await choose('DEEPSEEK');await expect(drawer().locator('#name')).toHaveValue('Name before type');await expect(drawer().locator('#baseUrl')).toHaveValue(providerDefaultBaseUrl.DEEPSEEK);pass('name typed before choosing type is preserved');
 await cancel();
 for(const kind of Object.keys(providerDefaultBaseUrl)){
  await page.getByRole('button',{name:'添加模型服务',exact:true}).click();await choose(kind);await expect(drawer().locator('#name')).toHaveValue(t[providerKindLabel[kind]]);
  if(kind==='TYPESAFE_JEV'){await expect(item('服务地址').locator('input')).toHaveValue(providerDefaultBaseUrl[kind]);await expect(drawer().locator('#modelName')).toHaveValue('jev-latest');assert.equal(await drawer().getByRole('button',{name:'获取模型列表',exact:true}).count(),0);}
  else{await expect(drawer().locator('#baseUrl')).toHaveValue(providerDefaultBaseUrl[kind]);await expect(drawer().locator('#modelName')).toHaveValue('');}
  await cancel();
 }
 pass('all 11 provider types populate their configured defaults');
 if(baseline.length){const existing=baseline[0];await page.getByRole('row').filter({hasText:existing.name}).getByRole('button',{name:/编\s*辑/}).click();await expect(drawer().locator('#name')).toHaveValue(existing.name);await expect(drawer().locator('#modelName')).toHaveValue(existing.modelName);await expect(drawer().locator('#providerKind')).toBeDisabled();if(existing.providerKind==='TYPESAFE_JEV')await expect(item('服务地址').locator('input')).toHaveValue(existing.baseUrl);else await expect(drawer().locator('#baseUrl')).toHaveValue(existing.baseUrl);await cancel();pass('existing provider edit preserves stored values');}
 await page.getByRole('button',{name:'添加模型服务',exact:true}).click();await blank();await page.setViewportSize({width:390,height:844});await page.waitForTimeout(500);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.screenshot({path:`${folder}/mobile-empty.png`});await cancel();pass('390px drawer no horizontal page overflow');
 assert.deepEqual(writes,[]);assert.deepEqual(await(await context.request.get('/api/platform/ai/providers')).json(),baseline);pass('no provider writes or inference calls and original settings unchanged');assert.deepEqual(errors,[]);pass('no browser page errors');
}finally{
 try{const token=await(await context.request.get('/api/auth/csrf')).json();logoutStatus=(await context.request.post('/api/auth/logout',{headers:{[token.headerName]:token.token}})).status();}finally{await context.request.dispose();await context.close();await browser.close();writeFileSync(`${folder}/results.json`,JSON.stringify({recordedAt:new Date().toISOString(),environment:'main preview 8010/8090',checks,providerWriteRequests:writes.length,pageErrors:errors,logoutStatus},null,2)+'\n');}
}
