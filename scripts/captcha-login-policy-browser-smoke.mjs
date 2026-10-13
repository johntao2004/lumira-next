import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import { translate } from '../apps/web/src/platform/i18n/messages.ts';

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});

async function openMockLogin({
  alwaysRequired,
  requireAfterRisk,
  deferMethods = false,
  methodsFailFirst = false,
  initiallySupported = true,
  methodsBecomeSupported = false,
}) {
  const context = await browser.newContext({
    baseURL: 'http://127.0.0.1:8010',
    viewport: { width: 1134, height: 869 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  const loginBodies = [];
  const challengeBodies = [];
  const methodsHeaders = [];
  let methodsRequests = 0;
  let releaseMethods;
  const methodsGate = new Promise(resolve => { releaseMethods = resolve; });
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.route('**/api/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(/^\/api/, '');
    if (path === '/auth/me') {
      await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ detail: 'authenticationRequired' }) });
    } else if (path === '/auth/login/state') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        authenticated: false, secondFactorRequired: false, challengeId: null, expiresAt: null, state: 'NONE',
      }) });
    } else if (path === '/auth/methods') {
      methodsRequests += 1;
      methodsHeaders.push(await request.allHeaders());
      if (deferMethods) await methodsGate;
      if (methodsFailFirst && methodsRequests === 1) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ detail: 'unavailable' }) });
        return;
      }
      const captchaSupported = methodsBecomeSupported && methodsRequests > 1 ? true : initiallySupported;
      const currentAlwaysRequired = methodsBecomeSupported && methodsRequests > 1 ? true : alwaysRequired;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        methods: ['PASSWORD'],
        registration: { supported: false, available: false, channels: [], path: null },
        capabilities: { loginCode: false, wechat: false, captcha: captchaSupported, rolePreview: false },
        captcha: {
          required: currentAlwaysRequired,
          type: 'IMAGE_TEXT',
          actions: ['PASSWORD_LOGIN'],
          alwaysActions: currentAlwaysRequired ? ['PASSWORD_LOGIN'] : [],
        },
      }) });
    } else if (path === '/auth/passkeys/authentication/availability') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ enabled: false }) });
    } else if (path === '/auth/csrf') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'mock-csrf-token' }) });
    } else if (path === '/auth/login' && request.method() === 'POST') {
      loginBodies.push(request.postData());
      if (requireAfterRisk) {
        await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ detail: 'captchaRequired' }) });
      } else {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ authenticated: false, secondFactorRequired: false, state: 'COMPLETE' }) });
      }
    } else if (path === '/auth/captcha/challenges' && request.method() === 'POST') {
      const body = request.postDataJSON();
      challengeBodies.push(body);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        challengeId: '11111111-1111-4111-8111-111111111111',
        action: body.action,
        attemptId: null,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
        provider: 'LOCAL',
        type: 'IMAGE_TEXT',
        accessibleAlternativeAvailable: false,
        imageDataUrl: 'data:image/png;base64,ZmFrZQ==',
        backgroundDataUrl: null,
        pieceDataUrl: null,
        audioDataUrl: null,
        width: 180,
        height: 64,
        pieceWidth: 0,
        pieceHeight: 0,
        pieceY: 0,
      }) });
    } else {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ detail: 'mockEndpointNotConfigured' }) });
    }
  });
  return { context, page, pageErrors, loginBodies, challengeBodies, methodsHeaders, releaseMethods, get methodsRequests() { return methodsRequests; } };
}

