import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { translate } from '../apps/web/src/platform/i18n/messages.ts';

const playwrightModule = process.env.PLAYWRIGHT_MODULE ?? '@playwright/test';
const { chromium, expect } = await import(playwrightModule);
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const artifactDirectory = path.join(repositoryRoot, '.runtime', 'captcha-login-layout');
await mkdir(artifactDirectory, { recursive: true });

const locale = 'en';
const username = 'mock-user';
const password = 'mock-password';
const captchaAnswerLabel = translate(locale, 'captchaDigitAnswer');
const captchaRefreshLabel = translate(locale, 'captchaRefresh');
const captchaCancelLabel = translate(locale, 'captchaCancel');
const captchaVerifyLabel = translate(locale, 'captchaHumanVerification');
const unavailableLabel = translate(locale, 'unavailable');
const retryLabel = translate(locale, 'retry');
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}),
});

const report = {
  startedAt: new Date().toISOString(),
  execution: 'local browser with isolated API mocks; no CAPTCHA solving or business-data writes',
  screenshots: {},
  scenarios: [],
};

async function openMockLogin({
  alwaysRequired,
  requireAfterRisk = false,
  deferMethods = false,
  methodsFailFirst = false,
  initiallySupported = true,
  methodsBecomeSupported = false,
  policyBecomesAlways = false,
  invalidVerifications = 0,
  challengeFailsFirst = false,
} = {}) {
  const context = await browser.newContext({
    baseURL: 'http://127.0.0.1:8010',
    viewport: { width: 1440, height: 960 },
    locale: 'en-US',
  });
  await context.addInitScript(language => window.localStorage.setItem('lumira-next.locale', language), locale);
  const page = await context.newPage();
  const pageErrors = [];
  const loginBodies = [];
  const challengeBodies = [];
  const challengeIds = [];
  const verifyBodies = [];
  const proofIds = [];
  const confirmBodies = [];
  const methodsHeaders = [];
  const methodsPolicies = [];
  let methodsRequests = 0;
  let loginRequests = 0;
  let riskCaptchaRequested = false;
  let successfulVerifications = 0;
  let releaseMethods;
  const methodsGate = new Promise(resolve => { releaseMethods = resolve; });
  page.on('pageerror', error => pageErrors.push(error.message));

  await page.route('**/api/**', async route => {
    const request = route.request();
    const pathName = new URL(request.url()).pathname.replace(/^\/api/, '');
    const method = request.method();
    const fulfillJson = (status, body) => route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });

    if (pathName === '/auth/me') {
      await fulfillJson(401, { detail: 'authenticationRequired' });
    } else if (pathName === '/auth/login/state') {
      await fulfillJson(200, {
        authenticated: false,
        secondFactorRequired: false,
        challengeId: null,
        expiresAt: null,
        state: 'NONE',
      });
    } else if (pathName === '/auth/methods') {
      methodsRequests += 1;
      methodsHeaders.push(await request.allHeaders());
      if (deferMethods) await methodsGate;
      if (methodsFailFirst && methodsRequests === 1) {
        await fulfillJson(503, { detail: 'unavailable' });
        return;
      }
      const captchaSupported = methodsBecomeSupported && methodsRequests > 1 ? true : initiallySupported;
      const currentAlwaysRequired = alwaysRequired || (policyBecomesAlways && riskCaptchaRequested);
      methodsPolicies.push({ captchaSupported, alwaysRequired: currentAlwaysRequired });
      await fulfillJson(200, {
        methods: ['PASSWORD'],
        registration: { supported: false, available: false, channels: [], path: null },
        capabilities: { loginCode: false, wechat: false, captcha: captchaSupported, rolePreview: false },
        captcha: {
          required: currentAlwaysRequired,
          type: 'IMAGE_TEXT',
          actions: ['PASSWORD_LOGIN'],
          alwaysActions: currentAlwaysRequired ? ['PASSWORD_LOGIN'] : [],
        },
      });
    } else if (pathName === '/auth/passkeys/authentication/availability') {
      await fulfillJson(200, { enabled: false });
    } else if (pathName === '/auth/csrf') {
      await fulfillJson(200, { headerName: 'X-CSRF-TOKEN', token: 'mock-csrf-token' });
    } else if (pathName === '/auth/captcha/challenges' && method === 'POST') {
      const body = request.postDataJSON();
      challengeBodies.push(body);
      if (challengeFailsFirst && challengeBodies.length === 1) {
        await fulfillJson(503, { detail: 'unavailable' });
        return;
      }
      const sequence = challengeIds.length + 1;
      const challengeId = `11111111-1111-4111-8111-${String(sequence).padStart(12, '0')}`;
      challengeIds.push(challengeId);
      await fulfillJson(200, {
        challengeId,
        action: body.action,
        attemptId: null,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
        provider: 'LOCAL',
        type: 'IMAGE_TEXT',
        accessibleAlternativeAvailable: false,
        imageDataUrl: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/M2QAAAAASUVORK5CYII=',
        backgroundDataUrl: null,
        pieceDataUrl: null,
        audioDataUrl: null,
        width: 180,
        height: 64,
        pieceWidth: 0,
        pieceHeight: 0,
        pieceY: 0,
      });
    } else if (pathName === '/auth/captcha/verify' && method === 'POST') {
      const body = request.postDataJSON();
      verifyBodies.push(body);
      if (verifyBodies.length <= invalidVerifications) {
        await fulfillJson(400, { detail: 'captchaInvalid' });
        return;
      }
      successfulVerifications += 1;
      const proofId = `mock-password-login-proof-${successfulVerifications}`;
      proofIds.push(proofId);
      await fulfillJson(200, {
        captchaProofId: proofId,
        action: body.action ?? 'PASSWORD_LOGIN',
        attemptId: null,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      });
    } else if (pathName === '/auth/login' && method === 'POST') {
      loginRequests += 1;
      const body = Object.fromEntries(new URLSearchParams(request.postData() ?? ''));
      loginBodies.push(body);
      if (requireAfterRisk && loginRequests === 1) {
        riskCaptchaRequested = true;
        await fulfillJson(400, { detail: 'captchaRequired' });
        return;
      }
      await fulfillJson(401, { detail: 'invalidCredentials' });
    } else if (pathName === '/auth/login/captcha/confirm' && method === 'POST') {
      confirmBodies.push(request.postDataJSON());
      await fulfillJson(401, { detail: 'invalidCredentials' });
    } else {
      await fulfillJson(404, { detail: 'mockEndpointNotConfigured' });
    }
  });

  return {
    context,
    page,
    pageErrors,
    loginBodies,
    challengeBodies,
    challengeIds,
    verifyBodies,
    proofIds,
    confirmBodies,
    methodsHeaders,
    methodsPolicies,
    releaseMethods,
    get methodsRequests() { return methodsRequests; },
  };
}

