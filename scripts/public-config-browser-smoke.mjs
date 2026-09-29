import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE?{executablePath:process.env.BROWSER_EXECUTABLE}:{})});
try{
 const context=await browser.newContext({baseURL:'http://127.0.0.1:8010',viewport:{width:1213,height:886}});
 let agreementFails=true,maintenanceFails=true,active=true;
 await context.route('**/api/public/agreements',route=>route.fulfill({status:agreementFails?503:200,contentType:'application/json',body:JSON.stringify(agreementFails?{detail:'unavailable'}:{userAgreementMarkdown:'# Recovered agreement',privacyAgreementMarkdown:''})}));
 await context.route('**/api/public/maintenance',route=>route.fulfill({status:maintenanceFails?503:200,contentType:'application/json',body:JSON.stringify(maintenanceFails?{detail:'unavailable'}:{active,title:'Maintenance recovery QA',message:'Notice content',endAt:null})}));
 const page=await context.newPage();await page.goto('/user/login');
 const agreementError=page.locator('.ant-notification-notice').filter({hasText:'暂时无法加载协议'});
 const maintenanceError=page.locator('.ant-notification-notice').filter({hasText:'暂时无法确认维护状态'});
 await agreementError.waitFor();await maintenanceError.waitFor();
 agreementFails=false;await agreementError.getByRole('button',{name:/重\s*试/}).click();
 await agreementError.waitFor({state:'hidden'});
 await page.getByRole('button',{name:'用户协议',exact:true}).click();
 await page.getByRole('heading',{name:'Recovered agreement',exact:true}).waitFor();
 await page.getByRole('dialog').getByRole('button',{name:'关闭',exact:true}).click();
 maintenanceFails=false;await maintenanceError.getByRole('button',{name:/重\s*试/}).click();
 await maintenanceError.waitFor({state:'hidden'});
 const notice=page.locator('.ant-alert').filter({hasText:'Maintenance recovery QA'});await notice.waitFor();
 maintenanceFails=true;
 await maintenanceError.waitFor({timeout:40000});
 assert.equal(await notice.count(),1,'A failed background refresh must retain the existing notice');
 maintenanceFails=false;active=false;await maintenanceError.getByRole('button',{name:/重\s*试/}).click();
 await maintenanceError.waitFor({state:'hidden'});await notice.waitFor({state:'hidden'});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 console.log('PASS: anonymous agreement and maintenance errors, retry recovery, agreement viewing, stale notice retained on polling error, successful inactive status clears notice; no server state changed');
}finally{await browser.close();}