try {
  const always = await openMockLogin({ alwaysRequired: true, requireAfterRisk: false });
  await always.page.goto('/user/login');
  await always.page.getByLabel(translate('zh-CN', 'username'), { exact: true }).fill('mock-user');
  await always.page.getByLabel(translate('zh-CN', 'password'), { exact: true }).fill('mock-password');
  await always.page.getByRole('button', { name: translate('zh-CN', 'signIn'), exact: true }).click();
  const alwaysPanel = always.page.getByRole('region', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true });
  await expect(alwaysPanel).toBeVisible();
  assert.equal(always.loginBodies.length, 0, 'Always policy opens the challenge before posting credentials');
  await alwaysPanel.getByRole('button', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true }).click();
  await expect(alwaysPanel.locator('.captcha-image-text')).toBeVisible();
  assert.deepEqual(always.challengeBodies.map(body => body.action), ['PASSWORD_LOGIN']);
  assert.deepEqual(always.pageErrors, []);
  await always.context.close();

  const delayedMethods = await openMockLogin({ alwaysRequired: true, requireAfterRisk: false, deferMethods: true });
  await delayedMethods.page.goto('/user/login');
  const delayedSignIn = delayedMethods.page.getByRole('button', { name: translate('zh-CN', 'signIn'), exact: true });
  await expect(delayedSignIn).toBeVisible();
  await delayedMethods.page.getByLabel(translate('zh-CN', 'username'), { exact: true }).fill('mock-user');
  await delayedMethods.page.getByLabel(translate('zh-CN', 'password'), { exact: true }).fill('mock-password');
  await expect(delayedSignIn).toBeDisabled();
  await delayedMethods.page.locator('input[type="password"]').press('Enter');
  await delayedMethods.page.waitForTimeout(150);
  assert.equal(delayedMethods.loginBodies.length, 0, 'password login waits for the CAPTCHA policy before dispatch');
  delayedMethods.releaseMethods();
  await expect(delayedSignIn).toBeEnabled();
  await delayedSignIn.click();
  const delayedPanel = delayedMethods.page.getByRole('region', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true });
  await expect(delayedPanel).toBeVisible();
  assert.equal(delayedMethods.loginBodies.length, 0, 'Always policy opens CAPTCHA before posting credentials after methods load');
  await delayedPanel.getByRole('button', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true }).click();
  await expect(delayedPanel.locator('.captcha-image-text')).toBeVisible();
  assert.deepEqual(delayedMethods.challengeBodies.map(body => body.action), ['PASSWORD_LOGIN']);
  assert.deepEqual(delayedMethods.pageErrors, []);
  await delayedMethods.context.close();

  const failedMethods = await openMockLogin({ alwaysRequired: true, requireAfterRisk: false, methodsFailFirst: true });
  await failedMethods.page.goto('/user/login');
  const failedSignIn = failedMethods.page.getByRole('button', { name: translate('zh-CN', 'signIn'), exact: true });
  await failedMethods.page.getByLabel(translate('zh-CN', 'username'), { exact: true }).fill('mock-user');
  await failedMethods.page.getByLabel(translate('zh-CN', 'password'), { exact: true }).fill('mock-password');
  await expect(failedSignIn).toBeDisabled();
  await failedMethods.page.locator('input[type="password"]').press('Enter');
  await expect(failedMethods.page.getByText(translate('zh-CN', 'unavailable'), { exact: true })).toBeVisible();
  assert.equal(failedMethods.loginBodies.length, 0, 'failed policy loading keeps credentials blocked');
  assert.equal(failedMethods.challengeBodies.length, 0, 'failed policy loading does not guess CAPTCHA policy');
  await failedMethods.page.getByRole('button', { name: translate('zh-CN', 'retry'), exact: true }).click();
  await expect(failedSignIn).toBeEnabled();
  await failedSignIn.click();
  const recoveredPolicyPanel = failedMethods.page.getByRole('region', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true });
  await expect(recoveredPolicyPanel).toBeVisible();
  assert.equal(failedMethods.methodsRequests, 2, 'retry reloads the public authentication policy');
  assert.equal(failedMethods.loginBodies.length, 0, 'known always policy opens CAPTCHA before credentials are sent');
  assert.deepEqual(failedMethods.challengeBodies.map(body => body.action), ['PASSWORD_LOGIN']);
  assert.deepEqual(failedMethods.pageErrors, []);
  await failedMethods.context.close();

  const unsupportedRequired = await openMockLogin({
    alwaysRequired: true,
    requireAfterRisk: false,
    initiallySupported: false,
    methodsBecomeSupported: true,
  });
  await unsupportedRequired.page.goto('/user/login');
  const unsupportedSignIn = unsupportedRequired.page.getByRole('button', { name: translate('zh-CN', 'signIn'), exact: true });
  await unsupportedRequired.page.getByLabel(translate('zh-CN', 'username'), { exact: true }).fill('mock-user');
  await unsupportedRequired.page.getByLabel(translate('zh-CN', 'password'), { exact: true }).fill('mock-password');
  await expect(unsupportedSignIn).toBeDisabled();
  await expect(unsupportedRequired.page.getByText(translate('zh-CN', 'captchaRequiredUnavailable'), { exact: true })).toBeVisible();
  assert.equal(unsupportedRequired.loginBodies.length, 0, 'known required CAPTCHA with unavailable support never posts credentials');
  assert.equal(unsupportedRequired.challengeBodies.length, 0, 'the UI does not fabricate a challenge when capability is unavailable');
  await unsupportedRequired.page.getByRole('button', { name: translate('zh-CN', 'retry'), exact: true }).click();
  await expect(unsupportedSignIn).toBeEnabled();
  await unsupportedSignIn.click();
  const supportedAgainPanel = unsupportedRequired.page.getByRole('region', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true });
  await expect(supportedAgainPanel).toBeVisible();
  assert.equal(unsupportedRequired.loginBodies.length, 0, 'retry uses the restored always-required policy before credentials');
  assert.deepEqual(unsupportedRequired.challengeBodies.map(body => body.action), ['PASSWORD_LOGIN']);
  await unsupportedRequired.context.close();

  const refreshedPolicy = await openMockLogin({
    alwaysRequired: false,
    requireAfterRisk: true,
    initiallySupported: false,
    methodsBecomeSupported: true,
  });
  await refreshedPolicy.page.goto('/user/login');
  await refreshedPolicy.page.getByLabel(translate('zh-CN', 'username'), { exact: true }).fill('mock-user');
  await refreshedPolicy.page.getByLabel(translate('zh-CN', 'password'), { exact: true }).fill('mock-password');
  await refreshedPolicy.page.getByRole('button', { name: translate('zh-CN', 'signIn'), exact: true }).click();
  const refreshedPanel = refreshedPolicy.page.getByRole('region', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true });
  await expect(refreshedPanel).toBeVisible();
  assert.equal(refreshedPolicy.loginBodies.length, 1, 'risk policy submits once before the server signals a changed requirement');
  assert.equal(refreshedPolicy.methodsRequests, 2, 'stale unsupported capability triggers a public policy refresh');
  await refreshedPanel.getByRole('button', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true }).click();
  await expect(refreshedPanel.locator('.captcha-image-text')).toBeVisible();
  assert.deepEqual(refreshedPolicy.challengeBodies.map(body => body.action), ['PASSWORD_LOGIN']);
  assert.equal(refreshedPolicy.loginBodies.length, 1, 'credentials are not retried until a proof is entered');
  assert.deepEqual(refreshedPolicy.pageErrors, []);
  await refreshedPolicy.context.close();

  const unsupportedRefresh = await openMockLogin({
    alwaysRequired: false,
    requireAfterRisk: true,
    initiallySupported: false,
  });
  await unsupportedRefresh.page.goto('/user/login');
  await unsupportedRefresh.page.getByLabel(translate('zh-CN', 'username'), { exact: true }).fill('mock-user');
  await unsupportedRefresh.page.getByLabel(translate('zh-CN', 'password'), { exact: true }).fill('mock-password');
  const unsupportedRefreshSignIn = unsupportedRefresh.page.getByRole('button', { name: translate('zh-CN', 'signIn'), exact: true });
  await unsupportedRefreshSignIn.click();
  await expect(unsupportedRefresh.page.getByText(translate('zh-CN', 'captchaUnavailable'), { exact: true })).toBeVisible();
  await expect(unsupportedRefreshSignIn).toBeDisabled();
  assert.equal(unsupportedRefresh.methodsRequests, 2, 'captchaRequired performs a capability refresh before any retry');
  assert.equal(unsupportedRefresh.loginBodies.length, 1, 'unsupported refresh never retries the credential request');
  assert.equal(unsupportedRefresh.challengeBodies.length, 0, 'no challenge is presented without confirmed provider capability');
  assert.deepEqual(unsupportedRefresh.pageErrors, []);
  await unsupportedRefresh.context.close();

  const risk = await openMockLogin({ alwaysRequired: false, requireAfterRisk: true });
  await risk.page.goto('/user/login');
  await risk.page.getByLabel(translate('zh-CN', 'username'), { exact: true }).fill('mock-user');
  await risk.page.getByLabel(translate('zh-CN', 'password'), { exact: true }).fill('mock-password');
  await expect(risk.page.getByRole('region', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true })).toHaveCount(0);
  await risk.page.getByRole('button', { name: translate('zh-CN', 'signIn'), exact: true }).click();
  const riskPanel = risk.page.getByRole('region', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true });
  await expect(riskPanel).toBeVisible();
  assert.equal(risk.loginBodies.length, 1, 'Risk policy first submits to the server and responds to captchaRequired');
  await riskPanel.getByRole('button', { name: translate('zh-CN', 'captchaHumanVerification'), exact: true }).click();
  await expect(riskPanel.locator('.captcha-image-text')).toBeVisible();
  assert.deepEqual(risk.challengeBodies.map(body => body.action), ['PASSWORD_LOGIN']);
  assert.deepEqual(risk.pageErrors, []);
  await risk.context.close();

  const logoutRefresh = await openMockLogin({ alwaysRequired: true, requireAfterRisk: false });
  await logoutRefresh.context.addCookies([{ name: 'lumira-session-test', value: 'opaque', domain: '127.0.0.1', path: '/' }]);
  await logoutRefresh.page.goto('/user/login');
  const logoutRefreshButton = logoutRefresh.page.getByRole('button', { name: translate('zh-CN', 'signIn'), exact: true });
  await expect(logoutRefreshButton).toBeEnabled();
  await logoutRefresh.page.evaluate(() => window.dispatchEvent(new StorageEvent('storage', {
    key: 'lumira:session-invalidated', newValue: 'external-logout-boundary',
  })));
  await expect.poll(() => logoutRefresh.methodsRequests).toBe(2);
  await expect(logoutRefreshButton).toBeEnabled();
  assert.equal(logoutRefresh.loginBodies.length, 0, 'logout-boundary policy refresh never submits credentials');
  assert.ok(logoutRefresh.methodsHeaders.every(headers => !headers.cookie && !headers['x-lumira-authorization-context-revision']),
    'public auth-method policy requests omit session cookies and stale authorization revisions');
  assert.deepEqual(logoutRefresh.pageErrors, []);
  await logoutRefresh.context.close();

  console.log('PASS: isolated API mocks cover fail-closed policy loading, retry, stale capability refresh, and public policy refresh after a logout boundary without cookies or stale authorization revisions');
} finally {
  await browser.close();
}
