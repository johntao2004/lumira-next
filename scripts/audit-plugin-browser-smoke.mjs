import {chromium, expect} from '@playwright/test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import path from 'node:path';
import {loadQaRunContext} from './qa-run-guard.mjs';

const {config, baseURL, projectRoot}=loadQaRunContext();
const sql=statement=>execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'], {
  cwd:path.join(projectRoot,'scripts'), input:statement, timeout:30000, stdio:['pipe','pipe','pipe'],
}).toString().trim();
const id=randomUUID(), marker='qa-audit-browser-'+randomUUID().replaceAll('-','');
const browser=await chromium.launch({headless:true});
try {
  sql(`INSERT INTO platform_audit(id,actor,action,target_id) VALUES('${id}','admin','${marker}','${id}')`);
  const context=await browser.newContext({baseURL,viewport:{width:1280,height:900}});
  const csrf=await(await context.request.get('/api/auth/csrf')).json();
  assert.equal((await context.request.post('/api/auth/login',{form:{username:'admin',password:config.LUMIRA_BOOTSTRAP_PASSWORD},headers:{[csrf.headerName]:csrf.token}})).status(),200);
  const me=await(await context.request.get('/api/auth/me')).json();
  const manifestResponse=await context.request.get('/api/platform/modules/plugins/frontend',{headers:{'X-Lumira-Authorization-Context-Revision':me.authorizationContext.revision}});
  assert.equal(manifestResponse.status(),200);
  const audit=(await manifestResponse.json()).find(plugin=>plugin.id==='audit-console');
  assert.ok(audit?.digest);
  const page=await context.newPage(), errors=[], pluginResponses=[];
  page.on('pageerror',error=>errors.push(error.message));
  page.on('response',response=>{if(response.url().includes(`/assets/${audit.digest}/plugin.js`))pluginResponses.push(response.status());});
  await page.goto('/dashboard/audit');
  await expect(page.locator('.audit-page')).toBeVisible({timeout:30000});
  await expect(page.locator('.audit-page').getByText(new RegExp(marker))).toBeVisible();
  assert.ok(pluginResponses.some(status=>status===200),'Audit UI must load from its independent plugin bundle');
  await page.reload();
  await expect(page.locator('.audit-page').getByText(new RegExp(marker))).toBeVisible({timeout:30000});
  assert.deepEqual(errors,[]);
  console.log('PASS: independent audit frontend bundle, retained record rendering and reload without browser errors');
  await context.close();
} finally {
  await browser.close();
}
