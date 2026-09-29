import {chromium} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync('.runtime/local.json','utf8'));
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
const context=await browser.newContext({baseURL:'http://127.0.0.1:8010'});let changed=false;
try{
 const csrf=await(await context.request.get('/api/auth/csrf')).json();assert.equal((await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);
 assert.equal((await(await context.request.get('/api/platform/settings/watermark')).json()).version,0,'Refuse to overwrite configured watermark');
 const page=await context.newPage();await page.addInitScript(()=>{window.watermarkTexts=[];window.watermarkImages=[];const original=CanvasRenderingContext2D.prototype.fillText;CanvasRenderingContext2D.prototype.fillText=function(text,...args){window.watermarkTexts.push(text);return original.call(this,text,...args);};const draw=CanvasRenderingContext2D.prototype.drawImage;CanvasRenderingContext2D.prototype.drawImage=function(source,...args){window.watermarkImages.push(source?.src??source?.currentSrc??'');return draw.call(this,source,...args);};});
 await page.goto('/dashboard/watermark');await page.locator('#data_enabled').click();
 assert.equal(await page.locator('#data_textLines').isVisible(),true,'Fixed text mode exposes only its own content field');assert.equal(await page.locator('#data_personalizedTextLines').count(),0);assert.equal(await page.locator('#changeReason').count(),0,'Routine settings do not require a reason');
 await page.locator('#data_textLines').fill('QA fixed');
 let saved=page.waitForResponse(r=>r.url().endsWith('/api/platform/settings/watermark')&&r.request().method()==='PUT');await page.locator('button[type="submit"]').click();assert.equal((await saved).status(),200);changed=true;
 await page.locator('.platform-watermark').waitFor();await page.waitForFunction(()=>window.watermarkTexts.includes('QA fixed'));
 assert.equal(await page.locator('.platform-watermark').evaluate(el=>getComputedStyle(el).pointerEvents),'none');
 await page.reload();await page.locator('.platform-watermark').waitFor();
 const mode=page.locator('.settings-form .ant-select').first();await mode.click();await page.keyboard.press('ArrowDown');await page.keyboard.press('Enter');await page.locator('#data_personalizedTextLines').waitFor();assert.equal(await page.locator('#data_textLines').count(),0,'Personalized mode hides fixed text');await page.locator('#data_personalizedTextLines').fill('QA {{username}}');saved=page.waitForResponse(r=>r.url().endsWith('/api/platform/settings/watermark')&&r.request().method()==='PUT');await page.locator('button[type="submit"]').click();assert.equal((await saved).status(),200);await page.waitForFunction(()=>window.watermarkTexts.includes('QA admin'));
 await page.reload();await page.locator('.platform-watermark').waitFor();await mode.click();await page.keyboard.press('ArrowDown');await page.keyboard.press('Enter');await page.locator('#data_imageUrl').waitFor();await page.locator('#data_imageUrl').fill('/login-cloud-platform.jpg');saved=page.waitForResponse(r=>r.url().endsWith('/api/platform/settings/watermark')&&r.request().method()==='PUT');await page.locator('button[type="submit"]').click();assert.equal((await saved).status(),200);await page.waitForFunction(()=>window.watermarkImages.some(src=>src.endsWith('/login-cloud-platform.jpg')));
 // Exercise a real control underneath the full-viewport layer.
 await page.locator('#data_enabled').click();saved=page.waitForResponse(r=>r.url().endsWith('/api/platform/settings/watermark')&&r.request().method()==='PUT');await page.locator('button[type="submit"]').click();assert.equal((await saved).status(),200);
 await page.locator('.platform-watermark').waitFor({state:'detached'});
 console.log('PASS: fixed/personalized/image modes, source-specific fields, canvas rendering, username substitution, save/reload, no routine reason field, click-through and live disable');
}finally{
 if(changed)execFileSync('python3',['-c',`from smoke_support import sql; sql("DELETE FROM platform_setting_revision WHERE setting_id IN (SELECT id FROM platform_setting WHERE section='watermark'); DELETE FROM platform_audit WHERE target_id IN (SELECT id FROM platform_setting WHERE section='watermark'); DELETE FROM platform_setting WHERE section='watermark'")`],{cwd:'scripts',stdio:'pipe'});
 await browser.close();
}
