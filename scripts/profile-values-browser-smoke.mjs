import {chromium} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const dictionary=randomUUID(),option=randomUUID(),dictionaryCode='qa_values_'+dictionary.replaceAll('-','');
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));const id=randomUUID(),username='qa_values_'+id.replaceAll('-','');let changed=false;
const sql=statement=>execFileSync('python3',['-c','import sys; from smoke_support import sql; sql(sys.stdin.read())'],{cwd:'scripts',input:statement,stdio:['pipe','pipe','pipe']});
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
async function login(context,name){const csrf=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:name,password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);}
try{
 const admin=await browser.newContext({baseURL:'http://127.0.0.1:8010'});await login(admin,'admin');const path='/api/platform/settings/profile-fields';const initial=await(await admin.request.get(path)).json();assert.equal(initial.version,0);
 for(const field of initial.data.fields){if(field.key==='realName')field.visible=false;if(field.key==='region'){field.required=true;field.label='Location QA';field.sortNo=0;}}
 const fields=['TEXT','TEXTAREA','NUMBER','DATE','SELECT'].map((type,i)=>({key:'custom'+i,label:'QA '+type,description:'',type,visible:true,required:true,weight:5,placeholder:'',groupKey:'custom',groupLabel:'QA group',sortNo:100+i,custom:true,options:type==='SELECT'?['One','Two']:[]}));
 sql(`INSERT INTO platform_dictionary(id,code,name,structure) VALUES('${dictionary}','${dictionaryCode}','Profile choices','FLAT'); INSERT INTO platform_dictionary_item(id,dictionary_id,value,label) VALUES('${option}','${dictionary}','chosen','Dictionary choice QA')`);
 fields.push({...fields[4],key:'dictionaryChoice',label:'QA dictionary',options:[],dictionaryCode});
 const csrf=await(await admin.request.get('/api/auth/csrf')).json();assert.equal((await admin.request.put(path,{data:{version:0,data:{fields:[...initial.data.fields,...fields]},changeReason:'Profile values browser fixture'},headers:{[csrf.headerName]:csrf.token}})).status(),200);changed=true;
 sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${id}','${username}','Values browser QA',password_hash FROM platform_user WHERE username='admin'`);
 const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});await login(context,username);const page=await context.newPage();await page.goto('/dashboard/home');
 async function openProfile(){await page.locator('.pro-account-trigger').click();await page.getByRole('menuitem',{name:'个人资料',exact:true}).click();}
 async function open(){await openProfile();await page.getByRole('button',{name:'自定义资料',exact:true}).click();await page.locator('#custom0').waitFor();}
 await openProfile();await page.getByLabel('Location QA',{exact:true}).waitFor();assert.equal(await page.locator('#realName').count(),0);await page.locator('button[form="account-profile"]').click();await page.locator('.ant-form-item-explain-error').first().waitFor();await page.getByLabel('Location QA',{exact:true}).fill('New region');const basicSaved=page.waitForResponse(r=>r.url().endsWith('/api/account/profile')&&r.request().method()==='PUT');await page.locator('button[form="account-profile"]').click();assert.equal((await basicSaved).status(),200);await page.locator('#region').waitFor({state:'hidden'});
 await open();await page.getByRole('dialog',{name:'自定义资料',exact:true}).getByRole('region',{name:'资料完善度'}).getByText('22%',{exact:true}).waitFor();await page.locator('button[form="account-profile-values"]').click();await page.locator('.ant-form-item-explain-error').first().waitFor();assert.ok(await page.locator('.ant-form-item-explain-error').count()>0);
 await page.locator('#custom0').fill('Text value');await page.locator('#custom1').fill('Long\ntext');await page.locator('#custom2').fill('-12.50');await page.locator('#custom3').fill('2024-02-29');await page.locator('#custom4').click();await page.locator('.ant-select-item-option-content').filter({hasText:/^Two$/}).click();
 await page.locator('#dictionaryChoice').click();await page.locator('.ant-select-item-option-content').filter({hasText:/^Dictionary choice QA$/}).click();
 const saved=page.waitForResponse(r=>r.url().endsWith('/api/account/profile-values')&&r.request().method()==='PUT');await page.locator('button[form="account-profile-values"]').click();assert.equal((await saved).status(),200);
 await page.locator('#custom0').waitFor({state:'hidden'});await page.reload();await open();await page.getByRole('dialog',{name:'自定义资料',exact:true}).getByRole('region',{name:'资料完善度'}).getByText('89%',{exact:true}).waitFor();assert.equal(await page.locator('#custom0').inputValue(),'Text value');assert.equal(await page.locator('#custom3').inputValue(),'2024-02-29');
 const values=await(await context.request.get('/api/account/profile-values')).json();assert.equal(values.values.dictionaryChoice,option);assert.equal(values.values.custom4,'Two');assert.equal(Number(values.values.custom2),-12.5);
 const freshToken=await(await admin.request.get('/api/auth/csrf')).json();assert.equal((await admin.request.put(path,{data:{version:1,data:{fields:[...initial.data.fields,...fields]},changeReason:'Concurrent schema revision'},headers:{[freshToken.headerName]:freshToken.token}})).status(),200);
 await page.locator('#custom0').fill('Unsaved value');const conflict=page.waitForResponse(r=>r.url().endsWith('/api/account/profile-values')&&r.request().method()==='PUT');await page.locator('button[form="account-profile-values"]').click();assert.equal((await conflict).status(),409);assert.equal(await page.locator('#custom0').inputValue(),'Unsaved value');
 console.log('PASS: schema conflict preserves input; all five profile value controls, required validation, account save and reload persistence');
}finally{
 sql(`DELETE FROM platform_audit WHERE target_id='${id}'; DELETE FROM spring_session WHERE principal_name='${username}'; DELETE FROM platform_user WHERE id='${id}'`);
 if(changed)sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='profile-fields'); DELETE FROM platform_setting WHERE section='profile-fields'");
 sql(`DELETE FROM platform_dictionary_item WHERE id='${option}'; DELETE FROM platform_dictionary WHERE id='${dictionary}'`);
 await browser.close();
}
