import { chromium, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import { translate } from '../apps/web/src/platform/i18n/messages.ts';

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});

async function openMockLogin({ alwaysRequired, requireAfterRisk }) {
  const context = await browser.newContext({
    baseURL: 'http://127.0.0.1:8010',
    viewport: { width: 1134, height: 869 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  const loginBodies = [];
  const challengeBodies = [];
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
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        methods: ['PASSWORD'],
        registration: { supported: false, available: false, channels: [], path: null },
        capabilities: { loginCode: false, wechat: false, captcha: true, rolePreview: false },
        captcha: {
          required: alwaysRequired,
          type: 'IMAGE_TEXT',
          actions: ['PASSWORD_LOGIN'],
          alwaysActions: alwaysRequired ? ['PASSWORD_LOGIN'] : [],
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
  return { context, page, pageErrors, loginBodies, challengeBodies };
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

  console.log('PASS: isolated API mocks show Always CAPTCHA before the login POST, Risk CAPTCHA after a server captchaRequired response, and both display the challenge image without changing saved settings or authenticating a user');
} finally {
  await browser.close();
}
