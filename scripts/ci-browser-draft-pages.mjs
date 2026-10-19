/** 在一次性 QA 用真实页面和接口验证草稿，不调用外部供应商。
 * Verify drafts through real pages and APIs in disposable QA, without external-provider calls. */
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {expect as baseExpect} from '@playwright/test';
import {responseFor,choose,expectSelected,confirmReload,installQAPlugin} from './ci-browser-extra-components.mjs';
const expect=baseExpect.configure({timeout:20000});

export async function verifyWechatDraft({page,admin,write,record}) {
  const path='/api/platform/settings/wechat';
  const r=await write(admin,path,'GET');assert.equal(r.status(),200);const original=await r.json();
  assert.equal(original.enabled,false);assert.equal(original.appSecretConfigured,false);
  const errors=[];let changed=false;
  const payload=(value,version)=>({version,enabled:false,appId:value.appId,publicOrigin:value.publicOrigin,timeoutSeconds:value.timeoutSeconds,appSecret:{action:'KEEP'}});
  try {
    await page.goto('/dashboard/wechat');
    const form=page.locator('form.wechat-settings-form'),enabled=form.getByRole('switch');
    const app=form.getByLabel('AppID',{exact:true}),timeout=form.locator('input[id$="_timeoutSeconds"]');
    const action=form.getByLabel('应用密钥处理方式',{exact:true});
    for(let i=0;i<2;i++) {
      await enabled.click();await app.fill('CI_UNSAVED_WECHAT');await timeout.fill(original.timeoutSeconds===7?'8':'7');
      await choose(page,action,'更换密钥');
      await expect(form.locator('input[type=password]')).toBeVisible();
      // 不填写或保存任何密钥；先取消一次，确认草稿仍存在。
      // Never enter or save credentials; cancel once and verify the draft remains.
      await form.getByRole('button',{name:'重新载入配置',exact:true}).click();
      await page.locator('.ant-popconfirm:visible .ant-popconfirm-buttons').getByRole('button',{name:/取\s*消/}).click();
      await expect(app).toHaveValue('CI_UNSAVED_WECHAT');
      await form.getByRole('button',{name:'重新载入配置',exact:true}).click();
      const reload=responseFor(page,path,'GET');await confirmReload(page);assert.equal((await reload).status(),200);
      await expect(enabled).not.toBeChecked();await expect(app).toHaveValue(original.appId);await expect(timeout).toHaveValue(String(original.timeoutSeconds));
      await expectSelected(action,'保留现有密钥');await expect(form.locator('input[type=password]')).toHaveCount(0);
    }
    await enabled.click();await app.fill('CI_LOCAL_WECHAT');await enabled.click();
    const remote=await write(admin,path,'PUT',payload({...original,appId:'CI_REMOTE_WECHAT'},original.version));assert.equal(remote.status(),200);changed=true;
    const conflict=responseFor(page,path,'PUT'),refetch=responseFor(page,path,'GET');
    await form.getByRole('button',{name:/保\s*存/}).click();assert.equal((await conflict).status(),409);assert.equal((await refetch).status(),200);
    await expect(app).toHaveValue('CI_LOCAL_WECHAT');await expectSelected(action,'保留现有密钥');
    await form.getByRole('button',{name:'重新载入配置',exact:true}).click();
    const reload=responseFor(page,path,'GET');await confirmReload(page);assert.equal((await reload).status(),200);await expect(app).toHaveValue('CI_REMOTE_WECHAT');
    await record('WeChat draft cancel, repeated reload, secret KEEP and real stale-version refresh',page);
  }catch(error){errors.push(error);}finally{
    if(changed)try{const current=await write(admin,path,'GET');assert.equal(current.status(),200);assert.equal((await write(admin,path,'PUT',payload(original,(await current.json()).version))).status(),200);}catch(error){errors.push(error);}
  }
  if(errors.length)throw new AggregateError(errors,'WeChat draft verification or restoration failed');
}

