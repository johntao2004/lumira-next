import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';
import { loadLocaleMessages } from '../apps/web/src/platform/i18n/messages.ts';
import { assertQaRunGuard } from './qa-run-guard.mjs';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const configuredPath = process.env.LUMIRA_SMOKE_CONFIG;
const baseURL = process.env.BASE_URL;
if (!configuredPath || !baseURL) {
  throw new Error('Set LUMIRA_SMOKE_CONFIG to a private isolated QA config and BASE_URL to its loopback origin.');
}
const configPath = path.isAbsolute(configuredPath) ? configuredPath : path.resolve(projectRoot, configuredPath);
assert.ok(existsSync(configPath), 'The explicitly selected smoke config must exist');
const parsedBase = new URL(baseURL ?? 'http://127.0.0.1:8010');
assert.ok(['127.0.0.1', 'localhost', '::1'].includes(parsedBase.hostname), 'Browser QA must target a loopback app');
assert.ok(['http:', 'https:'].includes(parsedBase.protocol));
assert.equal(parsedBase.username, '', 'Browser QA URL must not embed credentials');
assert.equal(parsedBase.password, '', 'Browser QA URL must not embed credentials');
assert.equal(parsedBase.pathname, '/', 'Browser QA URL must be an origin without a path');
assert.equal(parsedBase.search, '');
assert.equal(parsedBase.hash, '');
const config = JSON.parse(readFileSync(configPath, 'utf8'));
assertQaRunGuard({ projectRoot, configPath, config, baseURL });
const c3Only = process.env.LUMIRA_FS01_C3_ONLY === '1';
const holdStaleA401 = process.env.LUMIRA_HOLD_STALE_A_401 === '1';
const databaseUrl = new URL(String(config.LUMIRA_DB_URL ?? '').replace(/^jdbc:/, ''));
const databaseName = decodeURIComponent(databaseUrl.pathname.replace(/^\//, ''));
assert.equal(databaseUrl.protocol, 'postgresql:', 'Fixture SQL requires a PostgreSQL QA database');
assert.ok(['127.0.0.1', 'localhost', '::1'].includes(databaseUrl.hostname), 'Fixture SQL must target a loopback PostgreSQL host');
assert.match(databaseName, /^lumira_(?:security|qa|test|smoke)_[a-z0-9_]+$/, 'Fixture SQL requires the dedicated Lumira QA database namespace');
const bootstrapUsername = process.env.LUMIRA_SMOKE_USERNAME ?? config.LUMIRA_BOOTSTRAP_USERNAME ?? config.username ?? 'admin';
const password = config.LUMIRA_BOOTSTRAP_PASSWORD ?? config.bootstrapPassword ?? config.password;
assert.equal(typeof password, 'string');
assert.ok(password.length > 0, 'The isolated QA config must provide a bootstrap password');

const t = await loadLocaleMessages('zh-CN');
const suffix = randomSuffix();
const userAId = cryptoRandomUuid();
const userBId = cryptoRandomUuid();
const userA = `qa_file_identity_a_${suffix}`;
const userB = `qa_file_identity_b_${suffix}`;
const files = [
  { name: `qa-identity-batch-${suffix}-1.txt`, content: 'identity batch fixture one' },
  { name: `qa-identity-batch-${suffix}-2.txt`, content: 'identity batch fixture two' },
];
const fileIds = new Set();
const sqlQuote = value => `'${String(value).replaceAll("'", "''")}'`;
const sql = statement => execFileSync('python3', ['-c', 'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))'], {
  cwd: path.join(projectRoot, 'scripts'),
  env: { ...process.env, LUMIRA_SMOKE_CONFIG: configPath, BASE_URL: parsedBase.origin },
  input: statement,
  encoding: 'utf8',
  stdio: ['pipe', 'pipe', 'pipe'],
}).trim();

function randomSuffix() {
  return cryptoRandomUuid().replaceAll('-', '').slice(0, 12);
}

function cryptoRandomUuid() {
  return randomUUID();
}

function createUsers() {
  const source = sqlQuote(bootstrapUsername);
  const setup = sql(`BEGIN;
    INSERT INTO platform_user(id,username,display_name,password_hash,enabled)
      SELECT ${sqlQuote(userAId)},${sqlQuote(userA)},'File identity QA A',password_hash,true
      FROM platform_user WHERE username=${source};
    INSERT INTO platform_user(id,username,display_name,password_hash,enabled)
      SELECT ${sqlQuote(userBId)},${sqlQuote(userB)},'File identity QA B',password_hash,true
      FROM platform_user WHERE username=${source};
    INSERT INTO platform_authority(user_id,authority)
      SELECT ${sqlQuote(userAId)},authority FROM (VALUES
        ('platform:files:read'),('platform:files:write'),
        ('platform:dictionaries:read'),('platform:dictionaries:write'),
        ('platform:settings:read'),('platform:settings:write'),
        ('platform:navigation:read'),('platform:modules:read')
      ) AS grants(authority);
    INSERT INTO platform_authority(user_id,authority)
      SELECT ${sqlQuote(userBId)},authority FROM (VALUES
        ('platform:files:read'),('platform:files:write'),
        ('platform:dictionaries:read'),('platform:dictionaries:write'),
        ('platform:settings:read'),('platform:settings:write'),
        ('platform:navigation:read'),('platform:modules:read')
      ) AS grants(authority);
    COMMIT;
    SELECT count(*) FROM platform_user WHERE id IN (${sqlQuote(userAId)},${sqlQuote(userBId)});`);
  assert.equal(setup.split(/\r?\n/).at(-1), '2', 'Both synthetic users must be cloned from the configured QA bootstrap identity');
  const grants = sql(`SELECT count(DISTINCT user_id) FROM platform_authority
    WHERE user_id IN (${sqlQuote(userAId)},${sqlQuote(userBId)})
      AND authority IN ('platform:files:read','platform:files:write','platform:dictionaries:read','platform:dictionaries:write','platform:settings:read','platform:settings:write','platform:navigation:read','platform:modules:read');`);
  assert.equal(grants, '2', 'Both synthetic users must receive only the eight permissions needed for these feature pages and app shell');
}

function cleanupFixtures() {
  const ids = [...fileIds].map(sqlQuote);
  const filePredicate = ids.length ? `id IN (${ids.join(',')})` : 'false';
  const chunkPredicate = ids.length ? `file_id IN (${ids.join(',')})` : 'false';
  sql(`BEGIN;
    DELETE FROM platform_audit WHERE actor IN (${sqlQuote(userA)},${sqlQuote(userB)}) OR target_id IN (SELECT id FROM platform_file WHERE owner_id IN (${sqlQuote(userAId)},${sqlQuote(userBId)}));
    DELETE FROM platform_file_blob_chunk WHERE file_id IN (SELECT id FROM platform_file WHERE owner_id IN (${sqlQuote(userAId)},${sqlQuote(userBId)})) OR ${chunkPredicate};
    DELETE FROM platform_file WHERE owner_id IN (${sqlQuote(userAId)},${sqlQuote(userBId)}) OR ${filePredicate};
    DELETE FROM spring_session_attributes WHERE session_primary_id IN (SELECT primary_id FROM spring_session WHERE principal_name IN (${sqlQuote(userA)},${sqlQuote(userB)}));
    DELETE FROM spring_session WHERE principal_name IN (${sqlQuote(userA)},${sqlQuote(userB)});
    DELETE FROM platform_user WHERE id IN (${sqlQuote(userAId)},${sqlQuote(userBId)}) AND username IN (${sqlQuote(userA)},${sqlQuote(userB)});
    COMMIT;`);
}

async function saveIdentitySwitchFailure(page, trace, error, artifactName = 'b-account-load-failure') {
  const artifactDir = path.join(projectRoot, '.runtime', 'security-integration-20261009', `files-identity-boundary-${suffix}`);
  mkdirSync(artifactDir, { recursive: true });
  const screenshotPath = path.join(artifactDir, `fs01-c2-${artifactName}.png`);
  const reportPath = path.join(artifactDir, `fs01-c2-${artifactName}.json`);
  let pageState = {};
  try {
    pageState = await page.evaluate(() => ({
      url: location.href,
      title: document.title,
      bodyText: document.body?.innerText?.slice(0, 12_000) ?? '',
      accountTriggerText: document.querySelector('.pro-account-trigger')?.textContent?.trim() ?? null,
      importButtonCount: [...document.querySelectorAll('button')].filter(button => button.innerText.includes('导入字典')).length,
    }));
  } catch (stateError) {
    pageState = { captureError: String(stateError) };
  }
  try { await page.screenshot({ path: screenshotPath, fullPage: true }); } catch (screenshotError) {
    pageState.screenshotError = String(screenshotError);
  }
  trace.responseBodiesSettled = await settleTraceResponses(trace);
  const report = {
    scenario: 'FS01-C2 in-place shared-cookie A-to-B switch must render B account and dictionary controls on tab A',
    failedAt: new Date().toISOString(),
    error: String(error),
    steps: trace.steps,
    transitions: trace.transitions ?? [],
    pageState,
    apiResponses: trace.responses,
    responseBodiesSettled: trace.responseBodiesSettled,
    consoleErrors: trace.consoleErrors,
    pageErrors: trace.pageErrors,
    sessionApiTrace: await page.evaluate(() => window.__qaSessionApiTrace ?? []),
  };
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  return { screenshotPath, reportPath, pageState };
}

async function loginThroughUi(page, username, timeoutMs = 30_000) {
  const authTrace = { requests: [], responses: [], requestFailures: [], pendingResponses: [] };
  const isTrackedAuthPath = pathname => ['/api/auth/csrf', '/api/auth/login', '/api/auth/me'].includes(pathname);
  const onRequest = request => {
    const url = new URL(request.url());
    if (isTrackedAuthPath(url.pathname)) authTrace.requests.push({ at: new Date().toISOString(), method: request.method(), path: url.pathname });
  };
  const onResponse = response => {
    const url = new URL(response.url());
    if (!isTrackedAuthPath(url.pathname)) return;
    const entry = { at: new Date().toISOString(), method: response.request().method(), path: url.pathname, status: response.status() };
    authTrace.responses.push(entry);
    if (url.pathname === '/api/auth/me' && response.status() === 200) {
      authTrace.pendingResponses.push(response.json().then(identity => { entry.userId = identity.userId; }).catch(() => {}));
    } else if (url.pathname === '/api/auth/login') {
      authTrace.pendingResponses.push(captureLoginOutcome(response, entry));
    }
  };
  const onRequestFailed = request => {
    const url = new URL(request.url());
    if (isTrackedAuthPath(url.pathname)) authTrace.requestFailures.push({
      at: new Date().toISOString(),
      method: request.method(),
      path: url.pathname,
      failure: request.failure()?.errorText ?? null,
    });
  };
  page.on('request', onRequest);
  page.on('response', onResponse);
  page.on('requestfailed', onRequestFailed);
  try {
    await page.goto('/user/login');
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    const identityResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/me' && response.status() === 200, { timeout: timeoutMs })
      .then(response => ({ response }), error => ({ error }));
    await Promise.all([
      page.waitForURL(url => url.pathname.startsWith('/dashboard/'), { timeout: timeoutMs }),
      page.getByRole('button', { name: /登\s*录/ }).click(),
    ]);
    const responseResult = await identityResponse;
    if (responseResult.error) throw responseResult.error;
    const identity = await responseResult.response.json();
    assert.ok(identity.userId, 'Authenticated browser login must establish a stable server identity');
    return identity;
  } catch (error) {
    authTrace.responseBodiesSettled = await settleTraceResponses(authTrace);
    authTrace.pageState = await page.evaluate(() => ({
      pathname: location.pathname,
      search: location.search,
      accountTriggerText: document.querySelector('.pro-account-trigger')?.textContent?.trim() ?? null,
      bodyText: document.body?.innerText?.slice(0, 4000) ?? '',
    })).catch(captureError => ({ captureError: String(captureError) }));
    error.qaLoginAttempt = authTrace;
    throw error;
  } finally {
    page.off('request', onRequest);
    page.off('response', onResponse);
    page.off('requestfailed', onRequestFailed);
  }
}

function trackIdentityApi(page, trace, tab) {
  trace.apiEvents ??= [];
  trace.pendingResponses ??= [];
  const trackedPaths = new Set([
    '/api/auth/csrf', '/api/auth/login', '/api/auth/logout', '/api/auth/me',
    '/api/platform/settings/file-retention',
  ]);
  const onRequest = request => {
    const url = new URL(request.url());
    if (trackedPaths.has(url.pathname)) trace.apiEvents.push({ at: new Date().toISOString(), tab, event: 'request', method: request.method(), path: url.pathname });
  };
  const onResponse = response => {
    const url = new URL(response.url());
    if (!trackedPaths.has(url.pathname)) return;
    const entry = { at: new Date().toISOString(), tab, event: 'response', method: response.request().method(), path: url.pathname, status: response.status() };
    trace.apiEvents.push(entry);
    if (url.pathname === '/api/auth/login') trace.pendingResponses.push(captureLoginOutcome(response, entry));
    if (url.pathname === '/api/auth/me' && response.status() === 200) {
      trace.pendingResponses.push(response.json().then(identity => { entry.userId = identity.userId ?? null; }).catch(() => {}));
    }
  };
  const onRequestFailed = request => {
    const url = new URL(request.url());
    if (trackedPaths.has(url.pathname)) trace.apiEvents.push({
      at: new Date().toISOString(), tab, event: 'request-failed', method: request.method(), path: url.pathname,
      failure: request.failure()?.errorText ?? null,
    });
  };
  page.on('request', onRequest);
  page.on('response', onResponse);
  page.on('requestfailed', onRequestFailed);
  return () => {
    page.off('request', onRequest);
    page.off('response', onResponse);
    page.off('requestfailed', onRequestFailed);
  };
}

function waitForIdentity(page, userId, timeoutMs = 30_000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      page.off('response', inspect);
      reject(new Error(`The first tab did not accept the expected account identity ${userId}`));
    }, timeoutMs);
    const inspect = async response => {
      if (new URL(response.url()).pathname !== '/api/auth/me' || response.status() !== 200) return;
      try {
        const identity = await response.json();
        if (identity.userId !== userId) return;
        clearTimeout(timeout);
        page.off('response', inspect);
        resolve(identity);
      } catch {
        // Other /auth/me responses may be in flight while the shared cookie changes.
      }
    };
    page.on('response', inspect);
  });
}