async function runScenario(name, options, run) {
  const startedAt = Date.now();
  const test = await openMockLogin(options);
  try {
    await run(test);
    report.scenarios.push({ name, status: 'passed', durationMs: Date.now() - startedAt });
  } finally {
    await test.context.close();
  }
}

async function expectChallengeInput(page) {
  const input = page.getByTestId('password-login-captcha-answer');
  await expect(input).toBeVisible();
  return input;
}

async function clickAlertRetry(page, alertText) {
  const alert = page.locator('.ant-alert').filter({ hasText: alertText });
  await expect(alert).toHaveCount(1);
  const retryButton = alert.locator('xpath=../..').getByRole('button', { name: retryLabel, exact: true });
  await expect(retryButton).toHaveCount(1);
  await retryButton.click();
}

function assertNoPageErrors(test, scenario) {
  assert.deepEqual(test.pageErrors, [], `${scenario} should not raise browser page errors`);
}

function assertPasswordLoginChallengeRequests(test) {
  assert.ok(test.challengeBodies.length > 0, 'the policy should request a challenge');
  assert.ok(test.challengeBodies.every(body => body.action === 'PASSWORD_LOGIN'));
  assert.ok(test.challengeBodies.every(body => body.accessible === false));
  assert.ok(test.challengeBodies.every(body => typeof body.locale === 'string' && body.locale.length > 0));
}

const answerTestId = 'password-login-captcha-answer';

