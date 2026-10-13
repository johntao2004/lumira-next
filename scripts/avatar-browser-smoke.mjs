import {chromium,expect} from '@playwright/test';
import {readFileSync,mkdirSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {observeResponse} from './browser-support.mjs';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const owner=randomUUID(),username='qa_avatar_browser_'+owner.replaceAll('-','');
const sql=statement=>execFileSync('python3',['-c','import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'],{cwd:'scripts',input:statement,stdio:['pipe','pipe','pipe']}).toString().trim();
const png=execFileSync('python3',['-c',String.raw`import sys,struct,zlib
def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d))
sys.stdout.buffer.write(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',2,1,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\xff\x00\x00\xff\x00\xff\x00\xff'))+chunk(b'IEND',b''))`]);
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
let context;
try{
 sql(`INSERT INTO platform_user(id,username,display_name,password_hash) SELECT '${owner}','${username}','Avatar Browser QA',password_hash FROM platform_user WHERE username='admin'`);
 context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});
 const token=await(await context.request.get('/api/auth/csrf')).json();
 assert.equal((await context.request.post('/api/auth/login',{form:{username,password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[token.headerName]:token.token}})).status(),200);
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('/dashboard/home');await page.locator('.pro-account-trigger').click();await page.getByRole('menuitem',{name:'个人资料',exact:true}).click();
 const editor=page.getByRole('region',{name:'头像',exact:true});await editor.waitFor();
 await page.locator('#account-profile_displayName').fill('Unsaved profile name');
 async function choose(body,name='avatar.png'){
  const chooser=page.waitForEvent('filechooser');await editor.getByRole('button',{name:'更换头像',exact:true}).click();
  await(await chooser).setFiles({name,mimeType:'image/png',buffer:body});
 }
 const invalid=observeResponse(page,r=>r.url().includes('/api/account/avatar?')&&r.request().method()==='POST');
 await choose(Buffer.from('<svg/>'));assert.equal((await invalid).status(),400);
 await page.locator('.ant-message-notice').filter({hasText:'请选择有效的 JPEG 或 PNG 图片。'}).waitFor();
 const uploaded=observeResponse(page,r=>r.url().includes('/api/account/avatar?')&&r.request().method()==='POST');
 await choose(png);assert.equal((await uploaded).status(),200);
 await expect(page.locator('img[alt="头像"]:visible')).toHaveCount(2);
 await page.waitForFunction(()=>[...document.querySelectorAll('img[alt="头像"]')].every(img=>img.complete&&img.naturalWidth===2));
 assert.equal(await page.locator('#account-profile_displayName').inputValue(),'Unsaved profile name');
 assert.equal((await(await context.request.get('/api/account/profile')).json()).displayName,'Avatar Browser QA');
 // UI policy rendering is injected here; real policy enforcement has its own HTTP suite.
 const rules=await(await context.request.get('/api/account/profile-fields')).json();
 rules.data.fields.push({key:'avatarUrl',label:'Required avatar QA',description:'',visible:true,required:true});
 const avatarRule=rules.data.fields.at(-1);
 await page.route('**/api/account/profile-fields',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(rules)}));
 await page.reload();await page.locator('.pro-account-trigger').click();await page.getByRole('menuitem',{name:'个人资料',exact:true}).click();await editor.getByText('Required avatar QA *',{exact:true}).waitFor();
 await expect(editor.getByRole('button',{name:'移除头像',exact:true})).toBeDisabled();
 avatarRule.visible=false;
 await page.reload();await page.locator('.pro-account-trigger').click();await page.getByRole('menuitem',{name:'个人资料',exact:true}).click();
 await expect(editor).toHaveCount(0);
 await page.unroute('**/api/account/profile-fields');
 await page.reload();await page.locator('.pro-account-trigger img[alt="头像"]').waitFor();
 await page.locator('.pro-account-trigger').click();await page.getByRole('menuitem',{name:'个人资料',exact:true}).click();await editor.waitFor();
 await editor.getByRole('button',{name:'移除头像',exact:true}).click();
 const removed=observeResponse(page,r=>r.url().includes('/api/account/avatar?')&&r.request().method()==='DELETE');
 await page.locator('.ant-popconfirm').getByRole('button',{name:'移除头像',exact:true}).click();assert.equal((await removed).status(),200);
 await expect(page.locator('img[alt="头像"]')).toHaveCount(0);
 await page.reload();assert.equal((await(await context.request.get('/api/account/avatar')).json()).fileId,null);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>document.documentElement.clientWidth),false);assert.deepEqual(errors,[]);
 mkdirSync('.runtime/account-settings',{recursive:true});await page.screenshot({path:'.runtime/account-settings/avatar-removed.png'});
 console.log('PASS: invalid image feedback, file chooser upload, decoded ProLayout header avatar, independent save preserves unsaved profile, reload, confirmed removal and fallback, no pageerror or desktop overflow');
}finally{
 const ids=sql(`SELECT id FROM platform_file WHERE owner_id='${owner}'`).split('\n').filter(Boolean);
 sql(`DELETE FROM platform_account_media WHERE user_id='${owner}'`);
 for(const id of ids){assert.match(id,/^[a-f0-9-]{36}$/);execFileSync('python3',['-c','from pathlib import Path; import sys; key=sys.argv[1]; [(Path(".runtime/files")/key[:2]/(key+suffix)).unlink(missing_ok=True) for suffix in [".blob",".upload"]]',id]);}
 sql(`DELETE FROM platform_file WHERE owner_id='${owner}'; DELETE FROM platform_audit WHERE actor='${username}'; DELETE FROM spring_session WHERE principal_name='${username}'; DELETE FROM platform_user WHERE id='${owner}'`);
 await browser.close();
}