async function waitForIdentityOrAnonymousBoundary(page, userId, previousInvalidations, timeoutMs = 15_000) {
  let acceptedIdentity = null;
  let unauthorizedMeCount = 0;
  const pendingIdentityReads = [];
  const inspectResponse = response => {
    if (new URL(response.url()).pathname !== '/api/auth/me') return;
    if (response.status() === 401) {
      unauthorizedMeCount += 1;
      return;
    }
    if (response.status() !== 200) return;
    pendingIdentityReads.push(response.json().then(identity => {
      if (identity.userId === userId) acceptedIdentity = identity;
    }).catch(() => {}));
  };
  page.on('response', inspectResponse);
  const deadline = Date.now() + timeoutMs;
  try {
    while (Date.now() < deadline) {
      await Promise.allSettled(pendingIdentityReads);
      if (acceptedIdentity) return { kind: 'direct', identity: acceptedIdentity, unauthorizedMeCount };
      const state = await page.evaluate(() => ({
        pathname: location.pathname,
        invalidations: window.__fileIdentitySmoke?.invalidations ?? 0,
      })).catch(() => ({ pathname: null, invalidations: 0 }));
      if (unauthorizedMeCount > 0 && state.invalidations > previousInvalidations && state.pathname === '/user/login') {
        // Allow a target-account /auth/me request already in flight to win before
        // classifying the cleared-cookie interval as anonymous.
        await new Promise(resolve => setTimeout(resolve, 400));
        await Promise.allSettled(pendingIdentityReads);
        if (acceptedIdentity) return { kind: 'direct', identity: acceptedIdentity, unauthorizedMeCount };
        const stableState = await page.evaluate(() => ({
          pathname: location.pathname,
          invalidations: window.__fileIdentitySmoke?.invalidations ?? 0,
        })).catch(() => ({ pathname: null, invalidations: 0 }));
        if (stableState.pathname === '/user/login' && stableState.invalidations > previousInvalidations) {
          return { kind: 'anonymous', unauthorizedMeCount, invalidations: stableState.invalidations };
        }
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error(`Tab A observed neither target identity ${userId} nor a stable post-logout anonymous boundary (401 /auth/me count ${unauthorizedMeCount})`);
  } finally {
    page.off('response', inspectResponse);
  }
}

async function submitFreshLoginOnCurrentPage(page, username, timeoutMs = 30_000) {
  assert.equal(new URL(page.url()).pathname, '/user/login', 'An explicit anonymous-boundary recovery must begin on the existing login page');
  let loginPostRequestCount = 0;
  const countLoginPost = request => {
    const url = new URL(request.url());
    if (url.pathname === '/api/auth/login' && request.method() === 'POST') loginPostRequestCount += 1;
  };
  page.on('request', countLoginPost);
  let identity;
  try {
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    const loginResponsePromise = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/auth/login' && response.request().method() === 'POST',
    { timeout: timeoutMs }).then(response => ({ response }), error => ({ error }));
    const identityResponsePromise = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/auth/me' && response.status() === 200,
    { timeout: timeoutMs }).then(response => ({ response }), error => ({ error }));
    const dashboardPromise = page.waitForURL(url => url.pathname.startsWith('/dashboard/'), { timeout: timeoutMs })
      .then(() => ({ reached: true }), error => ({ error }));
    await page.getByRole('button', { name: /登\s*录/ }).click();
    const loginResult = await loginResponsePromise;
    if (loginResult.error) throw loginResult.error;
    assert.equal(loginResult.response.status(), 200, 'The new explicit login gesture must produce one successful login response');
    const identityResult = await identityResponsePromise;
    if (identityResult.error) throw identityResult.error;
    identity = await identityResult.response.json();
    assert.ok(identity.userId, 'The fresh explicit login must establish a stable server identity');
    const dashboardResult = await dashboardPromise;
    if (dashboardResult.error) throw dashboardResult.error;
  } finally {
    page.off('request', countLoginPost);
  }
  assert.equal(loginPostRequestCount, 1, 'A fresh explicit UI gesture must submit exactly one login POST');
  identity.qaExplicitLoginPostCount = loginPostRequestCount;
  return identity;
}

async function saveC1IdentitySwitchEvidence(context, pageA, pageB, trace, error) {
  const artifactDir = path.join(projectRoot, '.runtime', 'security-integration-20261009', `files-identity-boundary-${suffix}`);
  mkdirSync(artifactDir, { recursive: true });
  const reportPath = path.join(artifactDir, 'fs01-c1-identity-switch-failure.json');
  const pageState = async page => page.evaluate(() => ({
    url: location.href,
    pathname: location.pathname,
    title: document.title,
    bodyText: document.body?.innerText?.slice(0, 8_000) ?? '',
    accountTriggerText: document.querySelector('.pro-account-trigger')?.textContent?.trim() ?? null,
    invalidations: window.__fileIdentitySmoke?.invalidations ?? null,
  })).catch(captureError => ({ captureError: String(captureError) }));
  const serverIdentity = await context.request.get('/api/auth/me').then(async response => ({
    status: response.status(),
    userId: response.status() === 200 ? (await response.json()).userId ?? null : null,
  }), requestError => ({ status: null, requestError: String(requestError) }));
  const states = await Promise.all([pageState(pageA), pageState(pageB)]);
  trace.responseBodiesSettled = await settleTraceResponses(trace);
  for (const [page, filename] of [[pageA, 'fs01-c1-tab-a-identity-switch-failure.png'], [pageB, 'fs01-c1-tab-b-identity-switch-failure.png']]) {
    try { await page.screenshot({ path: path.join(artifactDir, filename), fullPage: true }); } catch {}
  }
  const report = {
    scenario: 'FS01-C1 settings confirmation must retire across a shared-cookie account switch',
    failedAt: new Date().toISOString(),
    error: String(error),
    primaryErrorName: error?.name ?? 'Error',
    serverIdentity,
    pageA: states[0],
    pageB: states[1],
    switchTrace: trace,
    recoveryEvidence: error?.qaSwitchEvidence ?? null,
    authAttempt: error?.qaLoginAttempt ?? null,
  };
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  return reportPath;
}

