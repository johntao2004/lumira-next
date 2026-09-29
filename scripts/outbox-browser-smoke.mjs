import {chromium,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {observeResponse} from './browser-support.mjs';

const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const sql=s=>execFileSync('python3',['-c','import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],{cwd:'scripts',input:s,stdio:['pipe','pipe','pipe']}).toString().trim();
const prefix='browser_'+randomUUID().replaceAll('-','').slice(0,10),ids=[randomUUID(),randomUUID(),randomUUID()],replayId=randomUUID();
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
try{
 sql(ids.map((id,i)=>`INSERT INTO platform_event_outbox(id,source_type,event_type,event_key,payload,status,attempts,last_error,delivered_at) VALUES('${id}','${prefix}','browser.event','${prefix}-${i}','{"fixture":true}','${['FAILED','DELIVERED','PENDING'][i]}',1,${i===0?"'fixture-error'":'NULL'},${i===1?'now()':'NULL'})`).join(';'));
 sql(`INSERT INTO platform_event_outbox(id,source_type,event_type,event_key,payload,status,attempts,last_error) VALUES('${replayId}','browser-replay','browser.replay','browser-replay-${replayId}','{"fixture":true}','DEAD_LETTER',8,'fixture-error')`);
 const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});
 const token=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[token.headerName]:token.token}})).status(),200);
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/dashboard/event-outbox');
 const main=page.getByRole('main');await expect(main.locator(`[data-row-key="${ids[0]}"]`)).toBeVisible();
 await main.getByRole('combobox',{name:'状态',exact:true}).click();await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText('处理失败',{exact:true}).click();
 await expect(main.locator(`[data-row-key="${ids[1]}"]`)).toHaveCount(0);await expect(main.locator(`[data-row-key="${ids[0]}"]`)).toBeVisible();
 const search=main.getByPlaceholder('搜索来源、事件或键',{exact:true});await search.fill(prefix);await search.press('Enter');await expect(main.locator('tbody tr[data-row-key]')).toHaveCount(1);
 await page.route('**/api/platform/event-outbox?**',route=>route.fulfill({status:503,contentType:'application/json',body:'{"detail":"unavailable"}'}));
 const failure=observeResponse(page,r=>r.url().includes('/api/platform/event-outbox?'));await main.getByRole('button',{name:'刷新页面',exact:true}).click();assert.equal((await failure).status(),503);
 await expect(page.locator('.ant-message-notice')).toBeVisible();await expect(main.locator(`[data-row-key="${ids[0]}"]`)).toBeVisible();
 await page.unroute('**/api/platform/event-outbox?**');await main.getByRole('button',{name:'刷新页面',exact:true}).click();await expect(page.locator('.ant-message-notice')).toHaveCount(0);
 await search.fill('');await search.press('Enter');
 await main.getByRole('combobox',{name:'状态',exact:true}).click();await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').getByText('死信',{exact:true}).click();
 await expect(main.locator(`[data-row-key="${replayId}"]`)).toBeVisible();
 await main.locator(`[data-row-key="${replayId}"]`).getByRole('button',{name:'人工重放',exact:true}).click();
 const replayModal=page.getByRole('dialog');await expect(replayModal).toBeVisible();
 await replayModal.getByRole('textbox',{name:'重放原因',exact:true}).fill('浏览器确认人工恢复');
 await replayModal.getByRole('button',{name:'人工重放',exact:true}).click();
 await expect(main.locator(`[data-row-key="${replayId}"]`)).toHaveCount(0);
 assert.equal(sql(`SELECT reason FROM platform_event_replay_approval WHERE event_id='${replayId}'`),'浏览器确认人工恢复');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>document.documentElement.clientWidth),false);assert.deepEqual(errors,[]);
 console.log('PASS: event outbox route, status filter, source/event/key search, failed refresh preserves rows, successful retry, dead-letter replay approval, no pageerror or desktop overflow');
 await context.close();
}finally{await browser.close();sql(`DELETE FROM platform_event_replay_approval WHERE event_id='${replayId}'`);sql(`DELETE FROM platform_event_outbox WHERE id IN (${[...ids,replayId].map(id=>`'${id}'`).join(',')})`);}
