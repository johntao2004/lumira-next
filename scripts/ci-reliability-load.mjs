/** 只对本次一次性 CI 项目执行有限负载及故障恢复，不操作真实服务。
 * Bounded load and recovery against this disposable CI project only, never real services. */
import assert from 'node:assert/strict';
import os from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { performance } from 'node:perf_hooks';
import { resolve } from 'node:path';
import { readFileSync, writeFileSync, lstatSync } from 'node:fs';
const exec = promisify(execFile);
const sleep = ms => new Promise(resolve => setTimeout(resolve, Math.max(0, ms)));
const DURATION = 600000, RATE = 20, SLOTS = 12000, MAX_LOAD = 11980, MAX_INFLIGHT = 64;
const REQUEST_TIMEOUT = 2000, RECOVERY_LIMIT = 60000;
const SERVICES = ['database','server','office-worker','web','edge'];

export async function verifyReliabilityLoad({ admin, member, page, memberPage, userId, username, expectedDisplayName }) {
  assert.equal(process.env.GITHUB_ACTIONS,'true');
  assert.match(process.env.GITHUB_RUN_ID ?? '',/^\d+$/);
  assert.match(process.env.GITHUB_RUN_ATTEMPT ?? '',/^\d+$/);
  assert.equal(process.env.LUMIRA_BROWSER_CI_MODE,'immutable');
  const project=`lumira-browser-${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}-immutable`;
  const work=resolve('.runtime/browser-ci/immutable');
  const configPath=resolve(process.env.LUMIRA_BROWSER_CI_CONFIG ?? '');
  assert.equal(configPath,resolve(work,'private.json'));
  assert.equal(lstatSync(configPath).isSymbolicLink(),false);
  assert.equal(lstatSync(configPath).mode & 0o077,0);
  const config=JSON.parse(readFileSync(configPath,'utf8'));
  assert.equal(config.runId,process.env.GITHUB_RUN_ID);assert.equal(config.mode,'immutable');
  const compose=['compose','--project-name',project,'--env-file',resolve(work,'private.env'),'-f',resolve('deploy/compose.yml'),'-f',resolve(work,'compose.override.yml')];
  const command=async(args,timeout=15000)=>(await exec('docker',args,{timeout,maxBuffer:2*1024*1024,encoding:'utf8'})).stdout;
  const run=async(args,timeout=15000)=>command([...compose,...args],timeout);
  const ids={};
  async function inspect(id) { return JSON.parse(await command(['inspect','--format','{{json .State}}',id])); }
  async function own(service) {
    const found=(await run(['ps','--all','--quiet',service])).trim().split(/\s+/).filter(Boolean);
    assert.equal(found.length,1);assert.match(found[0],/^[a-f0-9]{12,64}$/);
    if(ids[service]) assert.equal(found[0],ids[service],'Container identity changed during bounded test');
    ids[service]=found[0];
    const labels=JSON.parse(await command(['inspect','--format','{{json .Config.Labels}}',ids[service]]));
    assert.equal(labels['com.docker.compose.project'],project);
    assert.equal(labels['com.docker.compose.service'],service);
    return ids[service];
  }
  for(const service of SERVICES) { await own(service);assert.equal((await inspect(ids[service])).Running,true); }

  // 读取两个独立会话身份，固定修订；不通过重新登录掩盖会话丢失。
  // Bind two distinct identities/revisions; never re-login to conceal lost sessions.
  let issued=0;
  async function identity(ctx) {
    issued++;
    const response=await ctx.request.get('/api/auth/me',{timeout:5000,maxRetries:0});
    try { assert.equal(response.status(),200);return await response.json(); }
    finally { await response.dispose(); }
  }
  const adminMe=await identity(admin), memberMe=await identity(member);
  assert.equal(adminMe.username,'admin');assert.equal(memberMe.username,username);
  assert.equal(memberMe.userId,userId);assert.notEqual(adminMe.userId,memberMe.userId);
  assert.ok(adminMe.authorizationContext?.revision && memberMe.authorizationContext?.revision);
  const sameGrants=(actual,expected)=>JSON.stringify([...(actual.authorities??[])].sort())===JSON.stringify([...(expected.authorities??[])].sort())&&JSON.stringify([...(actual.globalAuthorities??[])].sort())===JSON.stringify([...(expected.globalAuthorities??[])].sort());
  const headers=who=>({'X-Lumira-Authorization-Context-Revision':who.authorizationContext.revision});
  const cases=[
    {name:'admin-user-detail',ctx:admin,path:`/api/platform/users/${userId}`,status:200,who:adminMe,validate:b=>b.id===userId&&b.username===username&&b.displayName===expectedDisplayName},
    {name:'admin-self',ctx:admin,path:'/api/auth/me',status:200,who:adminMe,validate:b=>b.userId===adminMe.userId&&b.username==='admin'&&sameGrants(b,adminMe)},
    {name:'member-users-denied',ctx:member,path:'/api/platform/users',status:403,who:memberMe,validate:b=>!('items' in b)&&!('username' in b)},
    {name:'member-modules-denied',ctx:member,path:'/api/platform/modules',status:403,who:memberMe,validate:b=>!Array.isArray(b)&&!('items' in b)},
    {name:'member-self',ctx:member,path:'/api/auth/me',status:200,who:memberMe,validate:b=>b.userId===userId&&b.username===username&&b.displayName===expectedDisplayName&&sameGrants(b,memberMe)},
  ];
  // 测量期停止 SPA 后台轮询，但保留两个独立会话的 Cookie。
  // Stop SPA polling during the measured interval; request contexts retain their session cookies.
  for(const ctx of [admin,member]) for(const tab of ctx.pages()) await tab.goto('about:blank');
  const rows=[],faults=[],resources=[],infraErrors=[];
  const finalStates={};
  let t0,stop=false,activeFault=null,paused=false,serverKilled=false;
  let skipped=0,scheduled=0,resourceTask,faultTask;
  const inflight=new Set();
  const elapsed=()=>t0===undefined?0:Math.round(performance.now()-t0);
  const reportPath=resolve(work,'evidence/reliability.json');
  async function once(item,track=true,probe=false) {
    const started=elapsed();
    issued++;assert.ok(issued<=12000,'Hard helper-owned HTTP request cap');
    const overlap=()=>faults.find(f=>f.startMs<=elapsed()&&(f.recoveredMs??Infinity)>=started);
    let response,status=0,outcome='unexpected-transport',latencyMs;
    try {
      response=await item.ctx.request.get(item.path,{headers:headers(item.who),timeout:REQUEST_TIMEOUT,maxRetries:0,maxRedirects:0});
      status=response.status();
      if(status===item.status) {
        let body;
        try { body=await response.json(); } catch { outcome='invalid-json';return false; }
        outcome=item.validate(body)?'ok':'content-or-identity-mismatch';
      } else if(overlap()&&[502,503,504].includes(status)) outcome='fault-unavailable';
      else outcome='unexpected-status';
      return outcome==='ok';
    } catch(error) {
      // 仅明确故障窗口中的传输中断可分类为故障影响；401、409、500从不放宽。
      // Only transport failure in explicit fault windows is expected impact; never relax 401/409/500.
      const known=error instanceof Error && /Timeout|ECONNRESET|ECONNREFUSED|socket hang up/.test(error.message);
      outcome=overlap()&&known?'fault-transport':'unexpected-transport';
      return false;
    } finally {
      latencyMs=elapsed()-started;
      if(track) rows.push({case:item.name,startMs:started,latencyMs,status,outcome,fault:overlap()?.kind??null,probe});
      await response?.dispose();
      if(!['ok','fault-unavailable','fault-transport'].includes(outcome)) stop=true;
    }
  }
  async function allProbes() {
    const values=await Promise.all(cases.map(item=>once(item,true,true)));
    return values.every(Boolean);
  }
  assert.equal(await allProbes(),true,'Preflight must pass before any fault');
  rows.length=0;
  async function until(offset) { while(!stop&&elapsed()<offset) await sleep(Math.min(250,offset-elapsed())); }
  async function recover(fault) {
    let consecutive=0;
    while(!stop&&elapsed()-fault.startMs<RECOVERY_LIMIT) {
      // 恢复判定复用已有节拍流量，不另起请求突发。
      // Recovery is established from the existing paced traffic, not an extra request burst.
      const recent=rows.filter(r=>r.startMs>=fault.startMs&&r.startMs>=elapsed()-2000);
      const healthy=cases.every(c=>{const xs=recent.filter(r=>r.case===c.name);return xs.length>=2&&xs.every(r=>r.outcome==='ok');});
      if(healthy) consecutive++;else consecutive=0;
      if(consecutive>=3) {fault.recoveredMs=elapsed();fault.status='recovered';activeFault=null;return;}
      await sleep(500);
    }
    fault.status='recovery-timeout';throw new Error('Bounded recovery timeout');
  }
  async function runFaults() {
    await until(180000);if(stop)return;
    const first={kind:'server-sigkill',startMs:elapsed(),status:'started'};faults.push(first);activeFault=first;
    await own('server');
    serverKilled=true;
    try {
      await run(['kill','--signal','SIGKILL','server']);
      await run(['start','server']);serverKilled=false;
      await recover(first);
    } finally {
      if(serverKilled) {await run(['start','server']);serverKilled=false;}
    }
    await until(360000);if(stop)return;
    const second={kind:'postgres-pause-10s',startMs:elapsed(),status:'started'};faults.push(second);activeFault=second;
    await own('database');
    paused=true;
    try {
      await run(['pause','database']);
      second.pausedAtMs=elapsed();
      await until(second.pausedAtMs+10000);
    } finally {
      // 即使负载或断言失败也必须解冻；外层还有独立恢复与 shell 清理。
      // Always unpause on failure; outer restoration and shell cleanup provide additional protection.
      await run(['unpause','database']);paused=false;
    }
    await recover(second);
  }
  async function sampleResources() {
    for(let slot=0;slot<=20&&!stop;slot++) {
      await until(slot*30000);if(stop)return;
      const output=await command(['stats','--no-stream','--format','{{json .}}',...Object.values(ids)],10000);
      const stats=output.trim().split('\n').filter(Boolean).map(line=>JSON.parse(line));
      const freeBytes=os.freemem();
      assert.ok(freeBytes>=256*1024*1024,'Stop below 256 MiB free runner memory');
      assert.ok(stats.every(s=>Number(s.PIDs)<512),'Stop at excessive per-container process count');
      let database=null;
      if(!activeFault) {
        const value=(await run(['exec','-T','database','psql','-U','lumira_bootstrap','-d','lumira_next','-Atc',"SELECT count(*)::text||'/'||current_setting('max_connections') FROM pg_stat_activity"])).trim();
        const [connections,maximum]=value.split('/').map(Number);
        assert.ok(Number.isFinite(connections)&&connections<maximum*0.8,'Stop before 80% database connection occupancy');
        database={connections,maximum};
      }
      resources.push({atMs:elapsed(),freeBytes,database,containers:stats.map(s=>({id:s.ID,cpu:s.CPUPerc,memory:s.MemUsage,memoryPercent:s.MemPerc,pids:s.PIDs,network:s.NetIO,block:s.BlockIO}))});
    }
  }
  const quantile=(values,p)=>{const sorted=[...values].sort((a,b)=>a-b);return sorted.length?sorted[Math.max(0,Math.ceil(p*sorted.length)-1)]:null;};
  function summary() {
    const load=rows.filter(r=>!r.probe),normal=load.filter(r=>!r.fault),fault=load.filter(r=>r.fault);
    const successful=normal.filter(r=>r.outcome==='ok').map(r=>r.latencyMs);
    const violations=rows.filter(r=>!['ok','fault-unavailable','fault-transport'].includes(r.outcome));
    return {machine:{cpuCount:os.cpus().length,totalMemoryBytes:os.totalmem(),platform:os.platform(),architecture:os.arch()},dataset:{syntheticUsers:2,requestMix:'five equal read cases; admin detail/self, member self/two forbidden lists'},scope:'single disposable GitHub runner; synthetic session read load, not production capacity certification',durationSeconds:600,targetRps:20,maximumLoadRequests:12000,
      issuedIncludingPreflightAndFinalProbes:issued,scheduled,completed:load.length,skipped,probes:rows.length-load.length,
      normalRequests:normal.length,normalErrors:normal.filter(r=>r.outcome!=='ok').length,
      faultRequests:fault.length,faultErrors:fault.filter(r=>r.outcome!=='ok').length,
      violations:violations.length,latencyMs:{p50:quantile(successful,.5),p95:quantile(successful,.95),p99:quantile(successful,.99)},
      cases:Object.fromEntries(cases.map(c=>{const xs=load.filter(r=>r.case===c.name);return [c.name,{count:xs.length,errors:xs.filter(r=>r.outcome!=='ok').length,p95:quantile(xs.filter(r=>r.outcome==='ok'&&!r.fault).map(r=>r.latencyMs),.95)}];})),
      faults,resources,finalStates,infraErrors,requests:rows};
  }
  const terminate=()=>{infraErrors.push('termination-requested');stop=true;};
  process.on('SIGTERM',terminate);process.on('SIGINT',terminate);
  let failure;
  try {
    t0=performance.now();
    faultTask=runFaults().catch(()=>{infraErrors.push('fault-controller-failed');stop=true;});
    resourceTask=sampleResources().catch(()=>{infraErrors.push('resource-sampling-failed');stop=true;});
    let slot=0,lastIssuedAt=-Infinity;
    while(slot<SLOTS&&!stop) {
      await until(slot*1000/RATE);if(stop)break;
      // 迟到任务不与下一任务连续补发。
      // Do not emit a late slot and its successor back-to-back.
      while(!stop&&performance.now()<lastIssuedAt+1000/RATE) await sleep(lastIssuedAt+1000/RATE-performance.now());
      if(stop)break;
      const latest=Math.min(SLOTS,Math.floor(elapsed()*RATE/1000));
      if(latest>slot) {skipped+=latest-slot;slot=latest;}
      if(slot>=SLOTS||elapsed()>=DURATION||scheduled>=MAX_LOAD)break;
      const item=cases[slot%cases.length];slot++;
      if(inflight.size>=MAX_INFLIGHT) {skipped++;continue;}
      scheduled++;lastIssuedAt=performance.now();
      const request=once(item).catch(()=>{infraErrors.push('request-internal-failed');stop=true;});
      inflight.add(request);void request.finally(()=>inflight.delete(request));
    }
    await Promise.all(inflight);await faultTask;await resourceTask;
    assert.equal(await allProbes(),true,'No re-login: existing sessions, edit and permissions must recover');
    // 验证真实页面恢复，不能仅靠负载 API 成功。
    // Verify actual pages recover, not only request probes.
    await page.goto('/dashboard/users',{timeout:15000});
    await page.getByRole('row').filter({hasText:expectedDisplayName}).waitFor({state:'visible',timeout:20000});
    await memberPage.goto('/dashboard/home',{timeout:15000});
    assert.ok(new URL(memberPage.url()).pathname.startsWith('/dashboard/'));
    await memberPage.locator('.pro-account-trigger').waitFor({state:'visible',timeout:20000});
    for(const service of SERVICES) {
      await own(service);const state=await inspect(ids[service]);
      finalStates[service]={running:state.Running,paused:state.Paused,oomKilled:state.OOMKilled,health:state.Health?.Status??null};
      assert.equal(state.Running,true);assert.equal(state.Paused,false);assert.equal(state.OOMKilled,false);
    }
    const result=summary();
    assert.equal(infraErrors.length,0);assert.equal(result.violations,0);
    assert.equal(faults.length,2);assert.ok(faults.every(f=>f.status==='recovered'&&f.recoveredMs-f.startMs<=RECOVERY_LIMIT));
    assert.ok(result.completed>=11760 && result.completed<=SLOTS,'At least 98% paced requests must be dispatched');
    assert.equal(result.normalErrors,0);
    assert.ok(result.latencyMs.p95<=1000&&result.latencyMs.p99<=2000,'Provisional single-runner latency gate');
    assert.ok(resources.length>=20,'Resource curve must cover the 10-minute observation');
  } catch(error) {failure=error.name;}
  finally {
    stop=true;
    process.off('SIGTERM',terminate);process.off('SIGINT',terminate);
    await Promise.allSettled([faultTask,resourceTask,...inflight].filter(Boolean));
    // 不按名称模糊匹配，不操作其它项目；失败恢复本身也导致验收失败。
    // Never use broad container matching; failed restoration is an acceptance failure.
    try {await own('database');if(paused||(await inspect(ids.database)).Paused) await run(['unpause','database']);}
    catch {infraErrors.push('database-finally-restore-failed');}
    try {await own('server');if(serverKilled||!(await inspect(ids.server)).Running) await run(['start','server']);}
    catch {infraErrors.push('server-finally-restore-failed');}
    const result=summary();
    result.status=failure||infraErrors.length?'failed':'passed';
    if(failure)result.failureType=failure;
    writeFileSync(reportPath,JSON.stringify(result,null,2),{mode:0o600});
  }
  assert.equal(failure,undefined,'Reliability acceptance failed; inspect safe reliability.json');
  assert.equal(infraErrors.length,0,'Reliability restoration or infrastructure failed');
}
