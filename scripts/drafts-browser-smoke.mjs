import {chromium,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8')),owner=randomUUID(),name='qa_draft_'+randomUUID().replaceAll('-','');
function sql(input){return execFileSync(process.env.PSQL_BIN??'/opt/homebrew/opt/postgresql@18/bin/psql',['-X','-At','-h','127.0.0.1','-U',config.LUMIRA_DB_USER,'-d','lumira_next','-v','ON_ERROR_STOP=1'],{input,env:{...process.env,PGPASSWORD:config.LUMIRA_DB_PASSWORD},encoding:'utf8'}).trim();}
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});
try{
 sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${owner}','${name}','Draft browser QA',password_hash FROM platform_user WHERE username='admin';`);
 const csrf=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:name,password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);
 const a=await context.newPage(),errors=[];a.on('pageerror',e=>errors.push(e.message));await a.goto('/dashboard/home');
 sql(`INSERT INTO platform_account_draft(id,owner_id,draft_key,revision,payload,payload_bytes) SELECT gen_random_uuid(),'${owner}','fixture.'||n,gen_random_uuid(),'{}',2 FROM generate_series(1,25) n`);
 await a.setViewportSize({width:1213,height:886});const loaded=a.waitForResponse(r=>r.url().includes('/api/account/drafts?')&&r.request().method()==='GET');await a.locator('.pro-account-trigger').click();await a.getByRole('menuitem',{name:'草稿',exact:true}).click();const list=a.getByRole('dialog',{name:'草稿',exact:true});
 const loadedResponse=await loaded;assert.equal(loadedResponse.status(),200);assert.equal((await loadedResponse.json()).total,25);
 await expect(list.getByRole('table')).toBeVisible();await a.screenshot({path:'.runtime/drafts-list-desktop.png'});await expect(list.locator('.ant-table-tbody tr[data-row-key]')).toHaveCount(20);
 await list.locator('.ant-pagination-item-2').click();await expect(list.locator('.ant-table-tbody tr[data-row-key]')).toHaveCount(5);
 await list.getByLabel('搜索草稿标识',{exact:true}).fill('fixture.25');await list.getByLabel('搜索草稿标识',{exact:true}).press('Enter');await expect(list.locator('.ant-table-tbody tr[data-row-key]')).toHaveCount(1);
 sql(`UPDATE platform_account_draft SET revision=gen_random_uuid() WHERE owner_id='${owner}' AND draft_key='fixture.25'`);
 async function remove(){await list.getByRole('button',{name:'删除草稿',exact:true}).click();const response=a.waitForResponse(r=>r.url().includes('/api/account/drafts/fixture.25?revision=')&&r.request().method()==='DELETE');await list.locator('.ant-popconfirm').getByRole('button',{name:'删除草稿',exact:true}).click();return(await response).status();}
 assert.equal(await remove(),409);await expect(list.locator('.ant-popconfirm')).toBeHidden();await expect(list.getByRole('button',{name:'刷新页面',exact:true})).toBeEnabled();
 assert.equal(sql(`SELECT count(*) FROM platform_account_draft WHERE owner_id='${owner}' AND draft_key='fixture.25'`),'1');
 assert.equal(await remove(),204);await expect(list.locator('.ant-table-tbody tr[data-row-key]')).toHaveCount(0);
 await list.getByLabel('搜索草稿标识',{exact:true}).fill('');await list.getByLabel('搜索草稿标识',{exact:true}).press('Enter');await expect(list.locator('.ant-table-tbody tr[data-row-key]')).toHaveCount(20);
 await a.setViewportSize({width:390,height:844});await expect.poll(async()=>{const r=await list.boundingBox();return r&&r.x>=0&&r.x+r.width<=390;}).toBeTruthy();await a.screenshot({path:'.runtime/drafts-list-mobile.png'});await list.getByRole('button',{name:'关闭',exact:true}).click();
 await a.locator('.pro-account-trigger').click();await a.getByRole('menuitem',{name:'草稿',exact:true}).click();await expect(list).toBeVisible();await list.getByRole('button',{name:'关闭',exact:true}).click();
 await a.setViewportSize({width:1213,height:886});await a.locator('.pro-account-trigger').click();await a.getByRole('menuitem',{name:'草稿',exact:true}).click();await expect(list).toBeVisible();await list.getByRole('button',{name:'关闭',exact:true}).click();
 assert.deepEqual(errors,[]);console.log('PASS: paged account draft list/search/conflict/delete, mobile bounds, desktop/mobile ProLayout account entries');
}finally{
 try{const csrf=await(await context.request.get('/api/auth/csrf')).json();await context.request.post('/api/auth/logout',{headers:{[csrf.headerName]:csrf.token}});}finally{await browser.close();sql(`DELETE FROM platform_audit WHERE actor='${name}'; DELETE FROM platform_user WHERE id='${owner}';`);}
}
