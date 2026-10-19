/** 隔离 CI 的真实表单、只读接口文档和合成短信端到端验收。
 * Real forms, read-only API docs and synthetic SMS end-to-end checks in isolated CI. */
// 仅在一次性 CI 上验证真实组件和接口，使用调用方的合成测试会话。
// Real UI/HTTP only; caller supplies existing authenticated contexts.
import assert from 'node:assert/strict';
import {expect as baseExpect} from '@playwright/test';
const expect=baseExpect.configure({timeout:20000});
const responseFor=(page,path,method)=>{const pending=page.waitForResponse(r=>new URL(r.url()).pathname===path&&r.request().method()===method);pending.catch(()=>{});return pending;};
async function choose(page,control,label){
  await control.click();
  // 虚拟列表另有隐藏无障碍选项；只点击唯一可视内容。
  // Virtual lists also contain hidden accessibility options; click the unique visible content.
  const option=page.locator('.ant-select-dropdown:visible .ant-select-item-option-content:visible').filter({hasText:new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')}$`)});
  await expect(option).toHaveCount(1);await option.click();
}

// 精确确认按钮结构并记录无表单值的点击诊断，确认步骤不可跳过。
// Verify the actual confirmation button and retain value-free click diagnostics; never bypass confirmation.
async function confirmReload(page) {
  const button=page.locator('.ant-popconfirm:visible .ant-popconfirm-buttons > button.ant-btn-primary');
  try {
    await expect(button).toHaveCount(1);await expect(button).toHaveText('重新载入配置');
    await expect(button).toBeEnabled();await button.click();
  } catch(error) {
    error.controlState=await button.evaluateAll(nodes=>nodes.map(node=>{
      const r=node.getBoundingClientRect(),hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);
      return {disabled:node.disabled,visible:r.width>0&&r.height>0,hitTag:hit?.tagName,hitClass:typeof hit?.className==='string'?hit.className:undefined,hitInside:!!hit&&node.contains(hit)};
    })).catch(()=>[]);
    throw error;
  }
}

export async function verifyExtraComponents({page,admin,member,write,record,only}) {
  const writes=[];const observe=r=>{const p=new URL(r.url()).pathname;if(p.startsWith('/api/platform/')&&!['GET','HEAD','OPTIONS'].includes(r.method()))writes.push(p);};
  page.on('request',observe);
  try {
    if(!only||only==='swagger') {
    const doc=responseFor(page,'/api/platform/api-docs','GET');
    await page.goto('/dashboard/api-docs');assert.equal((await doc).status(),200);
    const swagger=page.locator('.api-docs-swagger');
    await swagger.locator('.opblock-tag').first().click();
    await expect(swagger.locator('.opblock').first()).toBeVisible();
    await swagger.locator('.opblock-summary').first().click();
    await expect(swagger.locator('.opblock.is-open').first()).toBeVisible();
    await expect(swagger.locator('.try-out__btn,.execute,.authorize')).toHaveCount(0);
    await page.getByRole('textbox',{name:'搜索路径、接口名称、说明或标签',exact:true}).fill('QA_NO_SUCH_OPERATION_12345');
    await page.locator('.api-docs-search').getByRole('button',{name:/查\s*询/}).click();
    await expect(page.locator('.api-docs-document .ant-empty')).toBeVisible();
    await page.locator('.api-docs-search').getByRole('button',{name:/重\s*置/}).click();
    await expect(swagger.locator('.opblock-tag').first()).toBeVisible();
    if(member)assert.equal((await write(member,'/api/platform/api-docs','GET')).status(),403);
    await record('Swagger renders, expands, searches and resets without execution controls',page);

    }
    if(!only||only==='smtp') {
    await page.goto('/dashboard/smtp');
    const form=page.locator('form.settings-form');
    const enabled=form.getByRole('switch',{name:'启用邮件服务',exact:true});
    if(!await enabled.isChecked())await enabled.click(); // 仅改未保存界面；unsaved UI only
    const port=form.locator('input[id$="_data_port"]');
    await expect(port).toHaveCount(1);await expect(port).toHaveAttribute('role','spinbutton');
    assert.equal(await port.evaluate(input=>[...input.labels].some(label=>label.htmlFor===input.id&&label.textContent.trim()==='端口')),true);
    // 先校验全新配置的标准端口，不能预填预期值伪造联动。
    // Fresh CI defaults must be standard; do not overwrite baseline before testing inference.
    assert.ok(['465','587'].includes(await port.inputValue()));
    const security=form.getByLabel('连接加密',{exact:true});
    await choose(page,security,'TLS');await expect(port).toHaveValue('465');
    await choose(page,security,'STARTTLS');await expect(port).toHaveValue('587');
    await port.fill('2525');await choose(page,security,'TLS');await expect(port).toHaveValue('2525');
    await port.fill('');await choose(page,security,'STARTTLS');await expect(port).toHaveValue('');
    await record('SMTP inferred ports follow security; explicit custom and cleared values persist',page);
    await page.reload(); // 丢弃草稿，不保存或发送；discard edits, never save/send mail

    }
    // 文件只暂存在浏览器，beforeUpload 返回 false。
    // These uploads are staged client-side only (beforeUpload returns false).
    for(const item of [
      {route:'dictionaries',button:'导入字典',form:'#dictionary-file-import',name:'名称',ext:'txt'},
      {route:'print-templates',button:'新建模板',form:'#print-template-create',name:'模板名称',ext:'xlsx'},
    ].filter(item=>!only||only===item.route)) {
      await page.goto(`/dashboard/${item.route}`);
      await page.getByRole('button',{name:item.button,exact:true}).click();
      const f=page.locator(item.form), input=f.locator('input[type=file]');
      // 最小暂存字节不提交给导入或渲染服务。
      // Minimal staged bytes, never submitted to import/render services.
      const pick=name=>input.setInputFiles({name:`${name}.${item.ext}`,mimeType:'application/octet-stream',buffer:Buffer.from('synthetic staged fixture')});
      const name=f.getByLabel(item.name,{exact:true});
      await pick('QA_first');await expect(name).toHaveValue('QA_first');
      await pick('QA_second');await expect(name).toHaveValue('QA_second');
      await name.fill('Explicit label');await pick('QA_third');await expect(name).toHaveValue('Explicit label');
      await name.fill('');await pick('QA_fourth');await expect(name).toHaveValue('');
      await record(`${item.route} infers staged filename and preserves explicit edits`,page);
      await page.getByRole('dialog').getByRole('button',{name:/取\s*消/}).click();
      await page.getByRole('button',{name:item.button,exact:true}).click();
      await expect(name).toHaveValue('');await pick('QA_reopen');await expect(name).toHaveValue('QA_reopen');
      await page.getByRole('dialog').getByRole('button',{name:/取\s*消/}).click();
    }
    assert.deepEqual(writes,[],'read-only docs/defaults checks must not submit platform mutations');
  } finally {page.off('request',observe);}
}

export async function installDebuggerExample({page,admin,write,archivePath}) {
  assert.equal(process.env.LUMIRA_BROWSER_CI_MODE,'dynamic');
  const root='/api/platform/modules/plugins';
  await page.goto('/dashboard/modules');
  await page.getByRole('tab',{name:'可管理模块',exact:true}).click();
  const uploaded=responseFor(page,`${root}/import`,'POST');
  await page.getByLabel('导入插件',{exact:true}).setInputFiles(archivePath);
  assert.equal((await uploaded).status(),200);
  const card=page.locator('.modules-card').filter({hasText:'verification-debugger'});
  const identity=await admin.request.get('/api/auth/me');assert.equal(identity.status(),200);
  const revision=(await identity.json()).authorizationContext.revision;
  await card.getByRole('button',{name:'安装插件',exact:true}).click();
  const installed=responseFor(page,`${root}/verification-debugger/install`,'POST');
  await page.getByRole('dialog',{name:'安装插件',exact:true}).getByRole('button',{name:/确\s*认/}).click();
  const response=await installed;assert.equal(response.status(),200);const op=await response.json();
  await expect.poll(async()=>{const r=await admin.request.get(`${root}/operations/${op.id}`,{headers:{'X-Lumira-Authorization-Context-Revision':revision}});assert.equal(r.status(),200);return(await r.json()).status;},{timeout:60000}).toBe('SUCCEEDED');
  await expect(page.getByRole('dialog',{name:'安装插件',exact:true})).toHaveCount(0);
  // 安装不会自动授予新权限；使用完整安装管理员的正常角色委派接口。
  // Installation grants no new authority. Use the full install administrator's normal role-delegation API.
  assert.equal((await write(admin,'/api/plugins/verification-debugger/status','GET')).status(),403);
  const me=await admin.request.get('/api/auth/me');assert.equal(me.status(),200);const actor=await me.json();
  const authorities=['access','read','control','fixture'].map(action=>`platform:verification-debugger:${action}`);
  const created=await write(admin,'/api/platform/roles','POST',{code:'ci_verification_debugger',name:'CI Verification Debugger',enabled:true,dataScope:'ALL',authorities});
  assert.equal(created.status(),201);const role=await created.json();
  const assignments=await write(admin,`/api/platform/users/${actor.userId}/roles`,'GET');assert.equal(assignments.status(),200);const current=await assignments.json();
  const assigned=await write(admin,`/api/platform/users/${actor.userId}/roles`,'PUT',{version:current.version,roleIds:[...current.roleIds,role.id]});assert.equal(assigned.status(),200);
  const fresh=await admin.request.get('/api/auth/me');assert.equal(fresh.status(),200);const identityAfter=await fresh.json();
  for(const authority of authorities)assert.ok(identityAfter.globalAuthorities.includes(authority));
  const status=await write(admin,'/api/plugins/verification-debugger/status','GET');assert.equal(status.status(),200);
  assert.deepEqual(await status.json(),{enabled:true,receiverReady:true,allowedRecipient:'+15555550123'});
}

// 传输探针只接受固定文本、空 code，不弹验证码。
// Requires writer's strict fixed transport-message branch, code=='' and no empty-code popup.
// 仅用于全新动态 QA，不使用真实供应商、号码、凭据或 fixture 替代流程。
// Only fresh dynamic QA. No real providers, numbers, credentials or fixtures endpoint.
export async function verifyDebuggerQueue({admin,page,write,record,adminPassword,member,uiLogin}) {
  assert.equal(process.env.LUMIRA_BROWSER_CI_MODE,'dynamic');
  const root='/api/plugins/verification-debugger';
  const oldResponse=await write(admin,'/api/platform/settings/sms','GET');assert.equal(oldResponse.status(),200);
  const old=await oldResponse.json();assert.equal(old.data.enabled,false);assert.equal(old.credentialConfigured,false);
  let configured=false,sessionStarted=false,otp;
  const failures=[];
  const phonePage=await admin.newPage();
  try {
    await page.goto('/dashboard/verification-debugger');
    const started=responseFor(page,`${root}/sessions`,'POST');
    await page.getByRole('button',{name:'开始短信调试',exact:true}).click();
    const sr=await started;assert.equal(sr.status(),200);const session=await sr.json();sessionStarted=true;
    const url=new URL(session.endpoint);assert.equal(url.protocol,'http:');assert.equal(url.hostname,'127.0.0.1');assert.equal(url.pathname,'/send');assert.ok(url.port);
    assert.equal(session.recipient,'+15555550123');assert.equal(session.configuration.sender,'LumiraDebug');
    if(member)assert.equal((await write(member,`${root}/messages`,'GET')).status(),403);
    const put=await write(admin,'/api/platform/settings/sms','PUT',{version:old.version,data:{...old.data,...session.configuration},credentials:{action:'REPLACE',value:session.bearerToken}});
    assert.equal(put.status(),200);configured=true;
    // 真实账号流程生成 OTP、加密入队，再由 worker 调用本地接收器。
    // Real account flow creates OTP, encrypts durable delivery, worker calls local receiver.
    await phonePage.goto('/dashboard/account-authentication-security');
    await phonePage.locator('.account-settings-row').filter({has:phonePage.locator('.account-settings-row-label').filter({hasText:/^账号手机$/})}).getByRole('button',{name:/编\s*辑/}).click();
    const drawer=phonePage.getByRole('dialog',{name:'账号手机',exact:true});
    await drawer.getByLabel('账号手机',{exact:true}).fill('+15555550123');
    await drawer.getByLabel('当前密码',{exact:true}).fill(adminPassword);
    const request=responseFor(phonePage,'/api/account/phone/request','POST');
    await drawer.getByRole('button',{name:'发送验证码短信',exact:true}).click();
    assert.equal((await request).status(),202);
    await expect.poll(async()=>{const r=await write(admin,`${root}/messages`,'GET');assert.equal(r.status(),200);const items=(await r.json()).items;otp=items.find(x=>x.recipient==='+15555550123'&&x.provider==='webhook'&&/^\d{6}$/.test(x.code));return Boolean(otp);},{timeout:30000}).toBe(true);
    assert.equal(otp.message,`Lumira verification code: ${otp.code}`);
    await page.bringToFront();
    const modal=page.getByRole('dialog',{name:'收到本地测试验证码',exact:true});
    await expect(modal).toBeVisible();await expect(modal.getByText(otp.code,{exact:true})).toBeVisible();
    await modal.getByRole('button',{name:'关闭',exact:true}).click();
    // 独立传输测试只增加一条无验证码记录。
    // This exact HTTP test is separate from OTP delivery. It is allowed to add one non-code row.
    await page.bringToFront();
    const transport=await write(admin,'/api/platform/settings/sms/test','POST',{});assert.equal(transport.status(),200);assert.deepEqual(await transport.json(),{result:'ACCEPTED'});
    await expect.poll(async()=>{const r=await write(admin,`${root}/messages`,'GET');assert.equal(r.status(),200);const rows=(await r.json()).items.filter(x=>x.provider==='transport-test');assert.ok(rows.length<=1);return rows.length;}).toBe(1);
    const inbox=await write(admin,`${root}/messages`,'GET');const rows=(await inbox.json()).items;
    // 真实回环桥接必须严格拒绝契约外正文。
    // Strict rejection of non-contract text through the real loopback bridge.
    for(const message of ['Lumira verification code: 1234567','Lumira local SMS transport test. No account code is included. EXTRA']) {
      const rejected=await write(admin,`${root}/invoke`,'POST',{provider:'webhook',body:{to:'+15555550123',sender:'LumiraDebug',message}});
      assert.equal(rejected.status(),200);assert.equal((await rejected.json()).status,400);
    }
    const unchanged=await write(admin,`${root}/messages`,'GET');assert.equal(unchanged.status(),200);
    assert.deepEqual((await unchanged.json()).items.map(x=>x.id).sort(),rows.map(x=>x.id).sort());
    const probe=rows.find(x=>x.provider==='transport-test');assert.equal(probe.code,'');assert.equal(probe.recipient,'+15555550123');assert.equal(probe.message,'Lumira local SMS transport test. No account code is included.');
    const row=page.getByRole('row').filter({hasText:'transport-test'});await expect(row).toBeVisible();await expect(row.getByRole('button',{name:'查看验证码',exact:true})).toHaveCount(0);await expect(modal).toHaveCount(0);
    // 窗口凭据和验证码可见时不截图，只记录检查名称。
    // No screenshot while the window/token/OTP remains visible. Mark success without page argument.
  } catch(error) {failures.push(error);} finally {
    // 结束接收器前恢复禁用配置，恢复异常必须使 CI 失败。
    // Restore disabled settings before ending the receiver. Exceptions must fail CI.
    try {if(configured){const r=await write(admin,'/api/platform/settings/sms','GET');assert.equal(r.status(),200);const current=await r.json();const restored=await write(admin,'/api/platform/settings/sms','PUT',{version:current.version,data:old.data,credentials:{action:'CLEAR'}});assert.equal(restored.status(),200);}}catch(error){failures.push(error);}
    try {if(sessionStarted){await page.getByRole('button',{name:'结束短信调试',exact:true}).click();await expect(page.getByRole('button',{name:'结束短信调试',exact:true})).toBeDisabled();}}catch(error){failures.push(error);}
  }
  try {
    if(!failures.length) {
    // 先清理调试配置，再确认手机；凭据变更必须撤销旧会话。
    // Clean up debug configuration before confirming the phone; credential changes must revoke the old session.
    await phonePage.bringToFront();
    const drawer=phonePage.getByRole('dialog',{name:'账号手机',exact:true});
    await drawer.getByLabel('短信验证码',{exact:true}).fill(otp.code);
    const confirmed=responseFor(phonePage,'/api/account/phone/confirm','POST');
    await drawer.getByRole('button',{name:'确认手机号',exact:true}).click();assert.equal((await confirmed).status(),204);
    assert.equal((await admin.request.get('/api/auth/me')).status(),401);
    assert.equal((await uiLogin(phonePage,'admin',adminPassword)).state,'COMPLETE');
    await expect(phonePage).toHaveURL(url=>url.pathname.startsWith('/dashboard/'));
    const verified=await write(admin,'/api/account/phone','GET');assert.equal(verified.status(),200);
    const view=await verified.json();assert.equal(view.phone,'+15555550123');assert.ok(view.verifiedAt);
    await record('real phone OTP enqueue, private inbox UI and verification revokes old session; explicit non-OTP transport test');
    }
  } catch(error){failures.push(error);} finally {try{await phonePage.close();}catch(error){failures.push(error);}}
  if(failures.length)throw new AggregateError(failures,'Debugger checks or cleanup failed');
}

export async function verifySettingsDraftConflict({page,admin,write,record}) {
  // 仅在隔离 QA 改变验证码类型，服务始终保持禁用。
  // Isolated QA only. Changes local CAPTCHA type while keeping service disabled.
  const path='/api/platform/settings/captcha';
  const response=await write(admin,path,'GET');assert.equal(response.status(),200);const original=await response.json();
  assert.equal(original.data.enabled,false);assert.equal(original.data.type,'IMAGE_TEXT');
  let changed=false;
  try {
    await page.goto('/dashboard/captcha');
    const form=page.locator('form.captcha-settings-form');
    const enabled=form.getByRole('switch',{name:'启用 CAPTCHA 服务',exact:true});
    await enabled.click();
    const type=form.getByLabel('验证类型',{exact:true});
    await choose(page,type,'滑块');await choose(page,type,'图形文字');
    // 并发真实 HTTP 更新保留禁用状态和全部动作策略。
    // Real concurrent HTTP update, preserving disabled service and all existing action policies.
    const concurrent=await write(admin,path,'PUT',{version:original.version,data:{...original.data,type:'SLIDER'}});
    assert.equal(concurrent.status(),200);changed=true;
    await expect(enabled).toBeChecked();await expect(form.locator('.ant-select-selection-item').first()).toHaveText('图形文字');
    // 冲突处理会真实重读配置；全局关闭窗口聚焦刷新。
    // Conflict handling performs the real refetch; global window-focus refetch is disabled.
    const refreshed=responseFor(page,path,'GET');
    const conflict=responseFor(page,path,'PUT');await form.getByRole('button',{name:/保\s*存/}).click();
    assert.equal((await conflict).status(),409);assert.equal((await refreshed).status(),200);
    await expect(enabled).toBeChecked();await expect(form.locator('.ant-select-selection-item').first()).toHaveText('图形文字');
    const actual=await write(admin,path,'GET');assert.equal(actual.status(),200);const current=await actual.json();assert.equal(current.data.enabled,false);assert.equal(current.data.type,'SLIDER');
    await record('real settings refetch and stale-version 409 preserve unsaved CAPTCHA draft',page);
    await captchaReloadIncrement({page,admin,write,record,form,enabled,type,path});
  } finally {
    if(changed){const r=await write(admin,path,'GET');assert.equal(r.status(),200);const current=await r.json();const restored=await write(admin,path,'PUT',{version:current.version,data:original.data});assert.equal(restored.status(),200);}
    await page.goto('/dashboard/home');
  }
}

// 验证相同响应的显式重载。 / Verify explicit reloads of identical responses.
export async function verifyRegistrationSameValueReload({page,admin,write,record}) {
  const path='/api/platform/settings/registration';
  const response=await write(admin,path,'GET');assert.equal(response.status(),200);
  const baseline=await response.json();assert.equal(baseline.enabled,false);
  await page.goto('/dashboard/registration');
  const form=page.locator('.registration-settings-form');
  const enabled=form.getByRole('switch',{name:'开放公开注册',exact:true});
  await expect(enabled).not.toBeChecked();await enabled.click();await expect(enabled).toBeChecked();
  const email=form.getByRole('checkbox',{name:'电子邮箱',exact:true});
  await email.setChecked(!baseline.channels.includes('EMAIL'));
  await form.getByRole('button',{name:'重新载入配置',exact:true}).click();
  const pop=page.locator('.ant-popconfirm:visible');
  await expect(pop.getByText('重新载入将放弃本页未保存的修改。',{exact:true})).toBeVisible();
  // 确认前保留草稿。 / Keep the draft until confirmation.
  await expect(enabled).toBeChecked();
  const reloaded=responseFor(page,path,'GET');
  await confirmReload(page);
  const result=await reloaded;assert.equal(result.status(),200);await expect(pop).toBeHidden();
  const fresh=await result.json();assert.equal(fresh.version,baseline.version);
  assert.equal(fresh.enabled,baseline.enabled);assert.deepEqual(fresh.channels,baseline.channels);assert.equal(fresh.defaultRoleId,baseline.defaultRoleId);
  await expect(enabled).not.toBeChecked();
  if(baseline.channels.includes('EMAIL'))await expect(email).toBeChecked();else await expect(email).not.toBeChecked();
  await expect(form.getByRole('button',{name:'重新载入配置',exact:true})).toBeEnabled();
  // 重复同值重载，验证显式版本驱动回填。 / Repeat identical reloads to verify explicit revision hydration.
  await enabled.click();await expect(enabled).toBeChecked();
  await form.getByRole('button',{name:'重新载入配置',exact:true}).click();
  const again=responseFor(page,path,'GET');
  await confirmReload(page);
  assert.equal((await again).status(),200);await expect(enabled).not.toBeChecked();
  await record('registration confirmed reload restores unchanged server values twice',page);
}

export async function captchaReloadIncrement({page,admin,write,record,form,enabled,type,path}) {
  await form.getByRole('button',{name:'重新载入配置',exact:true}).click();
  const refreshed=responseFor(page,path,'GET');
  await confirmReload(page);
  assert.equal((await refreshed).status(),200);
  await expect(page.locator('.ant-popconfirm:visible')).toHaveCount(0);
  await expect(enabled).not.toBeChecked();
  await expect(form.locator('.ant-select-selection-item').first()).toHaveText('滑块');
  // 按新快照保存，服务保持关闭。 / Save with the new snapshot while keeping the service disabled.
  await enabled.click();await choose(page,type,'图形文字');await enabled.click();
  const saved=responseFor(page,path,'PUT');
  await form.getByRole('button',{name:/保\s*存/}).click();
  const result=await saved;assert.equal(result.status(),200);
  const snapshot=await result.json();assert.equal(snapshot.data.enabled,false);assert.equal(snapshot.data.type,'IMAGE_TEXT');
  await expect(enabled).not.toBeChecked();
  await expect(form.locator('.ant-select-selection-item').first()).toHaveText('图形文字');
  const persisted=await write(admin,path,'GET');assert.equal(persisted.status(),200);
  const current=await persisted.json();assert.equal(current.version,snapshot.version);assert.equal(current.data.enabled,false);assert.equal(current.data.type,'IMAGE_TEXT');
  await record('CAPTCHA conflict reload adopts new snapshot and subsequent UI save succeeds',page);
}