export async function verifyProfileDraft({page,admin,write,record}) {
  const path='/api/account/profile',valuesPath='/api/account/profile-values';
  const read=async()=>{const p=await write(admin,path,'GET'),v=await write(admin,valuesPath,'GET');assert.equal(p.status(),200);assert.equal(v.status(),200);return {p:await p.json(),v:await v.json()};};
  const original=await read();let changed=false;const errors=[];
  const payload=(p,v,name)=>({displayName:name,realName:p.realName,birthMonth:p.birthMonth,region:p.region,version:p.version,schemaVersion:p.schemaVersion,profileValuesSchemaVersion:v.schemaVersion,profileValues:v.values});
  try {
    await page.goto('/dashboard/account-security');
    const form=page.locator('#account-profile'),name=form.locator('input[id$="_displayName"]');
    await expect(name).toHaveCount(1);await name.fill('CI_LOCAL_PROFILE');
    const remote=await write(admin,path,'PUT',payload(original.p,original.v,'CI_REMOTE_PROFILE'));assert.equal(remote.status(),200);changed=true;
    const conflict=responseFor(page,path,'PUT'),fresh=responseFor(page,path,'GET'),freshValues=responseFor(page,valuesPath,'GET');
    await form.locator('button[type=submit]').click();const rejected=await conflict;assert.equal(rejected.status(),409);
    assert.equal(rejected.request().postDataJSON().version,original.p.version);
    assert.equal((await fresh).status(),200);assert.equal((await freshValues).status(),200);await expect(name).toHaveValue('CI_LOCAL_PROFILE');
    assert.equal((await read()).p.displayName,'CI_REMOTE_PROFILE');
    for(let i=0;i<2;i++) {
      await form.getByRole('button',{name:'重新载入配置',exact:true}).click();
      const reload=responseFor(page,path,'GET'),values=responseFor(page,valuesPath,'GET');await confirmReload(page);
      assert.equal((await reload).status(),200);assert.equal((await values).status(),200);await expect(name).toHaveValue('CI_REMOTE_PROFILE');
      if(i===0)await name.fill('CI_DISCARD_PROFILE');
    }
    await name.fill('CI_SAVED_PROFILE');
    const saved=responseFor(page,path,'PUT');await form.locator('button[type=submit]').click();assert.equal((await saved).status(),200);
    await expect(form.locator('button[type=submit]')).toBeEnabled();
    // 再保存验证成功后的快照版本已更新，不依靠整页刷新。
    // Save again to prove the post-save snapshot version advances without a full-page refresh.
    await name.fill('CI_SAVED_PROFILE_2');const again=responseFor(page,path,'PUT');await form.locator('button[type=submit]').click();assert.equal((await again).status(),200);
    await expect(form.locator('button[type=submit]')).toBeEnabled();await name.fill('CI_CANCEL_PROFILE');
    await page.goto('/dashboard/home');await page.goto('/dashboard/account-security');await expect(name).toHaveValue('CI_SAVED_PROFILE_2');
    await record('Real profile conflict refetch preserves draft; reload, repeated save and route cancellation isolate edits',page);
  }catch(error){errors.push(error);}finally{
    if(changed)try{const current=await read();assert.equal((await write(admin,path,'PUT',payload(current.p,current.v,original.p.displayName))).status(),200);}catch(error){errors.push(error);}
  }
  if(errors.length)throw new AggregateError(errors,'Profile draft verification or restoration failed');
}

export async function verifyPaymentDrafts({page,admin,write,record}) {
  assert.equal(process.env.LUMIRA_BROWSER_CI_MODE,'dynamic');
  for(const id of ['payment-core','alipay-pay','wechat-pay']) {
    const manifest=JSON.parse(readFileSync(`plugins/${id}/plugin.json`,'utf8'));
    await installQAPlugin({page,admin,id,archivePath:`plugins/${id}/dist/${id}-${manifest.version}.lumira-plugin.zip`});
  }
  const paths=['alipay-pay','wechat-pay'].map(id=>`/api/plugins/${id}/settings`);
  const baseline=[];
  for(const path of paths){const r=await write(admin,path,'GET');assert.equal(r.status(),200);const data=await r.json();assert.equal(data.configured,false);assert.ok(Object.values(data.secrets).every(v=>v===false));baseline.push(data);}
  const requests=[];const observe=request=>{if(new URL(request.url()).pathname.startsWith('/api/plugins/')&&(/orders/.test(request.url())||request.method()!=='GET'))requests.push({path:new URL(request.url()).pathname,method:request.method()});};
  page.on('request',observe);
  try {
    await page.goto('/dashboard/alipay-pay-settings');
    const app=page.getByLabel('支付宝应用 App ID',{exact:true});await app.fill('CI_ALIPAY_DRAFT');
    await choose(page,page.getByLabel('环境',{exact:true}),'沙箱（测试）');
    // 等待现有 30 秒 staleTime 后用真实网络恢复触发重读。
    // Wait beyond the existing 30-second staleTime and use real network reconnection to trigger refetch.
    await page.waitForTimeout(31000);await admin.setOffline(true);await page.waitForFunction(()=>navigator.onLine===false);
    const refreshed=responseFor(page,paths[0],'GET');await admin.setOffline(false);assert.equal((await refreshed).status(),200);await expect(app).toHaveValue('CI_ALIPAY_DRAFT');
    await page.goto('/dashboard/wechat-pay-settings');const other=page.getByLabel('微信 AppID',{exact:true});await expect(other).toHaveValue(baseline[1].values.appId??'');await other.fill('CI_WECHAT_PAY_DRAFT');
    await page.getByRole('button',{name:'重新载入配置',exact:true}).click();const reload=responseFor(page,paths[1],'GET');await confirmReload(page);assert.equal((await reload).status(),200);await expect(other).toHaveValue(baseline[1].values.appId??'');
    await page.goto('/dashboard/alipay-pay-settings');await expect(app).toHaveValue(baseline[0].values.appId??'');
    for(const input of await page.locator('input[type=password]').all())await expect(input).toHaveValue('');
    for(let i=0;i<paths.length;i++){const r=await write(admin,paths[i],'GET');assert.equal(r.status(),200);assert.deepEqual(await r.json(),baseline[i]);}
    assert.deepEqual(requests,[]);await record('Payment settings reconnect preserves draft; provider routes and confirmed reload isolate unsaved values with no provider calls',page);
  }finally{await admin.setOffline(false);page.off('request',observe);}
}
