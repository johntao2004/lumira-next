import assert from 'node:assert/strict';
import http from 'node:http';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import path from 'node:path';
import { loadQaRunContext } from './qa-run-guard.mjs';

// Real authenticated dictionary CRUD. Other business domains require separate scenarios.
// Never count rejected requests or missed arrivals as successful throughput.
if (process.env.LUMIRA_MIXED_LOAD !== '1') throw new Error('Set LUMIRA_MIXED_LOAD=1 for this isolated QA load test');
const { config, baseURL, projectRoot, databaseName } = loadQaRunContext();
assert.equal(new URL(baseURL).protocol, 'http:', 'This runner requires an isolated loopback HTTP origin');
function integer(name, fallback, maximum) {
  const value = Number(process.env[name] ?? fallback);
  assert.ok(Number.isSafeInteger(value) && value > 0 && value <= maximum, `Invalid ${name}`);
  return value;
}
const userCount = integer('LUMIRA_LOAD_USERS', 1000, 1000);
const rate = integer('LUMIRA_LOAD_RPS', 10000, 10000);
const seconds = integer('LUMIRA_LOAD_SECONDS', 30, 120);
const prefix = `qa_load_${randomUUID().replaceAll('-', '')}`;
const role = randomUUID();
const agent = new http.Agent({ keepAlive: true, maxSockets: userCount, maxFreeSockets: userCount });
const users = Array.from({ length: userCount }, (_, index) => ({
  username: `${prefix}_${index}`, cookies: new Map(), headers: {}, busy: false, step: 0, cycle: 0,
}));
function sql(statement) {
  return execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'], {
    cwd: path.join(projectRoot, 'scripts'), input: statement, encoding: 'utf8', timeout: 30000,
    stdio: ['pipe', 'pipe', 'pipe'],
  }).trim();
}
function request(user, route, method = 'GET', payload, form = false) {
  return new Promise(resolve => {
    const started = performance.now();
    const body = payload === undefined ? undefined : form ? new URLSearchParams(payload).toString() : JSON.stringify(payload);
    let finished = false;
    const finish = (status, data) => {
      if (finished) return;
      finished = true;
      clearTimeout(deadline);
      resolve({ status, data, ms: performance.now() - started });
    };
    const call = http.request(new URL(`/api${route}`, baseURL), {
      agent, method,
      headers: { ...user.headers, Cookie: [...user.cookies].map(([key, value]) => `${key}=${value}`).join('; '),
        ...(body === undefined ? {} : { 'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json', 'Content-Length': Buffer.byteLength(body) }) },
    }, response => {
      for (const cookie of response.headers['set-cookie'] ?? []) {
        const pair = cookie.split(';', 1)[0], separator = pair.indexOf('=');
        user.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
      }
      let content = '';
      response.setEncoding('utf8');
      response.on('data', chunk => {
        content += chunk;
        if (content.length > 1024 * 1024) call.destroy(new Error('Response budget exceeded'));
      });
      response.on('error', () => finish(0));
      response.on('end', () => {
        try { finish(response.statusCode, content ? JSON.parse(content) : null); }
        catch { finish(response.statusCode); }
      });
    });
    const deadline = setTimeout(() => call.destroy(new Error('Request deadline exceeded')), 10000);
    call.on('error', () => finish(0));
    call.end(body);
  });
}
async function csrf(user) {
  const result = await request(user, '/auth/csrf');
  assert.equal(result.status, 200, 'CSRF setup failed');
  assert.equal(typeof result.data?.token, 'string');
  user.headers[result.data.headerName] = result.data.token;
}
async function login(user) {
  await csrf(user);
  const result = await request(user, '/auth/login', 'POST', { username: user.username, password: config.LUMIRA_BOOTSTRAP_PASSWORD }, true);
  const reason = ['loginRateLimited', 'passwordHashCapacity', 'captchaRateLimited'].includes(result.data?.detail)
    ? result.data.detail : 'unexpectedResponse';
  assert.equal(result.status, 200, `Login setup failed (${reason}); no credentials are included in results`);
  const me = await request(user, '/auth/me');
  assert.equal(me.status, 200);
  assert.equal(me.data.username, user.username);
  const revision = me.data.authorizationContext?.revision;
  if (revision) user.headers['X-Lumira-Authorization-Context-Revision'] = revision;
  await csrf(user); // Authentication rotates the CSRF token.
}
const histogram = new Uint32Array(10002);
const metrics = { scheduled: rate * seconds, launched: 0, missedArrivals: 0, missedBusyUsers: 0, missedGeneratorLag: 0, successful: 0, failed: 0, peakInFlight: 0, statuses: {}, operations: {} };
let inFlight = 0;
async function operation(user) {
  const names = ['create', 'read', 'update', 'delete', 'list'];
  const name = names[user.step], root = '/platform/dictionaries';
  const input = { code: `${user.username}_${user.cycle}`, name: 'Isolated capacity fixture', enabled: true, structure: 'FLAT', remark: prefix };
  // A rejected/unknown earlier cycle may leave a fixture until final cleanup. Check
  // this cycle's deleted code exactly, rather than misclassifying old fixtures.
  const route = user.step === 0 ? root : user.step === 4 ? `${root}?search=${user.username}_&filter.code=${input.code}&filterMode.code=exact&pageSize=10`
    : `${root}/${user.record.id}${user.step === 3 ? `?version=${user.record.version}` : ''}`;
  const methods = ['POST', 'GET', 'PUT', 'DELETE', 'GET'];
  const result = await request(user, route, methods[user.step], user.step === 0 ? input : user.step === 2 ? { ...input, name: 'Updated capacity fixture', version: user.record.version } : undefined);
  let valid = result.status === [201, 200, 200, 204, 200][user.step];
  if (user.step === 0) valid &&= typeof result.data?.id === 'string' && result.data.code === input.code;
  if (user.step === 1) valid &&= result.data?.id === user.record.id && result.data.code === input.code;
  if (user.step === 2) valid &&= result.data?.id === user.record.id && result.data.version === user.record.version + 1;
  if (user.step === 4) valid &&= Array.isArray(result.data?.items) && result.data.total === 0;
  metrics.statuses[result.status] = (metrics.statuses[result.status] ?? 0) + 1;
  const bucket = metrics.operations[name] ??= { successful: 0, failed: 0 };
  if (valid) {
    metrics.successful++;
    bucket.successful++;
    histogram[Math.min(10001, Math.floor(result.ms))]++;
    if (user.step === 0 || user.step === 2) user.record = result.data;
    user.step = (user.step + 1) % 5;
    if (user.step === 0) user.cycle++;
  } else {
    metrics.failed++;
    bucket.failed++;
    // A timed-out write has an unknown outcome: do not retry that mutation.
    // A fresh cycle uses a fresh code; exact fixture cleanup runs after draining.
    user.step = 0;
    user.cycle++;
  }
}
let fixtures = false;
try {
  fixtures = true;
  sql(`BEGIN;
    INSERT INTO platform_role(id,code,name,enabled,data_scope) VALUES('${role}','${prefix}','Isolated capacity role',true,'ALL');
    INSERT INTO platform_role_authority(role_id,authority) SELECT '${role}', 'platform:dictionaries:' || action FROM unnest(ARRAY['access','create','update','delete']) action;
    INSERT INTO platform_user(id,username,display_name,password_hash)
      SELECT gen_random_uuid(),'${prefix}_' || n,'Isolated capacity user',password_hash FROM platform_user CROSS JOIN generate_series(0,${userCount - 1}) n WHERE username='admin';
    INSERT INTO platform_user_role(user_id,role_id) SELECT id,'${role}' FROM platform_user WHERE starts_with(username,'${prefix}_');
    COMMIT;`);
  assert.equal(Number(sql(`SELECT count(*) FROM platform_user WHERE starts_with(username,'${prefix}_')`)), userCount);
  // Preserve both source rate limits and the bounded password-hash capacity.
  // Serial setup plus a post-completion delay cannot accumulate hash work when
  // the host slows down. Authentication is outside the measured CRUD interval.
  let prepared = 0;
  for (const user of users) {
    await login(user);
    if (++prepared % 50 === 0) console.error(`Prepared ${prepared}/${userCount} authenticated users`);
    await delay(600);
  }
  const measurementStartedAt = new Date().toISOString();
  const started = performance.now();
  const pending = new Set();
  let arrival = 0;
  while (arrival < metrics.scheduled) {
    const due = Math.min(metrics.scheduled, 1 + Math.floor((performance.now() - started) * rate / 1000));
    while (arrival < due) {
      const user = users[arrival % users.length];
      const late = performance.now() - started - arrival * 1000 / rate > 100;
      arrival++;
      if (late) { metrics.missedArrivals++; metrics.missedGeneratorLag++; continue; }
      if (user.busy) { metrics.missedArrivals++; metrics.missedBusyUsers++; continue; }
      user.busy = true;
      metrics.launched++;
      metrics.peakInFlight = Math.max(metrics.peakInFlight, ++inFlight);
      const work = operation(user).finally(() => { user.busy = false; inFlight--; pending.delete(work); });
      pending.add(work);
    }
    if (arrival < metrics.scheduled) await delay(1);
  }
  await delay(Math.max(0, seconds * 1000 - (performance.now() - started)));
  await Promise.all(pending);
  const measurementFinishedAt = new Date().toISOString();
  assert.equal(metrics.launched + metrics.missedArrivals, metrics.scheduled);
  assert.equal(metrics.missedBusyUsers + metrics.missedGeneratorLag, metrics.missedArrivals);
  const elapsed = (performance.now() - started) / 1000;
  const percentile = fraction => {
    if (!metrics.successful) return null;
    let count = 0;
    for (let i = 0; i < histogram.length; i++) { count += histogram[i]; if (count >= Math.ceil(metrics.successful * fraction)) return i; }
    return null;
  };
  const recovery = await request(users[0], '/platform/dictionaries?pageSize=1');
  const result = { scenario: 'authenticated dictionary CRUD; excludes login and other business domains', databaseName, users: userCount,
    offeredRps: rate, offeredSeconds: seconds, measurementStartedAt, measurementFinishedAt,
    ...metrics, elapsedSeconds: elapsed,
    successfulRps: metrics.successful / elapsed, successfulP95Ms: percentile(.95), successfulP99Ms: percentile(.99),
    recoveryStatus: recovery.status, passedOfferedLoad: metrics.failed === 0 && metrics.missedArrivals === 0 && recovery.status === 200 };
  console.log(JSON.stringify(result, null, 2));
  if (!result.passedOfferedLoad) process.exitCode = 1;
} finally {
  // Retain audit evidence; remove only this run's generated business fixtures and identities.
  try {
    if (fixtures) sql(`BEGIN;
      DELETE FROM platform_dictionary WHERE starts_with(code,'${prefix}_') AND remark='${prefix}';
      DELETE FROM spring_session WHERE starts_with(principal_name,'${prefix}_');
      DELETE FROM platform_user_role WHERE role_id='${role}';
      DELETE FROM platform_user WHERE starts_with(username,'${prefix}_');
      DELETE FROM platform_role WHERE id='${role}' AND code='${prefix}';
      COMMIT;`);
  } finally { agent.destroy(); }
}