async function saveC3IdentitySwitchEvidence(page, trace, error, serverIdentity, peerPage, peerTrace, diagnostics = {}) {
  const artifactDir = path.join(projectRoot, '.runtime', 'security-integration-20261009', `files-identity-boundary-${suffix}`);
  mkdirSync(artifactDir, { recursive: true });
  const screenshotPath = path.join(artifactDir, 'fs01-c3-tab-a-identity-timeout.png');
  const reportPath = path.join(artifactDir, 'fs01-c3-tab-a-identity-timeout.json');
  let pageState = {};
  let peerPageState = null;
  try {
    pageState = await page.evaluate(() => ({
      url: location.href,
      pathname: location.pathname,
      title: document.title,
      bodyText: document.body?.innerText?.slice(0, 12_000) ?? '',
      accountTriggerText: document.querySelector('.pro-account-trigger')?.textContent?.trim() ?? null,
      invalidations: window.__fileIdentitySmoke?.invalidations ?? null,
      loginButtonCount: [...document.querySelectorAll('button')].filter(button => /登\s*录|sign in|log in/i.test(button.innerText)).length,
    }));
  } catch (stateError) {
    pageState = { captureError: String(stateError) };
  }
  try { await page.screenshot({ path: screenshotPath, fullPage: true }); } catch (screenshotError) {
    pageState.screenshotError = String(screenshotError);
  }
  if (peerPage) {
    peerPageState = await peerPage.evaluate(() => ({
      url: location.href,
      pathname: location.pathname,
      title: document.title,
      bodyText: document.body?.innerText?.slice(0, 12_000) ?? '',
      accountTriggerText: document.querySelector('.pro-account-trigger')?.textContent?.trim() ?? null,
    })).catch(error => ({ captureError: String(error) }));
    try {
      await peerPage.screenshot({ path: path.join(artifactDir, 'fs01-c3-tab-b-login-state.png'), fullPage: true });
    } catch (screenshotError) {
      peerPageState.screenshotError = String(screenshotError);
    }
  }
  const report = {
    scenario: 'FS01-C3 held file-batch request during shared-cookie A-to-B change',
    failedAt: new Date().toISOString(),
    error: String(error),
    classification: pageState.pathname === '/user/login' && serverIdentity?.userId === userBId
      ? 'tab-a-redirected-to-login-during-anonymous-cookie-window; shared-server-cookie-is-now-B'
      : 'tab-a-did-not-observe-B-identity-and-is-not-an-expected-login-redirect',
    pageState,
    serverIdentity,
    sessionApiTrace: trace.sessionApiTrace,
    peerPageState,
    peerSessionApiTrace: peerTrace?.sessionApiTrace ?? [],
    apiResponses: trace.responses,
    peerApiResponses: peerTrace?.responses ?? [],
    requests: trace.requests,
    peerRequests: peerTrace?.requests ?? [],
    held401s: trace.held401s ?? [],
    peerHeld401s: peerTrace?.held401s ?? [],
    loginAttempts: peerTrace?.loginAttempts ?? [],
    serverIdentityBeforeRetry: peerTrace?.serverIdentityBeforeRetry ?? null,
    requestFailures: trace.requestFailures,
    peerRequestFailures: peerTrace?.requestFailures ?? [],
    diagnostics,
    consoleErrors: trace.consoleErrors,
    pageErrors: trace.pageErrors,
    responseBodiesSettled: trace.responseBodiesSettled ?? null,
    peerResponseBodiesSettled: peerTrace?.responseBodiesSettled ?? null,
  };
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  return { screenshotPath, reportPath, pageState, peerPageState, classification: report.classification };
}

async function captureLoginOutcome(response, entry) {
  try {
    const body = await response.json();
    const candidates = [body?.message, body?.detail, body?.code, body?.error].filter(value => typeof value === 'string');
    const knownErrors = new Set([
      'captchaRequired', 'captchaUnavailable', 'passwordHashCapacity', 'loginRateLimited', 'loginFailed',
      'maintenanceActive', 'forbidden', 'secondFactorUnavailable', 'accountDisabled',
    ]);
    entry.loginOutcome = {
      state: typeof body?.state === 'string' ? body.state : null,
      authenticated: typeof body?.authenticated === 'boolean' ? body.authenticated : null,
      secondFactorRequired: typeof body?.secondFactorRequired === 'boolean' ? body.secondFactorRequired : null,
      errorCode: candidates.find(value => knownErrors.has(value)) ?? null,
    };
  } catch {
    entry.loginOutcome = { state: null, authenticated: null, secondFactorRequired: null, errorCode: null };
  }
}

async function settleTraceResponses(trace, timeoutMs = 2_000) {
  let timeoutId;
  const settled = await Promise.race([
    Promise.allSettled(trace.pendingResponses).then(() => true),
    new Promise(resolve => { timeoutId = setTimeout(() => resolve(false), timeoutMs); }),
  ]);
  clearTimeout(timeoutId);
  return settled;
}

