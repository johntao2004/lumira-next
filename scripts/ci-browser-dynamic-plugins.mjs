/** 独立动态 QA 的真实组件生命周期；生产固定图不变。
 * Real component lifecycle in separate mutable QA; production graph stays immutable. */
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { expect as baseExpect } from '@playwright/test';
const expect = baseExpect.configure({timeout:20000});

// 在全新管理员初始化后调用；请求助手必须为读写刷新授权版本。
// Call after the same fresh-admin UI bootstrap used by ci-browser-acceptance.mjs.
// write must refresh /auth/me and send the current authorization revision, including GETs.
export async function verifyDynamicPlugins({ admin, page, write, record, member }) {
  const root='/api/platform/modules/plugins';
  const identity=await admin.request.get('/api/auth/me');assert.equal(identity.status(),200);
  const revision=(await identity.json()).authorizationContext.revision;assert.ok(revision);
  const pluginList=await write(admin,root,'GET');assert.equal(pluginList.status(),200);
  const feedback=(await pluginList.json()).find(item=>item.id==='feedback');
  assert.ok(feedback?.installed && feedback.lifecycleChangesAllowed);
  assert.equal(feedback.lifecycleBlockReason,null);
  const digest=feedback.digest;
  const assetSelector=`script[src*="/assets/${digest}/plugin.js"]`;
  const title=`QA_PLUGIN_PRESERVE_${randomBytes(8).toString('hex')}`;
  const other=await admin.newPage();
  await other.goto('/dashboard/feedback');
  await expect(other.getByRole('table')).toBeVisible();
  await other.getByRole('button',{name:'提交反馈',exact:true}).click();
  const form=other.locator('#feedback-create');
  await form.locator('[id$="_title"]').fill(title);
  await form.locator('[contenteditable="true"]').fill('Disposable browser plugin persistence evidence.');
  const creation=other.waitForResponse(r=>new URL(r.url()).pathname==='/api/feedback'&&r.request().method()==='POST');
  await other.locator('button[form="feedback-create"]').click();
  const created=await creation;assert.equal(created.status(),201);const item=await created.json();assert.ok(item.id);
  await expect(form).toBeHidden();await other.reload();
  await expect(other.getByRole('row').filter({hasText:title})).toBeVisible();
  await expect(other.locator(assetSelector)).toHaveCount(1);
  await record('dynamic plugin creates persistent feedback through real UI',other);

  await page.goto('/dashboard/modules');
  await page.getByRole('tab',{name:'可管理模块',exact:true}).click();
  const card=page.locator('.modules-card').filter({hasText:'feedback'});
  await expect(card.getByRole('switch')).toBeEnabled();
  // 此开关立即发送带版本的请求，没有确认对话框。
  // This switch sends an immediate versioned request; no confirmation dialog exists.
  await card.getByRole('switch').click();
  await expect(card.getByRole('switch')).not.toBeChecked();
  await expect(other.getByRole('table')).toHaveCount(0);
  await expect(other.locator(assetSelector)).toHaveCount(0);
  const disabledResponse=await write(admin,'/api/feedback','GET');
  assert.equal(disabledResponse.status(),403);
  const disabledError=await disabledResponse.json();
  assert.equal(disabledError.detail,'无权访问或安全令牌已过期');
  await other.reload();await expect(other.getByRole('table')).toHaveCount(0);
  await card.getByRole('switch').click();
  await expect(card.getByRole('switch')).toBeChecked();
  await expect(other.getByRole('row').filter({hasText:title})).toBeVisible();
  await record('dynamic switch removes and restores another tab component and plugin script',other);

  if(member) {
    // 在显式撤销前使用独立的真实普通会话。
    // Separate real ordinary session, before its explicit revoke in the caller.
    assert.equal((await write(member,`${root}/feedback/uninstall-plan`,'POST',{})).status(),403);
    assert.equal((await write(member,`${root}/feedback/install-plan`,'POST',{})).status(),403);
  }
  await card.getByRole('button',{name:'卸载插件',exact:true}).click();
  const drawer=page.getByRole('dialog',{name:'卸载插件',exact:true}).first();
  await expect(drawer.getByRole('radio',{name:'仅卸载插件，保留数据',exact:true})).toBeChecked();
  const calls=[];const listener=request=>{if(new URL(request.url()).pathname===`${root}/feedback/uninstall`&&request.method()==='POST')calls.push(request);};
  page.on('request',listener);
  await drawer.getByRole('button',{name:/确\s*认/}).click();
  const confirm=page.getByRole('dialog',{name:'卸载插件',exact:true}).last();
  await confirm.getByRole('button',{name:/取\s*消/}).click();
  await expect(page.getByRole('dialog',{name:'卸载插件',exact:true})).toHaveCount(1);
  assert.equal(calls.length,0);
  page.off('request',listener);
  await record('preserve uninstall cancellation sends no mutation',page);

  async function awaitOperation(responsePromise) {
    const response=await responsePromise;assert.equal(response.status(),200);
    const operation=await response.json();assert.ok(operation.id);
    await expect.poll(async()=>{
      // 图切换时仅操作查询可用，复用提交前已验证的普通会话版本，不请求被阻断的 csrf/me。
      // Only operation reads remain available during graph changes; reuse the pre-submit normal-session revision.
      const result=await admin.request.get(`${root}/operations/${operation.id}`,{headers:{'X-Lumira-Authorization-Context-Revision':revision}});
      assert.equal(result.status(),200);return (await result.json()).status;
    },{timeout:60000,intervals:[250,500,1000]}).toBe('SUCCEEDED');
    await expect.poll(async()=>{
      const response=await admin.request.get('/api/auth/me');
      if(response.status()===503) {assert.equal((await response.json()).code,'pluginRuntimeNotReady');return false;}
      assert.equal(response.status(),200);return true;
    },{timeout:20000}).toBe(true);
  }
  await drawer.getByRole('button',{name:/确\s*认/}).click();
  const uninstall=page.waitForResponse(r=>new URL(r.url()).pathname===`${root}/feedback/uninstall`&&r.request().method()==='POST');
  await page.getByRole('dialog',{name:'卸载插件',exact:true}).last().getByRole('button',{name:'卸载插件',exact:true}).click();
  await awaitOperation(uninstall);
  await expect(page.getByRole('dialog',{name:'卸载插件',exact:true})).toHaveCount(0);
  await expect(other.getByRole('table')).toHaveCount(0);
  await expect(other.locator(assetSelector)).toHaveCount(0);
  await other.reload();await expect(other.getByRole('table')).toHaveCount(0);
  assert.equal((await write(admin,'/api/feedback','GET')).status(),403);
  const uninstalled=await write(admin,root,'GET');assert.equal(uninstalled.status(),200);
  assert.equal((await uninstalled.json()).find(value=>value.id==='feedback').installed,false);
  const available=await write(admin,`${root}/frontend`,'GET');assert.equal(available.status(),200);
  assert.equal((await available.json()).some(value=>value.id==='feedback'),false);
  await record('preserve uninstall succeeds and removes frontend resource and endpoint',other);

  await card.getByRole('button',{name:'安装插件',exact:true}).click();
  const install=page.waitForResponse(r=>new URL(r.url()).pathname===`${root}/feedback/install`&&r.request().method()==='POST');
  await page.getByRole('dialog',{name:'安装插件',exact:true}).getByRole('button',{name:/确\s*认/}).click();
  await awaitOperation(install);
  await expect(page.getByRole('dialog',{name:'安装插件',exact:true})).toHaveCount(0);
  await other.goto('/dashboard/feedback');
  await expect(other.getByRole('row').filter({hasText:title})).toBeVisible();
  await expect(other.locator(assetSelector)).toHaveCount(1);
  const retained=await write(admin,`/api/feedback/${item.id}`,'GET');assert.equal(retained.status(),200);
  assert.equal((await retained.json()).title,title);
  await other.reload();await expect(other.getByRole('row').filter({hasText:title})).toBeVisible();
  await record('reinstall restores component and original feedback ID/content across refresh',other);
  await other.close();
}
