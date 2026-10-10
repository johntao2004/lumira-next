import {chromium,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';

const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const sql=s=>execFileSync('python3',['-c','import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],{cwd:'scripts',input:s,stdio:['pipe','pipe','pipe']}).toString().trim();
const id=randomUUID(),readId=randomUUID(),userId=randomUUID(),username='qa_notifications_'+randomUUID().replaceAll('-',''),dedupe='browser_notification_'+randomUUID().replaceAll('-','');
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
try{
 sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${userId}','${username}','Notification QA',password_hash FROM platform_user WHERE username='admin'`);
 sql(`INSERT INTO platform_notification(id,recipient_id,dedupe_key,kind,title_key,body_key,params,href) VALUES('${id}','${userId}','${dedupe}','feedback','notificationFeedbackSubmitted','notificationFeedbackCreatedBody','{"title":"Browser notification QA"}','/dashboard/feedback')`);
 sql(`INSERT INTO platform_notification(id,recipient_id,dedupe_key,kind,title_key,body_key,params,read_at) VALUES('${readId}','${userId}','${dedupe}_read','feedback','notificationCustomTitle','notificationCustomBody','{"title":"Already read QA","body":"Read history QA"}',now())`);
 const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});
 const token=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username,password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[token.headerName]:token.token}})).status(),200);
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/dashboard/home');
 const trigger=page.getByRole('button',{name:'通知',exact:true});await expect(trigger).toBeVisible();await trigger.click();
 const popover=page.locator('.notification-popover');await expect(popover.getByText('反馈已提交',{exact:true})).toBeVisible();await expect(popover.getByText('反馈“Browser notification QA”已进入处理队列。',{exact:true})).toBeVisible();
 assert.equal((await (await context.request.get('/api/account/notifications/unread-count')).json()),1);
 await popover.getByRole('tab',{name:'已读',exact:true}).click();await expect(popover.getByText('Already read QA',{exact:true})).toBeVisible();await expect(popover.getByText('反馈已提交',{exact:true})).toHaveCount(0);
 await popover.getByRole('tab',{name:'未读',exact:true}).click();await expect(popover.getByText('反馈已提交',{exact:true})).toBeVisible();
 await popover.getByRole('button',{name:'标为已读',exact:true}).click();await expect.poll(async()=>await (await context.request.get('/api/account/notifications/unread-count')).json()).toBe(0);
 await expect(popover.getByText('暂无通知',{exact:true})).toBeVisible();
 await popover.getByRole('tab',{name:'已读',exact:true}).click();
 await expect(popover.getByText('反馈“Browser notification QA”已进入处理队列。',{exact:true})).toBeVisible();
 await page.reload();await trigger.click();await popover.getByRole('tab',{name:'已读',exact:true}).click();await expect(popover.getByText('反馈已提交',{exact:true})).toBeVisible();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>document.documentElement.clientWidth),false);assert.deepEqual(errors,[]);
 console.log('PASS: isolated notification tabs, unread count, mark-read moves to history, reload persistence and no desktop overflow');
 await context.close();
}finally{await browser.close();sql(`DELETE FROM spring_session WHERE principal_name='${username}'; DELETE FROM platform_user WHERE id='${userId}'`);}