async function waitForStableLoginBoundary(pages, traces, timeoutMs = 5_000, stableMs = 400) {
  const deadline = Date.now() + timeoutMs;
  let previous = null;
  let stableSince = 0;
  while (Date.now() < deadline) {
    const pageStates = await Promise.all(pages.map(page => page.evaluate(() => ({
      pathname: location.pathname,
      invalidations: window.__fileIdentitySmoke?.invalidations ?? 0,
    })).catch(() => ({ pathname: null, invalidations: 0 }))));
    const invalidations = traces.map(trace => trace.sessionApiTrace.filter(entry => entry.event === 'invalidated').length);
    const snapshot = JSON.stringify({ pageStates, invalidations });
    const hasInvalidation = invalidations.some(count => count > 0) || pageStates.some(state => state.invalidations > 0);
    const bothOnLogin = pageStates.every(state => state.pathname === '/user/login');
    if (hasInvalidation && bothOnLogin && snapshot === previous) {
      if (Date.now() - stableSince >= stableMs) return { pageStates, invalidations };
    } else {
      stableSince = Date.now();
    }
    previous = snapshot;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('The two tabs did not reach a stable anonymous login boundary after nonce invalidation');
}

async function switchSharedCookieTo(context, pageA, pageB, targetUsername, targetUserId, targetDisplayName, trace = null, requireAnonymousBeforePeerLogin = false) {
  const previousInvalidations = await pageA.evaluate(() => window.__fileIdentitySmoke?.invalidations ?? 0);
  const protectedPath = await pageA.evaluate(() => `${location.pathname}${location.search}${location.hash}`);
  const peerProtectedPath = await pageB.evaluate(() => `${location.pathname}${location.search}${location.hash}`);
  const transitionPromise = waitForIdentityOrAnonymousBoundary(pageA, targetUserId, previousInvalidations);
  const csrf = await (await context.request.get('/api/auth/csrf')).json();
  const logout = await context.request.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } });
  assert.equal(logout.status(), 204, 'QA API logout must clear the shared browser-context cookie');
  await pageB.goto('/user/login');
  let transition = null;
  if (requireAnonymousBeforePeerLogin) {
    transition = await transitionPromise;
    assert.equal(transition.kind, 'anonymous', 'The controlled anonymous-boundary case must observe A receive 401 and reach the login route before B logs in');
  }
  let targetIdentity = null;
  let initialLoginError = null;
  try {
    targetIdentity = await loginThroughUi(pageB, targetUsername, 15_000);
    assert.equal(targetIdentity.userId, targetUserId);
  } catch (error) {
    initialLoginError = error;
    const attempt = error.qaLoginAttempt ?? { requests: [], responses: [], requestFailures: [], pageState: null };
    const firstServerIdentityResponse = await context.request.get('/api/auth/me');
    const firstServerIdentity = firstServerIdentityResponse.status() === 200 ? await firstServerIdentityResponse.json() : null;
    const loginPostRequests = attempt.requests.filter(entry => entry.method === 'POST' && entry.path === '/api/auth/login');
    const loginPostResponses = attempt.responses.filter(entry => entry.method === 'POST' && entry.path === '/api/auth/login');
    const loginPostWasAborted = attempt.requestFailures.some(entry => entry.method === 'POST'
      && entry.path === '/api/auth/login' && /ERR_ABORTED/i.test(entry.failure ?? ''));
    const attemptRecord = {
      at: new Date().toISOString(),
      targetUserId,
      phase: 'peer-login-first-user-action',
      result: 'dashboard-transition-timeout',
      errorName: error?.name ?? 'Error',
      requests: attempt.requests,
      responses: attempt.responses,
      requestFailures: attempt.requestFailures,
      pageState: attempt.pageState,
      serverIdentityStatus: firstServerIdentityResponse.status(),
      serverIdentityUserId: firstServerIdentity?.userId ?? null,
      recovery: null,
    };
    if (trace) {
      trace.loginAttempts ??= [];
      trace.loginAttempts.push(attemptRecord);
    }

    try {
      if (firstServerIdentityResponse.status() === 200 && firstServerIdentity?.userId === targetUserId) {
        const pageBState = attempt.pageState ?? await pageB.evaluate(() => ({ pathname: location.pathname })).catch(() => null);
        if (pageBState?.pathname === '/user/login') {
          const expectedTarget = waitForIdentity(pageB, targetUserId, 12_000).then(identity => ({ identity }), navError => ({ error: navError }));
          await pageB.goto(peerProtectedPath);
          const identityResult = await expectedTarget;
          if (identityResult.error) throw identityResult.error;
          await pageB.locator('.pro-account-trigger').filter({ hasText: targetDisplayName }).waitFor({ state: 'visible', timeout: 10_000 });
          targetIdentity = firstServerIdentity;
          attemptRecord.recovery = 'server-authenticated-peer-protected-navigation';
        } else {
          const accountVisible = await pageB.locator('.pro-account-trigger').filter({ hasText: targetDisplayName }).isVisible().catch(() => false);
          assert.ok(accountVisible, 'Server-confirmed B must render B controls already or after an explicit protected-route navigation from login');
          targetIdentity = firstServerIdentity;
          attemptRecord.recovery = 'server-authenticated-peer-already-rendered';
        }
      } else if (firstServerIdentityResponse.status() === 401) {
        const noLoginPostWasSent = loginPostRequests.length === 0;
        const onlyLoginPostWasCancelled = loginPostRequests.length === 1 && loginPostResponses.length === 0 && loginPostWasAborted;
        assert.ok(noLoginPostWasSent || onlyLoginPostWasCancelled,
          'A new login UI action is permitted only when the first login POST was absent or explicitly browser-aborted without a response');
        const stableBoundary = await waitForStableLoginBoundary([pageA, pageB], [{ sessionApiTrace: [] }, { sessionApiTrace: [] }]);
        attemptRecord.stableBoundary = stableBoundary;
        const settledServerIdentity = await context.request.get('/api/auth/me');
        attemptRecord.serverIdentityAfterBoundaryStatus = settledServerIdentity.status();
        if (settledServerIdentity.status() === 200) {
          const identity = await settledServerIdentity.json();
          attemptRecord.serverIdentityAfterBoundaryUserId = identity.userId ?? null;
          assert.equal(identity.userId, targetUserId, 'A newly authenticated target may be recovered by route navigation without repeating credentials');
          const expectedTarget = waitForIdentity(pageB, targetUserId, 12_000).then(value => ({ identity: value }), navError => ({ error: navError }));
          await pageB.goto(peerProtectedPath);
          const identityResult = await expectedTarget;
          if (identityResult.error) throw identityResult.error;
          await pageB.locator('.pro-account-trigger').filter({ hasText: targetDisplayName }).waitFor({ state: 'visible', timeout: 10_000 });
          targetIdentity = identity;
          attemptRecord.recovery = 'server-authenticated-peer-protected-navigation-after-anonymous-boundary';
        } else {
          assert.equal(settledServerIdentity.status(), 401, 'Only a still-anonymous server cookie may lead to one new explicit login gesture');
          const explicitIdentity = await submitFreshLoginOnCurrentPage(pageB, targetUsername);
          assert.equal(explicitIdentity.userId, targetUserId);
          targetIdentity = explicitIdentity;
          attemptRecord.recovery = 'one-explicit-login-after-stable-anonymous-boundary';
          attemptRecord.explicitLoginPostCount = 1;
        }
      } else {
        assert.equal(firstServerIdentityResponse.status(), 200, 'Peer login recovery only accepts a target-authenticated cookie or a confirmed anonymous cookie');
        assert.equal(firstServerIdentity?.userId, targetUserId, 'The server identity after the first login action must match the target');
      }
    } catch (recoveryError) {
      attemptRecord.recoveryError = String(recoveryError);
      attemptRecord.recoveryErrorName = recoveryError?.name ?? 'Error';
      attemptRecord.recoveryLoginAttempt = recoveryError?.qaLoginAttempt ?? null;
      error.qaSwitchEvidence = attemptRecord;
      throw recoveryError;
    }
    if (trace) trace.loginAttempts.at(-1).recovery = attemptRecord.recovery;
    if (!targetIdentity) {
      error.qaSwitchEvidence = attemptRecord;
      throw error;
    }
    if (initialLoginError && trace) trace.steps?.push(`Peer login page recovery: ${attemptRecord.recovery}`);
  }
  assert.equal(targetIdentity.userId, targetUserId);
  transition ??= await transitionPromise;
  const transitionRecord = {
    at: new Date().toISOString(),
    targetUserId,
    outcome: transition.kind,
    unauthorizedMeCount: transition.unauthorizedMeCount,
    invalidations: transition.invalidations ?? null,
    serverIdentityStatus: null,
    serverIdentityUserId: null,
    explicitProtectedNavigation: false,
    explicitTabALogin: false,
    forcedAnonymousBoundary: requireAnonymousBeforePeerLogin,
  };
  if (trace) {
    trace.transitions ??= [];
    trace.transitions.push(transitionRecord);
  }
  try {
    if (transition.kind === 'anonymous') {
      assert.equal(new URL(pageA.url()).pathname, '/user/login', 'Only a verified post-logout 401 boundary may classify tab A as anonymous');
      const serverIdentityResponse = await context.request.get('/api/auth/me');
      transitionRecord.serverIdentityStatus = serverIdentityResponse.status();
      if (serverIdentityResponse.status() === 200) {
        const serverIdentity = await serverIdentityResponse.json();
        transitionRecord.serverIdentityUserId = serverIdentity.userId ?? null;
        assert.equal(serverIdentity.userId, targetUserId, 'The peer tab must have established the expected server identity');
        const expectedIdentity = waitForIdentity(pageA, targetUserId).then(identity => ({ identity }), error => ({ error }));
        await pageA.goto(protectedPath);
        const identityResult = await expectedIdentity;
        if (identityResult.error) throw identityResult.error;
        assert.equal(identityResult.identity.userId, targetUserId, 'A fresh protected-route navigation must read the shared target identity');
        await pageA.locator('.pro-account-trigger').filter({ hasText: targetDisplayName }).waitFor({ state: 'visible', timeout: 15_000 });
        transition.kind = 'anonymous-navigation';
        transition.serverIdentityAfterPeerLogin = serverIdentity.userId;
        transition.tabAIdentityAfterNavigation = identityResult.identity.userId;
        transitionRecord.explicitProtectedNavigation = true;
      } else if (serverIdentityResponse.status() === 401) {
        const identitySeenByA = await submitFreshLoginOnCurrentPage(pageA, targetUsername);
        assert.equal(identitySeenByA.userId, targetUserId, 'A new explicit login gesture may recover only after the server confirms the shared cookie is anonymous');
        transition.kind = 'anonymous-explicit-login';
        transition.tabAIdentityAfterExplicitLogin = identitySeenByA.userId;
        transitionRecord.explicitTabALogin = true;
      } else {
        assert.fail(`The shared server identity check returned unexpected status ${serverIdentityResponse.status()}`);
      }
      transitionRecord.outcome = transition.kind;
    } else {
      assert.equal(transition.identity.userId, targetUserId, 'The first tab must accept the target identity without leaving the protected page');
    }
    if (transition.kind === 'direct') {
      await pageA.waitForFunction(previous => window.__fileIdentitySmoke?.invalidations > previous, previousInvalidations, { timeout: 15_000 });
    } else {
      assert.ok(transition.invalidations > previousInvalidations, 'The anonymous classification must retain the invalidation observed before the fresh navigation');
    }
  } catch (error) {
    transitionRecord.error = String(error);
    transitionRecord.pagePath = await pageA.evaluate(() => `${location.pathname}${location.search}`).catch(() => null);
    throw error;
  }
  targetIdentity.qaTabAOutcome = transition.kind;
  targetIdentity.qaTabAUnauthorizedMeCount = transition.unauthorizedMeCount;
  return targetIdentity;
}

async function switchSharedCookieToB(context, pageA, pageB, trace = null) {
  return switchSharedCookieTo(context, pageA, pageB, userB, userBId, 'File identity QA B', trace);
}

async function uploadFixture(page, file) {
  const uploaded = page.waitForResponse(response => new URL(response.url()).pathname === '/api/account/files' && response.request().method() === 'POST');
  await page.locator('input[type="file"]').last().setInputFiles({ name: file.name, mimeType: 'text/plain', buffer: Buffer.from(file.content) });
  const response = await uploaded;
  assert.equal(response.status(), 201, `Synthetic upload ${file.name} must succeed`);
  const value = await response.json();
  fileIds.add(value.id);
  return value;
}

async function chooseFiles(page, names) {
  for (const name of names) {
    await page.getByRole('row').filter({ hasText: name }).getByRole('checkbox').check();
  }
  await page.getByRole('button', { name: t.bulkTrashFiles, exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: t.bulkTrashFiles, exact: true }).waitFor();
  return dialog;
}

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});
const pageErrors = [];
const contexts = [];
let routeMode = 'control';
let routeRequests = [];
let resolveGate;
let resolveFirstDelete;
let firstDelete;
let routeSettled;
let c2BUsabilityFailure;
let c3IdentityFailure;
let releaseStaleA401Gate;

