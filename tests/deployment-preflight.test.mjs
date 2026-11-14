// 用合成环境验证离线预检与脱敏失败，不访问部署或真实密钥。
// Verify offline preflight and redacted failures with synthetic environments only.
import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const baseline = {
  LUMIRA_DB_BOOTSTRAP_PASSWORD:'synthetic-bootstrap', LUMIRA_DB_MIGRATION_PASSWORD:'synthetic-migration',
  LUMIRA_DB_PASSWORD:'synthetic-runtime', LUMIRA_BOOTSTRAP_PASSWORD:'synthetic-admin',
  LUMIRA_OFFICE_WORKER_TOKEN:'test-only-worker-token-32-characters', LUMIRA_COOKIE_SECURE:'true',
  LUMIRA_SECRET_KEYS:`primary=${Buffer.alloc(32,7).toString('base64')}`,
};
function run(changes={}) {
  const result=spawnSync('python3',['scripts/deployment-preflight.py'],{encoding:'utf8',env:{PATH:process.env.PATH,...baseline,...changes}});
  assert.equal(result.error,undefined);const report=JSON.parse(result.stdout);
  for(const value of Object.values({...baseline,...changes}).filter(v=>v.length>12)) assert.equal((result.stdout+result.stderr).includes(value),false);
  return {code:result.status,report};
}
test('standard configuration is valid but retains explicit on-site gates',()=>{
  const {code,report}=run();assert.equal(code,0);assert.equal(report.status,'configuration-valid');assert.equal(report.requiresOnSiteVerification.length,6);
});
test('missing and shared credentials fail without leaking values',()=>{
  for(const change of [{LUMIRA_DB_PASSWORD:''},{LUMIRA_DB_PASSWORD:baseline.LUMIRA_DB_MIGRATION_PASSWORD},{LUMIRA_OFFICE_WORKER_TOKEN:'short'},{LUMIRA_COOKIE_SECURE:'false'},{LUMIRA_METRICS_TOKEN:'short'}])assert.equal(run(change).code,1);
});
test('keyring validation rejects malformed, duplicate, wrong-length and missing active keys',()=>{
  for(const keys of ['', 'primary=invalid!', `primary=${Buffer.alloc(31).toString('base64')}`, `${baseline.LUMIRA_SECRET_KEYS},${baseline.LUMIRA_SECRET_KEYS}`])assert.equal(run({LUMIRA_SECRET_KEYS:keys}).code,1);
  assert.equal(run({LUMIRA_SECRET_ACTIVE_KEY:'absent'}).code,1);
  assert.equal(run({LUMIRA_SECRET_KEYS:Array.from({length:17},(_,i)=>`key${i}=${Buffer.alloc(32,7).toString('base64')}`).join(','),LUMIRA_SECRET_ACTIVE_KEY:'key0'}).code,1);
  assert.equal(run({LUMIRA_OFFICE_WORKER_TOKEN:'x'.repeat(257)}).code,1);
  assert.equal(run({LUMIRA_SECRET_KEYS:`old=${Buffer.alloc(32,8).toString('base64')},${baseline.LUMIRA_SECRET_KEYS}`}).code,0);
});
test('custom network must keep edge in subnet outside the allocation pool',()=>{
  for(const change of [{LUMIRA_EDGE_ADDRESS:'172.30.78.1'},{LUMIRA_EDGE_ADDRESS:'172.30.78.129'},{LUMIRA_EDGE_ADDRESS:'172.30.79.2'},{LUMIRA_PROXY_POOL:'192.168.1.0/24'},{LUMIRA_EDGE_ADDRESS:'bad'}])assert.equal(run(change).code,1);
  assert.equal(run({LUMIRA_PROXY_SUBNET:'10.45.0.0/24',LUMIRA_PROXY_POOL:'10.45.0.128/25',LUMIRA_EDGE_ADDRESS:'10.45.0.2'}).code,0);
});