try {
  await runScenario('alwaysActions opens an inline challenge on page load and verifies before login', {
    alwaysRequired: true,
    requireAfterRisk: false,
    invalidVerifications: 1,
  }, async always => {
    await always.page.goto('/user/login');
    const answerInput = await expectChallengeInput(always.page);
    await expect.poll(() => always.challengeBodies.length).toBe(1);
    assertPasswordLoginChallengeRequests(always);

    const refreshButton = always.page.getByTestId('password-login-captcha-image-refresh');
    await expect(refreshButton).toHaveAccessibleName(captchaRefreshLabel);
    const challengeImage = refreshButton.locator('img');
    await expect(refreshButton).toBeVisible();
    await expect(challengeImage).toBeVisible();
    await expect(always.page.getByRole('button', { name: captchaVerifyLabel, exact: true })).toHaveCount(0);
    await expect(always.page.getByRole('button', { name: captchaCancelLabel, exact: true })).toHaveCount(0);

    const signInButton = always.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true });
    const desktopInput = await answerInput.boundingBox();
    const desktopImage = await refreshButton.boundingBox();
    assert.ok(desktopInput && desktopImage, 'desktop CAPTCHA controls should have measurable geometry');
    assert.ok(desktopInput.x + desktopInput.width <= desktopImage.x + 1, 'answer input should be to the left of the refreshable image');
    assert.ok(Math.abs((desktopInput.y + desktopInput.height / 2) - (desktopImage.y + desktopImage.height / 2)) <= 6,
      'answer input and refreshable image should share one horizontal row');

    const desktopScreenshot = path.join(artifactDirectory, 'mock-desktop.png');
    await always.page.screenshot({ path: desktopScreenshot, fullPage: true });
    report.screenshots.mockDesktop = { path: path.relative(repositoryRoot, desktopScreenshot), viewport: { width: 1440, height: 960 } };

    await always.page.setViewportSize({ width: 390, height: 844 });
    const mobileInput = await answerInput.boundingBox();
    const mobileImage = await refreshButton.boundingBox();
    const mobileDocument = await always.page.evaluate(() => ({ width: window.innerWidth, scrollWidth: document.documentElement.scrollWidth }));
    assert.ok(mobileInput && mobileImage, 'mobile CAPTCHA controls should remain measurable');
    assert.ok(mobileInput.x + mobileInput.width <= mobileImage.x + 1, 'mobile answer input should remain to the left of the image');
    assert.ok(mobileImage.x + mobileImage.width <= 390, 'mobile refreshable image should fit inside the viewport');
    assert.ok(mobileInput.x >= 0 && mobileDocument.scrollWidth <= mobileDocument.width,
      `mobile login should not overflow horizontally (scrollWidth=${mobileDocument.scrollWidth})`);
    const mobileScreenshot = path.join(artifactDirectory, 'mock-mobile.png');
    await always.page.screenshot({ path: mobileScreenshot, fullPage: true });
    report.screenshots.mockMobile = { path: path.relative(repositoryRoot, mobileScreenshot), viewport: { width: 390, height: 844 } };

    await always.page.getByLabel(translate(locale, 'username'), { exact: true }).fill(username);
    await always.page.getByLabel(translate(locale, 'password'), { exact: true }).fill(password);
    await expect(signInButton).toBeDisabled();
    await answerInput.fill('135790');
    const challengeBeforeRefresh = always.challengeIds.at(-1);
    await refreshButton.click();
    await expect.poll(() => always.challengeBodies.length).toBe(2);
    await expect(answerInput).toHaveValue('');
    assert.notEqual(always.challengeIds.at(-1), challengeBeforeRefresh, 'image click should replace the challenge');

    await answerInput.fill('111111');
    await expect(signInButton).toBeEnabled();
    await signInButton.click();
    await expect.poll(() => always.verifyBodies.length).toBe(1);
    assert.equal(always.verifyBodies[0].answer, '111111');
    assert.equal(always.loginBodies.length, 0, 'invalid CAPTCHA verification must not submit credentials');
    await expect(always.page.getByText(translate(locale, 'captchaInvalid'), { exact: true })).toBeVisible();
    await expect(answerInput).toHaveValue('111111');

    await answerInput.fill('222222');
    await signInButton.click();
    await expect.poll(() => always.verifyBodies.length).toBe(2);
    assert.equal(always.verifyBodies[1].answer, '222222', 'changing six digits must verify the latest answer');
    await expect.poll(() => always.loginBodies.length).toBe(1);
    assert.equal(always.loginBodies[0].captchaProofId, always.proofIds[0], 'successful verification proof should accompany the same login submission');
    assert.equal(always.loginBodies[0].username, username);
    assert.equal(always.loginBodies[0].password, password);
    assert.equal(always.loginBodies[0].captchaProofId, 'mock-password-login-proof-1');

    await expect.poll(() => always.challengeBodies.length).toBe(3);
    await expect(answerInput).toHaveValue('');
    assert.notEqual(always.challengeIds.at(-1), always.verifyBodies[1].challengeId,
      'a failed login should discard the proof challenge and load a new one');
    assert.equal(always.loginBodies.length, 1, 'refresh after a failed login must not repost credentials');
    assertPasswordLoginChallengeRequests(always);
    assertNoPageErrors(always, 'alwaysActions login');
  });

  await runScenario('a pending capability load blocks password submission until policy resolves', {
    alwaysRequired: true,
    requireAfterRisk: false,
    deferMethods: true,
  }, async delayedMethods => {
    await delayedMethods.page.goto('/user/login');
    const signInButton = delayedMethods.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true });
    await expect(signInButton).toBeVisible();
    await delayedMethods.page.getByLabel(translate(locale, 'username'), { exact: true }).fill(username);
    await delayedMethods.page.getByLabel(translate(locale, 'password'), { exact: true }).fill(password);
    await expect(signInButton).toBeDisabled();
    await delayedMethods.page.locator('input[type="password"]').press('Enter');
    await delayedMethods.page.waitForTimeout(150);
    assert.equal(delayedMethods.loginBodies.length, 0, 'credentials must wait for the CAPTCHA policy');
    delayedMethods.releaseMethods();
    const answerInput = await expectChallengeInput(delayedMethods.page);
    await expect.poll(() => delayedMethods.challengeBodies.length).toBeGreaterThan(0);
    assert.equal(delayedMethods.loginBodies.length, 0, 'the always policy should show its inline challenge before credentials are posted');
    assertPasswordLoginChallengeRequests(delayedMethods);
    assertNoPageErrors(delayedMethods, 'pending capability load');
  });

  await runScenario('an initial challenge load failure can be retried without submitting credentials', {
    alwaysRequired: true,
    requireAfterRisk: false,
    challengeFailsFirst: true,
  }, async failedChallenge => {
    await failedChallenge.page.goto('/user/login');
    const signInButton = failedChallenge.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true });
    await expect(failedChallenge.page.getByText(translate(locale, 'captchaUnavailable'), { exact: true })).toBeVisible();
    await expect(signInButton).toBeDisabled();
    assert.equal(failedChallenge.challengeBodies.length, 1, 'the first mock challenge request should fail');
    assert.equal(failedChallenge.loginBodies.length, 0, 'a challenge load failure must keep credentials blocked');
    const retryButton = failedChallenge.page.getByTestId('password-login-captcha-retry');
    await expect(retryButton).toHaveAccessibleName(retryLabel);
    await retryButton.click();
    const answerInput = await expectChallengeInput(failedChallenge.page);
    await expect.poll(() => failedChallenge.challengeBodies.length).toBe(2);
    await expect.poll(() => failedChallenge.challengeIds.length).toBe(1);
    await answerInput.fill('555555');
    await expect(answerInput).toHaveValue('555555');
    assert.equal(failedChallenge.loginBodies.length, 0, 'challenge recovery must not submit credentials');
    assertPasswordLoginChallengeRequests(failedChallenge);
    assertNoPageErrors(failedChallenge, 'challenge load retry');
  });

  await runScenario('failed capability loading can be retried and automatically restores the challenge', {
    alwaysRequired: true,
    requireAfterRisk: false,
    methodsFailFirst: true,
  }, async failedMethods => {
    await failedMethods.page.goto('/user/login');
    const signInButton = failedMethods.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true });
    await expect(failedMethods.page.getByText(unavailableLabel, { exact: true })).toBeVisible();
    await expect(signInButton).toBeDisabled();
    assert.equal(failedMethods.loginBodies.length, 0, 'failed policy loading must keep credentials blocked');
    assert.equal(failedMethods.challengeBodies.length, 0, 'failed policy loading must not guess the CAPTCHA policy');
    const methodsBeforeRetry = failedMethods.methodsRequests;
    await clickAlertRetry(failedMethods.page, unavailableLabel);
    const answerInput = await expectChallengeInput(failedMethods.page);
    await expect.poll(() => failedMethods.methodsRequests).toBeGreaterThan(methodsBeforeRetry);
    await expect.poll(() => failedMethods.challengeBodies.length).toBe(1);
    await expect(signInButton).toBeDisabled();
    assert.equal(await answerInput.inputValue(), '');
    assert.equal(failedMethods.loginBodies.length, 0);
    assertPasswordLoginChallengeRequests(failedMethods);
    assertNoPageErrors(failedMethods, 'capability loading retry');
  });

  await runScenario('a required but unavailable capability fails closed and recovers after retry', {
    alwaysRequired: true,
    requireAfterRisk: false,
    initiallySupported: false,
    methodsBecomeSupported: true,
  }, async unsupportedRequired => {
    await unsupportedRequired.page.goto('/user/login');
    const signInButton = unsupportedRequired.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true });
    await expect(unsupportedRequired.page.getByText(translate(locale, 'captchaRequiredUnavailable'), { exact: true })).toBeVisible();
    await expect(signInButton).toBeDisabled();
    assert.equal(unsupportedRequired.loginBodies.length, 0, 'required CAPTCHA without provider support must block credentials');
    assert.equal(unsupportedRequired.challengeBodies.length, 0, 'no challenge should be fabricated without provider support');
    const methodsBeforeRetry = unsupportedRequired.methodsRequests;
    await clickAlertRetry(unsupportedRequired.page, translate(locale, 'captchaRequiredUnavailable'));
    await expectChallengeInput(unsupportedRequired.page);
    await expect.poll(() => unsupportedRequired.methodsRequests).toBeGreaterThan(methodsBeforeRetry);
    await expect.poll(() => unsupportedRequired.challengeBodies.length).toBe(1);
    assert.equal(unsupportedRequired.loginBodies.length, 0);
    assertPasswordLoginChallengeRequests(unsupportedRequired);
    assertNoPageErrors(unsupportedRequired, 'unavailable capability recovery');
  });

  await runScenario('risk CAPTCHA appears only after the server requires it and preserves the shared panel flow', {
    alwaysRequired: false,
    requireAfterRisk: true,
  }, async risk => {
    await risk.page.goto('/user/login');
    await expect.poll(() => risk.methodsRequests).toBeGreaterThan(0);
    const signInButton = risk.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true });
    await risk.page.getByLabel(translate(locale, 'username'), { exact: true }).fill(username);
    await risk.page.getByLabel(translate(locale, 'password'), { exact: true }).fill(password);
    await expect(risk.page.getByTestId(answerTestId)).toHaveCount(0);
    await expect(risk.page.getByRole('region', { name: captchaVerifyLabel, exact: true })).toHaveCount(0);
    assert.equal(risk.challengeBodies.length, 0, 'risk policy should not preload a challenge before the server requires one');
    await signInButton.click();
    const riskPanel = risk.page.getByRole('region', { name: captchaVerifyLabel, exact: true });
    await expect(riskPanel).toBeVisible();
    assert.equal(risk.loginBodies.length, 1, 'risk policy first submits credentials and waits for the server challenge');
    assert.equal(risk.loginBodies[0].captchaProofId, undefined);
    await expect.poll(() => risk.challengeBodies.length).toBe(1);
    await expect(riskPanel.locator('.captcha-image-text')).toBeVisible();
    assertPasswordLoginChallengeRequests(risk);
    const panelAnswer = riskPanel.getByLabel(captchaAnswerLabel, { exact: true });
    await panelAnswer.fill('333333');
    const panelRefreshButtons = riskPanel.getByRole('button', { name: captchaRefreshLabel, exact: true });
    await expect(panelRefreshButtons).toHaveCount(2);
    await panelRefreshButtons.last().click();
    await expect.poll(() => risk.challengeBodies.length).toBe(2);
    await expect(panelAnswer).toHaveValue('');
    await panelAnswer.fill('444444');
    await riskPanel.getByRole('button', { name: translate(locale, 'captchaVerify'), exact: true }).click();
    await expect.poll(() => risk.loginBodies.length).toBe(2);
    assert.equal(risk.loginBodies[1].captchaProofId, risk.proofIds[0], 'risk CAPTCHA proof should retry the original login request');
    assert.equal(risk.confirmBodies.length, 0, 'risk CAPTCHA should keep the existing login retry flow');
    assertNoPageErrors(risk, 'risk CAPTCHA');
  });

  await runScenario('stale unsupported capability refreshes before showing risk CAPTCHA', {
    alwaysRequired: false,
    requireAfterRisk: true,
    initiallySupported: false,
    methodsBecomeSupported: true,
  }, async refreshedPolicy => {
    await refreshedPolicy.page.goto('/user/login');
    await expect.poll(() => refreshedPolicy.methodsRequests).toBeGreaterThan(0);
    const methodsBeforeRiskCaptcha = refreshedPolicy.methodsRequests;
    await refreshedPolicy.page.getByLabel(translate(locale, 'username'), { exact: true }).fill(username);
    await refreshedPolicy.page.getByLabel(translate(locale, 'password'), { exact: true }).fill(password);
    const signInButton = refreshedPolicy.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true });
    await signInButton.click();
    const refreshedPanel = refreshedPolicy.page.getByRole('region', { name: captchaVerifyLabel, exact: true });
    await expect(refreshedPanel).toBeVisible();
    await expect.poll(() => refreshedPolicy.methodsRequests).toBeGreaterThan(methodsBeforeRiskCaptcha);
    assert.equal(refreshedPolicy.loginBodies.length, 1, 'credentials must not be retried before a risk proof is supplied');
    await expect.poll(() => refreshedPolicy.challengeBodies.length).toBe(1);
    await expect(refreshedPanel.locator('.captcha-image-text')).toBeVisible();
    await refreshedPanel.getByLabel(captchaAnswerLabel, { exact: true }).fill('444444');
    await refreshedPanel.getByRole('button', { name: translate(locale, 'captchaVerify'), exact: true }).click();
    await expect.poll(() => refreshedPolicy.loginBodies.length).toBe(2);
    assert.equal(refreshedPolicy.loginBodies[1].captchaProofId, refreshedPolicy.proofIds[0]);
    assert.equal(refreshedPolicy.confirmBodies.length, 0);
    assertPasswordLoginChallengeRequests(refreshedPolicy);
    assertNoPageErrors(refreshedPolicy, 'stale capability refresh');
  });

  await runScenario('risk CAPTCHA policy changes to ALWAYS without duplicate proof panels or credential retries', {
    alwaysRequired: false,
    requireAfterRisk: true,
    initiallySupported: false,
    methodsBecomeSupported: true,
    policyBecomesAlways: true,
  }, async transition => {
    await transition.page.goto('/user/login');
    await expect.poll(() => transition.methodsRequests).toBeGreaterThan(0);
    assert.ok(transition.methodsPolicies.every(policy => !policy.alwaysRequired),
      'the initial policy should remain risk-based before the server requires CAPTCHA');
    const signInButton = transition.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true });
    await transition.page.getByLabel(translate(locale, 'username'), { exact: true }).fill(username);
    await transition.page.getByLabel(translate(locale, 'password'), { exact: true }).fill(password);
    await expect(transition.page.getByTestId(answerTestId)).toHaveCount(0);
    assert.equal(transition.challengeBodies.length, 0, 'risk-based policy should not preload a challenge');

    await signInButton.click();
    const proofPanel = transition.page.getByRole('region', { name: captchaVerifyLabel, exact: true });
    await expect(proofPanel).toBeVisible();
    await expect(proofPanel).toHaveCount(1);
    await expect.poll(() => transition.methodsPolicies.some(policy => policy.alwaysRequired)).toBe(true);
    await expect.poll(() => transition.challengeBodies.length).toBe(1);
    assert.equal(await transition.page.getByTestId(answerTestId).count(), 0,
      'the transition to ALWAYS must not mount a duplicate inline answer panel');
    assert.equal(transition.loginBodies.length, 1, 'the original login must wait for proof verification before retrying');
    assertPasswordLoginChallengeRequests(transition);
    assert.equal(transition.challengeBodies.length, 1, 'only one challenge should back the pending proof panel');
    assertNoPageErrors(transition, 'risk to ALWAYS policy transition');
  });

  await runScenario('unsupported risk CAPTCHA refresh does not retry credentials', {
    alwaysRequired: false,
    requireAfterRisk: true,
    initiallySupported: false,
  }, async unsupportedRefresh => {
    await unsupportedRefresh.page.goto('/user/login');
    await expect.poll(() => unsupportedRefresh.methodsRequests).toBeGreaterThan(0);
    const methodsBeforeRiskCaptcha = unsupportedRefresh.methodsRequests;
    await unsupportedRefresh.page.getByLabel(translate(locale, 'username'), { exact: true }).fill(username);
    await unsupportedRefresh.page.getByLabel(translate(locale, 'password'), { exact: true }).fill(password);
    const signInButton = unsupportedRefresh.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true });
    await signInButton.click();
    await expect(unsupportedRefresh.page.getByText(translate(locale, 'captchaUnavailable'), { exact: true })).toBeVisible();
    await expect(signInButton).toBeDisabled();
    assert.ok(unsupportedRefresh.methodsRequests > methodsBeforeRiskCaptcha, 'captchaRequired should refresh public capability');
    assert.equal(unsupportedRefresh.loginBodies.length, 1, 'unsupported refresh must not resend credentials');
    assert.equal(unsupportedRefresh.challengeBodies.length, 0, 'do not present a challenge without confirmed capability');
    assertNoPageErrors(unsupportedRefresh, 'unsupported risk CAPTCHA');
  });

  await runScenario('logout boundary reloads public policy without carrying session or authorization headers', {
    alwaysRequired: true,
    requireAfterRisk: false,
  }, async logoutRefresh => {
    await logoutRefresh.context.addCookies([{ name: 'lumira-session-test', value: 'opaque', domain: '127.0.0.1', path: '/' }]);
    await logoutRefresh.page.goto('/user/login');
    await expectChallengeInput(logoutRefresh.page);
    await expect.poll(() => logoutRefresh.challengeBodies.length).toBe(1);
    await expect(logoutRefresh.page.getByRole('button', { name: translate(locale, 'signIn'), exact: true })).toBeDisabled();
    const methodsBeforeLogoutRefresh = logoutRefresh.methodsRequests;
    await logoutRefresh.page.evaluate(() => window.dispatchEvent(new StorageEvent('storage', {
      key: 'lumira:session-invalidated',
      newValue: 'external-logout-boundary',
    })));
    await expect.poll(() => logoutRefresh.methodsRequests).toBeGreaterThan(methodsBeforeLogoutRefresh);
    await expect.poll(() => logoutRefresh.challengeBodies.length).toBeGreaterThan(1);
    assert.equal(logoutRefresh.loginBodies.length, 0, 'a logout-boundary policy refresh must not submit credentials');
    assert.ok(logoutRefresh.methodsHeaders.every(headers => !headers.cookie && !headers['x-lumira-authorization-context-revision']),
      'public auth-method policy requests must omit session cookies and stale authorization revisions');
    assertNoPageErrors(logoutRefresh, 'logout boundary');
  });

  report.status = 'passed';
  console.log(`PASS: ${report.scenarios.length} CAPTCHA login browser scenarios passed; screenshots and results are in .runtime/captcha-login-layout`);
} catch (error) {
  report.status = 'failed';
  report.failure = error instanceof Error ? { name: error.name, message: error.message, stack: error.stack } : String(error);
  throw error;
} finally {
  report.finishedAt = new Date().toISOString();
  await writeFile(path.join(artifactDirectory, 'results.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await browser.close();
}