try {
  createUsers();

  if (c3Only) console.log('QA mode: C3-only diagnostics; C2/C1 scenarios remain enabled in the default run.');

  if (!c3Only) {
  // FS01-C2: hold native File.text(), then replace the authenticated identity
  // through another tab sharing the same cookie jar before releasing the bytes.
  const dictionaryContext = await browser.newContext({ baseURL: parsedBase.origin, viewport: { width: 1240, height: 900 } });
  contexts.push(dictionaryContext);
  const delayedName = `qa-held-dictionary-${suffix}.json`;
  await dictionaryContext.addInitScript(filename => {
    const originalText = Blob.prototype.text;
    const originalAny = AbortSignal.any;
    const state = { calls: 0, released: false, release: null, lastInputs: null, lastCombined: null, fileInputs: null, fileSignal: null };
    Object.defineProperties(state, {
      sessionSignalAborted: { get() { return state.fileInputs?.[0]?.aborted === true; } },
      scopedSignalAborted: { get() { return state.fileSignal?.aborted === true; } },
    });
    Object.defineProperty(window, '__qaFileTextGate', { value: state });
    AbortSignal.any = function (signals) {
      const combined = originalAny.call(this, signals);
      state.lastInputs = Array.from(signals);
      state.lastCombined = combined;
      return combined;
    };
    Blob.prototype.text = function () {
      if (!(this instanceof File) || this.name !== filename) return originalText.call(this);
      state.calls += 1;
      state.fileInputs = state.lastInputs;
      state.fileSignal = state.lastCombined;
      window.dispatchEvent(new Event('qa:file-text-held'));
      return new Promise((resolve, reject) => {
        state.release = () => {
          if (state.released) return false;
          state.released = true;
          originalText.call(this).then(resolve, reject);
          return true;
        };
      });
    };
    const boundary = { invalidations: 0 };
    Object.defineProperty(window, '__fileIdentitySmoke', { value: boundary });
    const sessionApiTrace = [];
    Object.defineProperty(window, '__qaSessionApiTrace', { value: sessionApiTrace });
    Object.defineProperty(window, '__lumiraSessionTrace', { value: entry => sessionApiTrace.push(entry) });
    window.addEventListener('lumira:session-invalidated', () => { boundary.invalidations += 1; });
  }, delayedName);
  const dictionaryPageA = await dictionaryContext.newPage();
  const dictionaryPageB = await dictionaryContext.newPage();
  dictionaryPageA.on('pageerror', error => pageErrors.push(error.message));
  dictionaryPageB.on('pageerror', error => pageErrors.push(error.message));
  const identitySwitchTrace = { steps: [], responses: [], consoleErrors: [], pageErrors: [], pendingResponses: [] };
  dictionaryPageA.on('response', response => {
    const url = new URL(response.url());
    if (!url.pathname.startsWith('/api/')) return;
    const entry = {
      at: new Date().toISOString(),
      method: response.request().method(),
      path: url.pathname,
      status: response.status(),
    };
    identitySwitchTrace.responses.push(entry);
    if (url.pathname === '/api/auth/me' && response.status() === 200) {
      const pending = response.json().then(value => { entry.userId = value.userId; }).catch(() => {});
      identitySwitchTrace.pendingResponses.push(pending);
    }
  });
  dictionaryPageA.on('console', message => {
    if (message.type() === 'error') identitySwitchTrace.consoleErrors.push(message.text());
  });
  dictionaryPageA.on('pageerror', error => identitySwitchTrace.pageErrors.push(error.message));
  const identityA = await loginThroughUi(dictionaryPageA, userA);
  assert.equal(identityA.userId, userAId);
  const previewRequests = [];
  await dictionaryContext.route('**/api/platform/dictionaries/preview', async route => {
    previewRequests.push(route.request().postDataJSON());
    await route.fulfill({ status: 200, json: { code: 'qa_control', name: 'QA control', structure: 'FLAT', itemCount: 1 } });
  });
  await dictionaryPageA.goto('/dashboard/dictionaries');
  await dictionaryPageA.getByRole('button', { name: t.importDictionary, exact: true }).waitFor();
  await dictionaryPageB.goto('/dashboard/home');
  const importDialog = dictionaryPageA.getByRole('dialog');
  await dictionaryPageA.getByRole('button', { name: t.importDictionary, exact: true }).click();
  await importDialog.waitFor();
  await importDialog.locator('input[type="file"]').setInputFiles({
    name: delayedName,
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify({ code: 'qa_held', name: 'QA held', structure: 'FLAT', items: [{ value: 'x', label: 'X' }] })),
  });
  await dictionaryPageA.waitForFunction(() => window.__qaFileTextGate?.calls === 1, undefined, { timeout: 15_000 });
  assert.equal(previewRequests.length, 0, 'The held File.text() call must not preview before the identity switch');
  identitySwitchTrace.steps.push('A authenticated on /dashboard/dictionaries; import dialog opened; named File.text() is held');
  identitySwitchTrace.steps.push('Shared cookie cleared through /api/auth/logout; tab B logged in as synthetic QA B');
  const initialTabASwitch = await switchSharedCookieToB(dictionaryContext, dictionaryPageA, dictionaryPageB, identitySwitchTrace);
  assert.equal(initialTabASwitch.qaTabAOutcome, 'direct', 'The positive FS01-C2 control must accept B in place, without a login-page boundary or navigation');
  identitySwitchTrace.steps.push('Tab A received /api/auth/me for synthetic QA B and lumira:session-invalidated');
  assert.equal(await dictionaryPageA.evaluate(() => window.__qaFileTextGate.sessionSignalAborted), true, 'The original A session signal must retire even if the feature page is unmounted');
  assert.equal(await dictionaryPageA.evaluate(() => window.__qaFileTextGate.scopedSignalAborted), true, 'The File.text operation scope must be aborted by the identity boundary');
  identitySwitchTrace.steps.push('Original A session signal and File.text operation signal observed aborted');
  const released = await dictionaryPageA.evaluate(() => window.__qaFileTextGate.release());
  assert.equal(released, true, 'The delayed browser file read must be released after the identity boundary');
  await dictionaryPageA.waitForTimeout(750);
  assert.equal(previewRequests.length, 0, 'A delayed A file must not send a preview request after B becomes the server-confirmed identity');
  console.log('PASS FS01-C2: File.text() held for A, same-cookie tab switches to B, released content sends no dictionary preview');

  // This is independent of rejecting the stale file read: the already-open tab
  // must also finish authenticating and render B's account and normal controls.
  identitySwitchTrace.steps.push('Released stale File.text(); preview request count remains zero');
  try {
    await dictionaryPageA.locator('.pro-account-trigger').filter({ hasText: 'File identity QA B' }).waitFor({ state: 'visible', timeout: 15_000 });
    await dictionaryPageA.getByRole('button', { name: t.importDictionary, exact: true }).waitFor({ state: 'visible', timeout: 15_000 });
    identitySwitchTrace.steps.push('Same tab visibly rendered B account and dictionary import control without reload');
    console.log('PASS FS01-C2 account control: tab A rendered B account and dictionary controls without reload');
  } catch (error) {
    const artifacts = await saveIdentitySwitchFailure(dictionaryPageA, identitySwitchTrace, error);
    c2BUsabilityFailure = new Error(`FS01-C2 B-session usability failed independently of stale-file rejection. Screenshot: ${artifacts.screenshotPath}; trace: ${artifacts.reportPath}; page: ${JSON.stringify(artifacts.pageState)}`);
    console.error(`FAIL FS01-C2 account control: ${c2BUsabilityFailure.message}`);
  }

  // Exercise repeated shared-cookie transitions. Each transition must either
  // accept the target identity in place or explicitly classify the anonymous
  // interval and validate a fresh protected-route request (or explicit login
  // only when the server still reports the shared cookie as anonymous).
  const repeatedSwitchFailures = [];
  for (let round = 1; round <= 3; round += 1) {
    for (const target of [
      { label: 'A', username: userA, userId: userAId, displayName: 'File identity QA A' },
      { label: 'B', username: userB, userId: userBId, displayName: 'File identity QA B' },
    ]) {
      const artifactName = `round-${round}-${target.label.toLowerCase()}`;
      try {
        const identity = await switchSharedCookieTo(
          dictionaryContext,
          dictionaryPageA,
          dictionaryPageB,
          target.username,
          target.userId,
          target.displayName,
          identitySwitchTrace,
          round === 1 && target.label === 'A',
        );
        assert.equal(identity.userId, target.userId);
        await dictionaryPageA.locator('.pro-account-trigger').filter({ hasText: target.displayName }).waitFor({ state: 'visible', timeout: 15_000 });
        await dictionaryPageA.getByRole('button', { name: t.importDictionary, exact: true }).waitFor({ state: 'visible', timeout: 15_000 });
        identitySwitchTrace.steps.push(`Repeated switch ${round} to ${target.label}: ${identity.qaTabAOutcome}; target account and dictionary controls are usable`);
        console.log(`PASS FS01-C2 repeated switch ${round} to ${target.label}: ${identity.qaTabAOutcome}; target controls usable`);
      } catch (error) {
        const artifacts = await saveIdentitySwitchFailure(
          dictionaryPageA,
          identitySwitchTrace,
          error,
          artifactName,
        );
        repeatedSwitchFailures.push({ round, target: target.label, error: String(error), ...artifacts });
        identitySwitchTrace.steps.push(`Repeated same-tab switch ${round} to ${target.label} failed: ${String(error)}`);
        console.error(`FAIL FS01-C2 repeated switch ${round} to ${target.label}: ${error}`);
      }
    }
  }
  if (repeatedSwitchFailures.length > 0) {
    const repeatedFailure = new Error(`FS01-C2 repeated A↔B identity switches failed: ${JSON.stringify(repeatedSwitchFailures)}`);
    c2BUsabilityFailure ??= repeatedFailure;
  }
  const sessionApiTrace = await dictionaryPageA.evaluate(() => window.__qaSessionApiTrace ?? []);
  const sessionTraceDir = path.join(projectRoot, '.runtime', 'security-integration-20261009', `files-identity-boundary-${suffix}`);
  mkdirSync(sessionTraceDir, { recursive: true });
  const sessionTracePath = path.join(sessionTraceDir, 'fs01-c2-session-api-trace.json');
  writeFileSync(sessionTracePath, `${JSON.stringify({ scenario: 'FS01-C2 repeated A-to-B-to-A same-tab identity changes', entries: sessionApiTrace }, null, 2)}\n`);
  console.log(`FS01-C2 session API source trace: ${sessionTracePath}`);

  // A freshly loaded authenticated page is the positive control for normal
  // imports; the preview endpoint is intercepted, so no dictionary is written.
  await dictionaryPageA.reload();
  await dictionaryPageA.getByRole('button', { name: t.importDictionary, exact: true }).waitFor();
  await dictionaryPageA.getByRole('button', { name: t.importDictionary, exact: true }).click();
  const controlDialog = dictionaryPageA.getByRole('dialog');
  await controlDialog.waitFor();
  const controlPreview = dictionaryPageA.waitForResponse(response => new URL(response.url()).pathname === '/api/platform/dictionaries/preview' && response.request().method() === 'POST');
  await controlDialog.locator('input[type="file"]').setInputFiles({
    name: `qa-control-dictionary-${suffix}.json`,
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify({ code: 'qa_control', name: 'QA Control', structure: 'FLAT', items: [{ value: 'yes', label: 'Yes' }] })),
  });
  assert.equal((await controlPreview).status(), 200);
  assert.equal(previewRequests.length, 1);
  assert.equal(previewRequests[0].code, 'qa_control');
  await controlDialog.getByText('QA control', { exact: true }).waitFor();
  console.log('PASS FS01-C2 control: normal file read previews once; preview endpoint is mocked and no import is committed');

  // FS01-C1: leave the static policy confirmation open across the same
  // cross-tab identity transition. If it remains visible, confirming it must
  // not issue the settings write captured by this route.
  const settingsContext = await browser.newContext({ baseURL: parsedBase.origin, viewport: { width: 1240, height: 900 } });
  contexts.push(settingsContext);
  await settingsContext.addInitScript(() => {
    const boundary = { invalidations: 0 };
    Object.defineProperty(window, '__fileIdentitySmoke', { value: boundary });
    window.addEventListener('lumira:session-invalidated', () => { boundary.invalidations += 1; });
  });
  const settingsPageA = await settingsContext.newPage();
  const settingsPageB = await settingsContext.newPage();
  settingsPageA.on('pageerror', error => pageErrors.push(error.message));
  settingsPageB.on('pageerror', error => pageErrors.push(error.message));
  const settingsIdentityA = await loginThroughUi(settingsPageA, userA);
  assert.equal(settingsIdentityA.userId, userAId);
  const retentionWrites = [];
  await settingsContext.route('**/api/platform/settings/file-retention', async route => {
    if (route.request().method() === 'PUT') {
      retentionWrites.push(route.request().postDataJSON());
      await route.fulfill({ status: 200, json: { data: { mode: 'AUTOMATIC', retentionDays: 30 }, version: 999 } });
      return;
    }
    await route.continue();
  });
  const initialRetentionResponse = settingsPageA.waitForResponse(response => new URL(response.url()).pathname === '/api/platform/settings/file-retention' && response.request().method() === 'GET' && response.status() === 200);
  await settingsPageA.goto('/dashboard/files-all#tab=file-retention');
  const originalRetention = await (await initialRetentionResponse).json();
  await settingsPageA.getByRole('tab', { name: t.fileRetention, exact: true }).click();
  const retentionCard = settingsPageA.locator('.platform-settings-card');
  await retentionCard.getByText(t.fileRetentionMode, { exact: true }).waitFor({ state: 'visible' });
  const retentionMode = retentionCard.getByRole('combobox');
  await retentionMode.click();
  const manualOption = settingsPageA.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: t.fileRetentionManual }).last();
  await manualOption.click();
  await retentionMode.click();
  const automaticOption = settingsPageA.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: t.fileRetentionAutomatic }).last();
  await automaticOption.click();
  await settingsPageB.goto('/dashboard/home');
  await settingsPageA.getByRole('button', { name: t.save, exact: true }).click();
  const retentionConfirm = settingsPageA.getByRole('dialog').filter({ hasText: t.fileRetentionConfirmTitle });
  await retentionConfirm.waitFor();
  const settingsSwitchTrace = { steps: [], transitions: [], loginAttempts: [] };
  const detachSettingsTrace = [
    trackIdentityApi(settingsPageA, settingsSwitchTrace, 'A'),
    trackIdentityApi(settingsPageB, settingsSwitchTrace, 'B'),
  ];
  let identityB;
  try {
    identityB = await switchSharedCookieToB(settingsContext, settingsPageA, settingsPageB, settingsSwitchTrace);
  } catch (error) {
    let evidencePath = null;
    try {
      evidencePath = await saveC1IdentitySwitchEvidence(settingsContext, settingsPageA, settingsPageB, settingsSwitchTrace, error);
    } catch (evidenceError) {
      console.error(`C1 diagnostic capture failed without replacing the primary error: ${String(evidenceError)}`);
    }
    if (evidencePath) error.qaEvidencePath = evidencePath;
    console.error(`FAIL FS01-C1 identity transition: ${String(error)}${evidencePath ? `; evidence: ${evidencePath}` : ''}`);
    throw error;
  } finally {
    detachSettingsTrace.forEach(detach => detach());
  }
  settingsSwitchTrace.responseBodiesSettled = await settleTraceResponses(settingsSwitchTrace);
  const oldConfirmationVisible = await retentionConfirm.isVisible();
  if (oldConfirmationVisible) await retentionConfirm.locator('.ant-modal-confirm-btns .ant-btn-primary').click();
  const retentionRevision = identityB.authorizationContext?.rolePreviewSupported === true ? identityB.authorizationContext.revision : null;
  const retentionRead = await settingsContext.request.get('/api/platform/settings/file-retention', {
    headers: typeof retentionRevision === 'string' ? { 'X-Lumira-Authorization-Context-Revision': retentionRevision } : {},
  });
  assert.equal(retentionRead.status(), 200);
  const refreshedRetention = await retentionRead.json();
  assert.equal(retentionWrites.length, 0, 'An old confirmation may not write policy settings after the account changes');
  assert.equal(refreshedRetention.version, originalRetention.version, 'The verified policy version must remain unchanged across the identity switch');
  assert.deepEqual(refreshedRetention.data, originalRetention.data, 'The verified policy data must remain unchanged across the identity switch');
  const c1ArtifactDir = path.join(projectRoot, '.runtime', 'security-integration-20261009', `files-identity-boundary-${suffix}`);
  mkdirSync(c1ArtifactDir, { recursive: true });
  writeFileSync(path.join(c1ArtifactDir, 'fs01-c1-identity-switch-result.json'), `${JSON.stringify({
    scenario: 'FS01-C1 settings confirmation across a shared-cookie account switch',
    result: 'passed',
    targetUserId: userBId,
    tabAOutcome: identityB.qaTabAOutcome ?? null,
    apiEvents: settingsSwitchTrace.apiEvents,
    transitions: settingsSwitchTrace.transitions,
    loginAttempts: settingsSwitchTrace.loginAttempts,
    oldConfirmationVisible,
    retentionPutCount: retentionWrites.length,
    originalPolicyVersion: originalRetention.version,
    verifiedPolicyVersion: refreshedRetention.version,
    createdAt: new Date().toISOString(),
  }, null, 2)}\n`);
  console.log(`PASS FS01-C1: old file-retention confirmation ${oldConfirmationVisible ? 'was clicked' : 'was hidden or retired'} after A-to-B identity change; no PUT and policy snapshot unchanged`);
  }

  // FS01-C3: two real QA files exercise the actual file table and batch dialog.
  // The normal control intercepts both writes; the identity-change case holds
  // the first response and verifies that the second request never starts.
  const fileContext = await browser.newContext({ baseURL: parsedBase.origin, viewport: { width: 1240, height: 900 } });
  contexts.push(fileContext);
  await fileContext.addInitScript(() => {
    const boundary = { invalidations: 0 };
    Object.defineProperty(window, '__fileIdentitySmoke', { value: boundary });
    const trace = [];
    Object.defineProperty(window, '__qaSessionApiTrace', { value: trace });
    Object.defineProperty(window, '__lumiraSessionTrace', { value: entry => {
      trace.push(entry);
      console.debug(`QA_SESSION_API_TRACE ${JSON.stringify(entry)}`);
    } });
    window.addEventListener('lumira:session-invalidated', () => {
      boundary.invalidations += 1;
      console.debug(`QA_SESSION_INVALIDATED ${boundary.invalidations}`);
    });
    window.addEventListener('lumira:session-expired', () => console.debug('QA_SESSION_EXPIRED'));
  });
  const filePageA = await fileContext.newPage();
  const filePageB = await fileContext.newPage();
  const c3IdentityTrace = { requests: [], responses: [], requestFailures: [], held401s: [], sessionApiTrace: [], consoleErrors: [], pageErrors: [], pendingResponses: [] };
  filePageA.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) {
      c3IdentityTrace.requests.push({ at: new Date().toISOString(), method: request.method(), path: url.pathname });
    }
  });
  filePageA.on('requestfailed', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) {
      c3IdentityTrace.requestFailures.push({ at: new Date().toISOString(), method: request.method(), path: url.pathname, failure: request.failure()?.errorText ?? null });
    }
  });
  filePageA.on('response', response => {
    const url = new URL(response.url());
    if (!url.pathname.startsWith('/api/')) return;
    const entry = { at: new Date().toISOString(), method: response.request().method(), path: url.pathname, status: response.status() };
    c3IdentityTrace.responses.push(entry);
    if (url.pathname === '/api/auth/me' && response.status() === 200) {
      c3IdentityTrace.pendingResponses.push(response.json().then(value => { entry.userId = value.userId; }).catch(() => {}));
    } else if (url.pathname === '/api/auth/login') {
      c3IdentityTrace.pendingResponses.push(captureLoginOutcome(response, entry));
    }
  });
  filePageA.on('console', message => {
    if (message.text().startsWith('QA_SESSION_API_TRACE ')) {
      try { c3IdentityTrace.sessionApiTrace.push(JSON.parse(message.text().slice('QA_SESSION_API_TRACE '.length))); } catch {}
    } else if (message.text().startsWith('QA_SESSION_INVALIDATED ')) {
      c3IdentityTrace.sessionApiTrace.push({ event: 'invalidated', count: Number(message.text().slice('QA_SESSION_INVALIDATED '.length)), at: new Date().toISOString() });
    } else if (message.text() === 'QA_SESSION_EXPIRED') {
      c3IdentityTrace.sessionApiTrace.push({ event: 'session-expired', at: new Date().toISOString() });
    } else if (message.type() === 'error') {
      c3IdentityTrace.consoleErrors.push(message.text());
    }
  });
  filePageA.on('pageerror', error => pageErrors.push(error.message));
  filePageB.on('pageerror', error => pageErrors.push(error.message));
  filePageA.on('pageerror', error => c3IdentityTrace.pageErrors.push(error.message));
  const c3IdentityTraceB = { requests: [], responses: [], requestFailures: [], held401s: [], sessionApiTrace: [], consoleErrors: [], pageErrors: [], pendingResponses: [] };
  filePageB.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) {
      c3IdentityTraceB.requests.push({ at: new Date().toISOString(), method: request.method(), path: url.pathname });
    }
  });
  filePageB.on('requestfailed', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) {
      c3IdentityTraceB.requestFailures.push({ at: new Date().toISOString(), method: request.method(), path: url.pathname, failure: request.failure()?.errorText ?? null });
    }
  });
  filePageB.on('response', response => {
    const url = new URL(response.url());
    if (!url.pathname.startsWith('/api/')) return;
    const entry = { at: new Date().toISOString(), method: response.request().method(), path: url.pathname, status: response.status() };
    c3IdentityTraceB.responses.push(entry);
    if (url.pathname === '/api/auth/me' && response.status() === 200) {
      c3IdentityTraceB.pendingResponses.push(response.json().then(value => { entry.userId = value.userId; }).catch(() => {}));
    } else if (url.pathname === '/api/auth/login') {
      c3IdentityTraceB.pendingResponses.push(captureLoginOutcome(response, entry));
    }
  });
  filePageB.on('console', message => {
    if (message.text().startsWith('QA_SESSION_API_TRACE ')) {
      try { c3IdentityTraceB.sessionApiTrace.push(JSON.parse(message.text().slice('QA_SESSION_API_TRACE '.length))); } catch {}
    } else if (message.text().startsWith('QA_SESSION_INVALIDATED ')) {
      c3IdentityTraceB.sessionApiTrace.push({ event: 'invalidated', count: Number(message.text().slice('QA_SESSION_INVALIDATED '.length)), at: new Date().toISOString() });
    } else if (message.text() === 'QA_SESSION_EXPIRED') {
      c3IdentityTraceB.sessionApiTrace.push({ event: 'session-expired', at: new Date().toISOString() });
    } else if (message.type() === 'error') {
      c3IdentityTraceB.consoleErrors.push(message.text());
    }
  });
  filePageB.on('pageerror', error => c3IdentityTraceB.pageErrors.push(error.message));
  let allowStaleA401Hold = false;
  let staleA401Held = false;
  let resolveStaleA401Observed;
  const staleA401Observed = new Promise(resolve => { resolveStaleA401Observed = resolve; });
  const staleA401Gate = new Promise(resolve => { releaseStaleA401Gate = resolve; });
  if (holdStaleA401) {
    await filePageA.route('**/api/**', async route => {
      const request = route.request();
      if (!allowStaleA401Hold || request.method() !== 'GET') return route.fallback();
      const response = await route.fetch();
      if (response.status() === 401 && !staleA401Held) {
        staleA401Held = true;
        c3IdentityTrace.held401s.push({ at: new Date().toISOString(), method: request.method(), path: new URL(request.url()).pathname, status: 401 });
        resolveStaleA401Observed(true);
        await staleA401Gate;
      }
      try { await route.fulfill({ response }); } catch { /* The tab may retire the request while the 401 is held. */ }
    });
  }
  const fileIdentityA = await loginThroughUi(filePageA, userA);
  assert.equal(fileIdentityA.userId, userAId);
  await filePageA.goto('/dashboard/files');
  await filePageA.getByRole('heading', { name: t.myFiles, exact: true }).waitFor();
  for (const file of files) {
    const created = await uploadFixture(filePageA, file);
    assert.equal(created.originalName, file.name);
    await filePageA.getByRole('row').filter({ hasText: file.name }).waitFor();
  }
  await filePageB.goto('/dashboard/home');

  let gate = new Promise(resolve => { resolveGate = resolve; });
  let resolveSettled;
  routeSettled = new Promise(resolve => { resolveSettled = resolve; });
  firstDelete = new Promise(resolve => { resolveFirstDelete = resolve; });
  await fileContext.route('**/api/account/files/**', async route => {
    const request = route.request();
    if (request.method() !== 'DELETE') return route.continue();
    const requestUrl = new URL(request.url());
    const id = requestUrl.pathname.split('/').at(-1);
    if (!fileIds.has(id)) return route.continue();
    routeRequests.push(id);
    if (routeMode === 'control' || routeRequests.length === 1) {
      if (routeMode === 'abort' && routeRequests.length === 1) resolveFirstDelete(id);
      if (routeMode === 'abort' && routeRequests.length === 1) await gate;
      try { await route.fulfill({ status: 204, body: '' }); } catch { /* The app may abort the held fetch at the identity boundary. */ }
      if (routeMode === 'abort' && routeRequests.length === 1) resolveSettled();
      return;
    }
    try { await route.fulfill({ status: 204, body: '' }); } catch { /* The assertion below records any follow-up request. */ }
  });

  const runConfirmedBatch = async () => {
    const dialog = await chooseFiles(filePageA, files.map(file => file.name));
    await dialog.getByRole('button', { name: t.bulkTrashFiles, exact: true }).click();
  };
  await runConfirmedBatch();
  await expect.poll(() => routeRequests.length, { timeout: 15_000 }).toBe(2);
  await expect(filePageA.getByRole('button', { name: t.bulkTrashFiles, exact: true })).toHaveCount(0);
  await expect(filePageA.getByRole('button', { name: t.uploadFile, exact: true })).toBeEnabled();
  assert.deepEqual(new Set(routeRequests), fileIds, 'Without a session boundary a normal batch must issue one request for each selection');
  console.log('PASS FS01-C3 control: both selected synthetic files issue a bounded sequential batch request');

  routeMode = 'abort';
  routeRequests = [];
  gate = new Promise(resolve => { resolveGate = resolve; });
  firstDelete = new Promise(resolve => { resolveFirstDelete = resolve; });
  routeSettled = new Promise(resolve => { resolveSettled = resolve; });
  await runConfirmedBatch();
  const firstId = await Promise.race([
    firstDelete,
    new Promise((_, reject) => setTimeout(() => reject(new Error('No first batch request reached the held QA route')), 30_000)),
  ]);
  assert.ok(fileIds.has(firstId), 'The first held batch request must target an owned synthetic fixture');
  const c3ProtectedPath = await filePageA.evaluate(() => `${location.pathname}${location.search}${location.hash}`);
  const c3InvalidationsBeforeLogout = await filePageA.evaluate(() => window.__fileIdentitySmoke?.invalidations ?? 0);
  let expectedIdentityOnA = waitForIdentity(filePageA, userBId, 10_000)
    .then(identity => ({ identity }), error => ({ error }));
  const csrf = await (await fileContext.request.get('/api/auth/csrf')).json();
  const logout = await fileContext.request.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } });
  assert.equal(logout.status(), 204, 'C3 QA API logout must clear the shared browser-context cookie');
  if (holdStaleA401) {
    allowStaleA401Hold = true;
    await filePageA.bringToFront();
    await filePageA.evaluate(() => {
      window.dispatchEvent(new Event('focus'));
      document.dispatchEvent(new Event('visibilitychange'));
    });
    const held = await Promise.race([
      staleA401Observed,
      new Promise(resolve => setTimeout(() => resolve(false), 3_000)),
    ]);
    console.log(held
      ? 'C3 diagnostic: held tab A protected-GET 401 until B login completes'
      : 'C3 diagnostic: no tab A protected-GET 401 was triggered during the hold window');
  }
  await filePageB.goto('/user/login');
  let c3IdentityB = null;
  let c3LoginError = null;
  const c3LoginAttempts = [];
  const countLoginPosts = () => c3IdentityTraceB.requests.filter(entry => entry.method === 'POST' && entry.path === '/api/auth/login').length;
  const countCsrfGets = () => c3IdentityTraceB.requests.filter(entry => entry.method === 'GET' && entry.path === '/api/auth/csrf').length;
  const readServerIdentity = async () => {
    const response = await fileContext.request.get('/api/auth/me');
    const identity = response.status() === 200 ? await response.json() : null;
    return { status: response.status(), identity };
  };
  try {
    c3IdentityB = await loginThroughUi(filePageB, userB, c3Only ? 12_000 : 30_000);
    assert.equal(c3IdentityB.userId, userBId);
    c3LoginAttempts.push({ phase: 'initial-user-action', result: 'authenticated', loginPostCount: countLoginPosts(), csrfGetCount: countCsrfGets() });
  } catch (error) {
    c3LoginError = error;
    c3LoginAttempts.push({ phase: 'initial-user-action', result: 'cancelled-or-incomplete', errorName: error?.name ?? 'Error', loginPostCount: countLoginPosts(), csrfGetCount: countCsrfGets() });
  }
  if (staleA401Held) {
    releaseStaleA401Gate?.();
    await filePageA.waitForTimeout(250);
  }
  let preRetryServerIdentity = null;
  if (c3LoginError) {
    preRetryServerIdentity = await readServerIdentity();
    c3IdentityTraceB.serverIdentityBeforeRetry = {
      status: preRetryServerIdentity.status,
      userId: preRetryServerIdentity.identity?.userId ?? null,
    };
    c3LoginAttempts[0].serverIdentityBeforeRetry = c3IdentityTraceB.serverIdentityBeforeRetry;
  }
  if (c3LoginError && preRetryServerIdentity?.status === 200 && preRetryServerIdentity.identity?.userId === userBId) {
    // The server completed the first login even if the page transition was
    // canceled. Re-enter the protected route to establish UI state, without
    // submitting credentials a second time.
    try {
      const expectedPeerIdentity = waitForIdentity(filePageB, userBId, 12_000)
        .then(identity => ({ identity }), error => ({ error }));
      await filePageB.goto('/dashboard/home');
      const peerIdentityResult = await expectedPeerIdentity;
      if (peerIdentityResult.error) throw peerIdentityResult.error;
      assert.equal(peerIdentityResult.identity.userId, userBId);
      await filePageB.locator('.pro-account-trigger').filter({ hasText: 'File identity QA B' }).waitFor({ state: 'visible', timeout: 10_000 });
      c3IdentityB = preRetryServerIdentity.identity;
      c3LoginError = null;
      c3LoginAttempts.push({ phase: 'server-cookie-already-authenticated', result: 'protected-route-restored-without-repost', loginPostCount: countLoginPosts() });
    } catch (error) {
      c3LoginError = error;
      c3LoginAttempts.push({ phase: 'server-cookie-already-authenticated', result: 'protected-route-restore-failed', errorName: error?.name ?? 'Error', loginPostCount: countLoginPosts() });
    }
  }
  const loginPostRequestsBeforeRecovery = c3IdentityTraceB.requests.filter(entry => entry.method === 'POST' && entry.path === '/api/auth/login');
  const loginPostResponsesBeforeRecovery = c3IdentityTraceB.responses.filter(entry => entry.method === 'POST' && entry.path === '/api/auth/login');
  const loginPostFailuresBeforeRecovery = c3IdentityTraceB.requestFailures.filter(entry => entry.method === 'POST' && entry.path === '/api/auth/login');
  const initialActionHadNoLoginPost = loginPostRequestsBeforeRecovery.length === 0;
  const initialLoginPostWasOnlyAborted = loginPostRequestsBeforeRecovery.length === 1
    && loginPostResponsesBeforeRecovery.length === 0
    && loginPostFailuresBeforeRecovery.length === 1
    && /ERR_ABORTED/i.test(loginPostFailuresBeforeRecovery[0].failure ?? '');
  const sessionBoundaryWasInvalidated = [...c3IdentityTrace.sessionApiTrace, ...c3IdentityTraceB.sessionApiTrace]
    .some(entry => entry.event === 'invalidated');
  if (c3LoginError && preRetryServerIdentity?.status === 401
    && (initialActionHadNoLoginPost || initialLoginPostWasOnlyAborted)
    && sessionBoundaryWasInvalidated) {
    const stableBoundary = await waitForStableLoginBoundary(
      [filePageA, filePageB],
      [c3IdentityTrace, c3IdentityTraceB],
    );
    c3LoginAttempts[0].boundarySettled = stableBoundary;
    c3IdentityTraceB.loginAttempts = c3LoginAttempts;
    const serverAfterStableBoundary = await readServerIdentity();
    c3LoginAttempts[0].serverIdentityAfterStableBoundary = {
      status: serverAfterStableBoundary.status,
      userId: serverAfterStableBoundary.identity?.userId ?? null,
    };
    if (serverAfterStableBoundary.status === 200 && serverAfterStableBoundary.identity?.userId === userBId) {
      const expectedPeerIdentity = waitForIdentity(filePageB, userBId, 12_000)
        .then(identity => ({ identity }), error => ({ error }));
      await filePageB.goto('/dashboard/home');
      const peerIdentityResult = await expectedPeerIdentity;
      if (peerIdentityResult.error) throw peerIdentityResult.error;
      await filePageB.locator('.pro-account-trigger').filter({ hasText: 'File identity QA B' }).waitFor({ state: 'visible', timeout: 10_000 });
      c3IdentityB = peerIdentityResult.identity;
      c3LoginError = null;
      c3LoginAttempts.push({ phase: 'server-authenticated-after-stable-boundary', result: 'protected-route-navigation-without-repost', loginPostCount: countLoginPosts(), csrfGetCount: countCsrfGets() });
    } else if (serverAfterStableBoundary.status === 401) {
      // This is one new explicit UI gesture after the anonymous boundary settled
      // and the server re-confirmed 401; it never replays the canceled request.
      expectedIdentityOnA = waitForIdentity(filePageA, userBId, 10_000)
        .then(identity => ({ identity }), error => ({ error }));
      try {
        c3IdentityB = await submitFreshLoginOnCurrentPage(filePageB, userB);
        assert.equal(c3IdentityB.userId, userBId);
        c3LoginError = null;
        c3LoginAttempts.push({ phase: 'explicit-new-user-action-after-stable-boundary', result: 'authenticated', loginPostCount: c3IdentityB.qaExplicitLoginPostCount, csrfGetCount: countCsrfGets() });
      } catch (error) {
        c3LoginError = error;
        c3LoginAttempts.push({ phase: 'explicit-new-user-action-after-stable-boundary', result: 'failed', errorName: error?.name ?? 'Error', loginPostCount: countLoginPosts(), csrfGetCount: countCsrfGets() });
      }
    } else {
      c3LoginError = new Error(`Unexpected /auth/me status ${serverAfterStableBoundary.status()} after the stable anonymous boundary`);
      c3LoginAttempts.push({ phase: 'server-recheck-after-stable-boundary', result: 'unexpected-status', status: serverAfterStableBoundary.status() });
    }
  }
  c3IdentityTraceB.loginAttempts = c3LoginAttempts;
  const observedA = await expectedIdentityOnA;
  const cookieMeResponse = await fileContext.request.get('/api/auth/me');
  const serverIdentity = cookieMeResponse.status() === 200 ? await cookieMeResponse.json() : { status: cookieMeResponse.status() };
  const serverCookieIsB = cookieMeResponse.status() === 200 && serverIdentity.userId === userBId;
  const tabAStateBeforeRecovery = await filePageA.evaluate(() => ({
    pathname: location.pathname,
    invalidations: window.__fileIdentitySmoke?.invalidations ?? 0,
    accountTriggerText: document.querySelector('.pro-account-trigger')?.textContent?.trim() ?? null,
  })).catch(error => ({ pathname: null, invalidations: 0, captureError: String(error) }));
  const sawInvalidation = c3IdentityTrace.sessionApiTrace.some(entry => entry.event === 'invalidated')
    || tabAStateBeforeRecovery.invalidations > c3InvalidationsBeforeLogout;
  let c3TabAOutcome = null;
  let c3TabAIdentity = observedA.identity ?? null;
  let c3IdentityFailure = null;
  try {
    assert.equal(serverCookieIsB, true, 'The shared server-side cookie must authenticate B before C3 can accept an identity transition');
    assert.equal(c3LoginError, null, `Tab B must complete a successful explicit login or protected-route recovery: ${String(c3LoginError ?? '')}`);
    await filePageB.locator('.pro-account-trigger').filter({ hasText: 'File identity QA B' }).waitFor({ state: 'visible', timeout: 15_000 });
    if (observedA.identity) {
      assert.equal(observedA.identity.userId, userBId);
      assert.notEqual(tabAStateBeforeRecovery.pathname, '/user/login', 'A direct B identity acceptance must still leave tab A on its protected route');
      await filePageA.locator('.pro-account-trigger').filter({ hasText: 'File identity QA B' }).waitFor({ state: 'visible', timeout: 15_000 });
      c3TabAOutcome = 'direct-authenticated-replacement';
    } else {
      assert.equal(tabAStateBeforeRecovery.pathname, '/user/login', 'Only a tab that actually reached the login page may use anonymous-boundary route recovery');
      assert.ok(sawInvalidation, 'Anonymous-boundary recovery requires a recorded nonce/session invalidation');
      const recoveredIdentity = waitForIdentity(filePageA, userBId, 15_000)
        .then(identity => ({ identity }), error => ({ error }));
      await filePageA.goto(c3ProtectedPath);
      const recoveryResult = await recoveredIdentity;
      if (recoveryResult.error) throw recoveryResult.error;
      assert.equal(recoveryResult.identity.userId, userBId, 'The explicit new protected-route navigation must fetch B from /auth/me');
      c3TabAIdentity = recoveryResult.identity;
      await filePageA.locator('.pro-account-trigger').filter({ hasText: 'File identity QA B' }).waitFor({ state: 'visible', timeout: 15_000 });
      await filePageA.getByRole('heading', { name: t.myFiles, exact: true }).waitFor({ state: 'visible', timeout: 15_000 });
      c3TabAOutcome = 'anonymous-boundary-protected-route-navigation';
    }
    const c3VerifiedTabA = await filePageA.evaluate(() => ({
      pathname: location.pathname,
      accountTriggerText: document.querySelector('.pro-account-trigger')?.textContent?.trim() ?? null,
    }));
    const c3ArtifactDir = path.join(projectRoot, '.runtime', 'security-integration-20261009', `files-identity-boundary-${suffix}`);
    mkdirSync(c3ArtifactDir, { recursive: true });
    const c3ResultPath = path.join(c3ArtifactDir, 'fs01-c3-tab-a-identity-result.json');
    writeFileSync(c3ResultPath, `${JSON.stringify({
      scenario: 'FS01-C3 held file-batch request during shared-cookie A-to-B change',
      result: 'passed',
      tabAOutcome: c3TabAOutcome,
      targetUserId: userBId,
      serverCookieStatus: cookieMeResponse.status(),
      serverIdentityUserId: serverIdentity.userId,
      tabAAuthMeUserId: c3TabAIdentity?.userId ?? null,
      tabA: c3VerifiedTabA,
      protectedPathBeforeLogout: c3ProtectedPath,
      explicitProtectedNavigation: c3TabAOutcome === 'anonymous-boundary-protected-route-navigation',
      invalidationsBeforeLogout: c3InvalidationsBeforeLogout,
      sawInvalidation,
      loginAttempts: c3LoginAttempts,
      createdAt: new Date().toISOString(),
    }, null, 2)}\n`);
    console.log(`PASS FS01-C3 tab A established /auth/me B and the B account control while the batch response was held (${c3TabAOutcome}); evidence: ${c3ResultPath}`);
  } catch (error) {
    c3IdentityFailure = error;
    // Let the in-flight batch route finish before collecting page state. The
    // request may have been canceled by the boundary and its response body
    // can remain unreadable, so both waits are deliberately bounded.
    resolveGate?.();
    await Promise.race([
      routeSettled,
      new Promise(resolve => setTimeout(resolve, 2_000)),
    ]);
    c3IdentityTrace.responseBodiesSettled = await settleTraceResponses(c3IdentityTrace);
    c3IdentityTraceB.responseBodiesSettled = await settleTraceResponses(c3IdentityTraceB);
    const artifacts = await saveC3IdentitySwitchEvidence(
      filePageA,
      c3IdentityTrace,
      error,
      serverIdentity,
      filePageB,
      c3IdentityTraceB,
      {
        observedAIdentityUserId: observedA.identity?.userId ?? null,
        observedAError: observedA.error ? String(observedA.error) : null,
        tabAStateBeforeRecovery,
        protectedPathBeforeLogout: c3ProtectedPath,
        invalidationsBeforeLogout: c3InvalidationsBeforeLogout,
        sawInvalidation,
        loginError: c3LoginError ? String(c3LoginError) : null,
        loginAttempts: c3LoginAttempts,
        serverCookieIsB,
      },
    );
    c3IdentityFailure = new Error(`FS01-C3 identity transition did not complete as expected${c3LoginError ? `; tab B login/identity error: ${String(c3LoginError)}` : ''}. Evidence: ${artifacts.reportPath}; screenshot: ${artifacts.screenshotPath}; tab A state: ${JSON.stringify(artifacts.pageState)}; tab B state: ${JSON.stringify(artifacts.peerPageState)}; invalidation=${sawInvalidation}`);
    console.error(`FAIL FS01-C3 tab A identity: ${c3IdentityFailure.message}`);
  }
  resolveGate();
  await Promise.race([
    routeSettled,
    new Promise((_, reject) => setTimeout(() => reject(new Error('The held QA batch request did not settle after identity change')), 15_000)),
  ]);
  await filePageA.waitForTimeout(750);
  assert.deepEqual(routeRequests, [firstId], 'After the delayed first item returns, identity cancellation must stop all remaining file writes');
  const fileStates = sql(`SELECT id || ':' || state FROM platform_file WHERE id IN (${[...fileIds].map(sqlQuote).join(',')}) ORDER BY id;`).split(/\r?\n/).filter(Boolean);
  assert.equal(fileStates.length, files.length);
  assert.ok(fileStates.every(value => value.endsWith(':READY')), 'Intercepted QA actions must leave both real QA records unchanged');
  assert.deepEqual(pageErrors, []);
  console.log('PASS FS01-C3: the first batch request was held across shared-cookie identity change; no second write was sent and both QA rows remain READY');
  if (c3IdentityFailure) throw c3IdentityFailure;
  if (c2BUsabilityFailure) throw c2BUsabilityFailure;
} finally {
  try { releaseStaleA401Gate?.(); } catch {}
  for (const context of contexts) {
    try { await context.unrouteAll({ behavior: 'ignoreErrors' }); } catch {}
  }
  try { resolveGate?.(); } catch {}
  await Promise.all(contexts.map(context => context.close()));
  await browser.close();
  cleanupFixtures();
}
