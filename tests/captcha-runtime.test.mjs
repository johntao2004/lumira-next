import assert from "node:assert/strict";
import test from "node:test";
import {
  appendSliderSample,
  buildSliderTrail,
  createCaptchaAttemptGuard,
  createCaptchaProofController,
  isLocalCaptchaChallenge,
  normalizeCaptchaAnswer,
  replaceCaptchaResolver,
  resolveCaptchaResolver,
} from "../apps/web/src/platform/auth/captcha-runtime.mjs";

const future = "2099-01-01T00:00:00.000Z";
const base = {
  challengeId: "c0d4a252-1bb4-45ab-9f7d-b1048a36ac8b",
  action: "PASSWORD_LOGIN",
  attemptId: null,
  expiresAt: future,
  provider: "LOCAL",
  accessibleAlternativeAvailable: true,
};

test("challenge generation invalidates results from refresh and cancel", () => {
  const generation = createCaptchaAttemptGuard();
  const first = generation.begin();
  const second = generation.begin();
  assert.equal(generation.isCurrent(first), false);
  assert.equal(generation.isCurrent(second), true);
  generation.invalidate();
  assert.equal(generation.isCurrent(second), false);
});

test("replacing a pending proof resolver settles the old action as cancelled", async () => {
  const holder = { current: null };
  const firstResult = new Promise(resolve => replaceCaptchaResolver(holder, resolve));
  let secondValue;
  replaceCaptchaResolver(holder, value => { secondValue = value; });
  assert.equal(await firstResult, null);
  resolveCaptchaResolver(holder, "fresh-proof");
  assert.equal(secondValue, "fresh-proof");
  assert.equal(holder.current, null);
});

function captchaOptions(shouldPreflight = true) {
  return {
    shouldPreflight: () => shouldPreflight,
    supported: true,
    unavailableError: () => new Error("captchaUnavailable"),
    isCaptchaRequired: cause => cause instanceof Error && cause.message === "captchaRequired",
  };
}

function tick() {
  return new Promise(resolve => setImmediate(resolve));
}

test("captchaRequired retries once with a fresh matching proof", async () => {
  const actions = [];
  const controller = createCaptchaProofController(action => actions.push(action));
  let attempts = 0;
  const run = controller.run("PASSWORD_LOGIN", captchaOptions(), async proofId => {
    attempts += 1;
    if (attempts === 1) {
      assert.equal(proofId, "proof-first");
      throw new Error("captchaRequired");
    }
    assert.equal(proofId, "proof-retry");
    return "signed-in";
  });

  assert.equal(actions.at(-1), "PASSWORD_LOGIN");
  assert.equal(controller.finish({ action: "PASSWORD_LOGIN", captchaProofId: "proof-first" }), true);
  await tick();
  assert.equal(actions.at(-1), "PASSWORD_LOGIN");
  assert.equal(controller.finish({ action: "PASSWORD_LOGIN", captchaProofId: "proof-retry" }), true);

  assert.deepEqual(await run, { status: "completed", value: "signed-in" });
  assert.equal(attempts, 2);
  controller.dispose();
});

test("a second captchaRequired failure is returned without a third attempt", async () => {
  const controller = createCaptchaProofController();
  let attempts = 0;
  const run = controller.run("LOGIN_CODE_VERIFY", captchaOptions(), async () => {
    attempts += 1;
    throw new Error("captchaRequired");
  });

  controller.finish({ action: "LOGIN_CODE_VERIFY", captchaProofId: "proof-first" });
  await tick();
  controller.finish({ action: "LOGIN_CODE_VERIFY", captchaProofId: "proof-retry" });
  await assert.rejects(run, /captchaRequired/);
  assert.equal(attempts, 2);
  controller.dispose();
});

test("cancelling preflight or a required retry never submits the next request", async () => {
  const beforeSubmit = createCaptchaProofController();
  let preflightAttempts = 0;
  const preflightRun = beforeSubmit.run("REGISTRATION_SEND", captchaOptions(), async () => {
    preflightAttempts += 1;
  });
  beforeSubmit.cancel();
  assert.deepEqual(await preflightRun, { status: "cancelled" });
  assert.equal(preflightAttempts, 0);
  beforeSubmit.dispose();

  const beforeRetry = createCaptchaProofController();
  let retryAttempts = 0;
  const retryRun = beforeRetry.run("PASSWORD_RECOVERY_REQUEST", captchaOptions(false), async () => {
    retryAttempts += 1;
    throw new Error("captchaRequired");
  });
  await tick();
  beforeRetry.cancel();
  assert.deepEqual(await retryRun, { status: "cancelled" });
  assert.equal(retryAttempts, 1);
  beforeRetry.dispose();
});

test("cancellation before the queued operation dispatch does not call the operation", async () => {
  const controller = createCaptchaProofController();
  let operations = 0;
  const options = {
    ...captchaOptions(false),
    shouldPreflight: () => {
      queueMicrotask(() => queueMicrotask(() => controller.cancel()));
      return false;
    },
  };
  const run = controller.run("PASSKEY_LOGIN", options, () => {
    operations += 1;
    return "must-not-start";
  });

  assert.deepEqual(await run, { status: "cancelled" });
  assert.equal(operations, 0);
  controller.dispose();
});

test("disposal before the queued operation dispatch does not call the operation", async () => {
  const controller = createCaptchaProofController();
  let operations = 0;
  const options = {
    ...captchaOptions(false),
    shouldPreflight: () => {
      queueMicrotask(() => queueMicrotask(() => controller.dispose()));
      return false;
    },
  };
  const run = controller.run("LOGIN_CODE_VERIFY", options, () => {
    operations += 1;
    return "must-not-start";
  });

  assert.deepEqual(await run, { status: "cancelled" });
  assert.equal(operations, 0);
});

test("a current run still dispatches its operation exactly once", async () => {
  const controller = createCaptchaProofController();
  let operations = 0;
  const run = controller.run("WECHAT_LOGIN", captchaOptions(false), async () => {
    operations += 1;
    return "authorization-attempt";
  });

  assert.deepEqual(await run, { status: "completed", value: "authorization-attempt" });
  assert.equal(operations, 1);
  controller.dispose();
});

test("a replacement run cancels the old proof and unmount suppresses stale results", async () => {
  const actions = [];
  const controller = createCaptchaProofController(action => actions.push(action));
  let oldAttempts = 0;
  let freshAttempts = 0;
  const oldRun = controller.run("PASSWORD_LOGIN", captchaOptions(), async () => { oldAttempts += 1; });
  const freshRun = controller.run("WECHAT_LOGIN", captchaOptions(), async proofId => {
    freshAttempts += 1;
    return proofId;
  });

  assert.deepEqual(await oldRun, { status: "cancelled" });
  assert.equal(actions.at(-1), "WECHAT_LOGIN");
  assert.equal(controller.finish({ action: "PASSWORD_LOGIN", captchaProofId: "stale" }), false);
  assert.equal(controller.finish({ action: "WECHAT_LOGIN", captchaProofId: "fresh" }), true);
  assert.deepEqual(await freshRun, { status: "completed", value: "fresh" });
  assert.equal(oldAttempts, 0);
  assert.equal(freshAttempts, 1);

  let resolveOperation;
  const pendingOperation = controller.run("PASSKEY_LOGIN", captchaOptions(false), () => new Promise(resolve => { resolveOperation = resolve; }));
  await tick();
  assert.equal(typeof resolveOperation, "function");
  controller.dispose();
  const transitionsAfterDispose = actions.length;
  assert.deepEqual(await pendingOperation, { status: "cancelled" });
  resolveOperation("late-options");
  await tick();
  assert.equal(actions.length, transitionsAfterDispose);
  assert.equal(controller.isMounted(), false);
});

test("proof completion ignores a proof for the wrong action", async () => {
  const controller = createCaptchaProofController();
  const run = controller.run("LOGIN_CODE_SEND", captchaOptions(), async proofId => proofId);
  assert.equal(controller.finish({ action: "LOGIN_CODE_VERIFY", captchaProofId: "wrong-action" }), false);
  assert.equal(controller.finish({ action: "LOGIN_CODE_SEND", captchaProofId: "matching-action" }), true);
  assert.deepEqual(await run, { status: "completed", value: "matching-action" });
  controller.dispose();
});

test("six-digit input sanitization preserves leading zeroes", () => {
  assert.equal(normalizeCaptchaAnswer("0a01-23 45"), "001234");
  assert.equal(normalizeCaptchaAnswer("000001"), "000001");
  assert.equal(normalizeCaptchaAnswer("１２３４５６"), "");
  assert.equal(normalizeCaptchaAnswer("123456789"), "123456");
});

test("local text, slider, and audio challenges require their matching render assets", () => {
  const imageText = { ...base, type: "IMAGE_TEXT", imageDataUrl: "data:image/png;base64,AA==", width: 160, height: 56 };
  const audio = { ...base, type: "AUDIO", audioDataUrl: "data:audio/wav;base64,AA==", width: 0, height: 0 };
  const slider = {
    ...base,
    type: "SLIDER",
    backgroundDataUrl: "data:image/png;base64,AA==",
    pieceDataUrl: "data:image/png;base64,AA==",
    width: 320,
    height: 160,
    pieceWidth: 58,
    pieceHeight: 58,
    pieceY: 32,
  };
  assert.equal(isLocalCaptchaChallenge(imageText, "PASSWORD_LOGIN", undefined, 1), true);
  assert.equal(isLocalCaptchaChallenge(audio, "PASSWORD_LOGIN", undefined, 1), true);
  assert.equal(isLocalCaptchaChallenge(slider, "PASSWORD_LOGIN", undefined, 1), true);
  assert.equal(isLocalCaptchaChallenge({ ...imageText, imageDataUrl: undefined }, "PASSWORD_LOGIN", undefined, 1), false);
  assert.equal(isLocalCaptchaChallenge({ ...imageText, imageDataUrl: "data:image/svg+xml;base64,AA==" }, "PASSWORD_LOGIN", undefined, 1), false);
  assert.equal(isLocalCaptchaChallenge({ ...audio, audioDataUrl: "data:audio/mpeg;base64,AA==" }, "PASSWORD_LOGIN", undefined, 1), false);
  assert.equal(isLocalCaptchaChallenge({ ...slider, pieceY: 150 }, "PASSWORD_LOGIN", undefined, 1), false);
});

test("challenge response must match the action, attempt, provider, and expiry", () => {
  const challenge = {
    ...base,
    type: "IMAGE_TEXT",
    imageDataUrl: "data:image/png;base64,AA==",
    width: 160,
    height: 56,
  };
  assert.equal(isLocalCaptchaChallenge(challenge, "PASSWORD_LOGIN", undefined, 1), true);
  assert.equal(isLocalCaptchaChallenge(challenge, "PASSKEY_LOGIN", undefined, 1), false);
  assert.equal(isLocalCaptchaChallenge({ ...challenge, attemptId: "old-attempt" }, "PASSWORD_LOGIN", "current-attempt", 1), false);
  assert.equal(isLocalCaptchaChallenge({ ...challenge, provider: "TENCENT" }, "PASSWORD_LOGIN", undefined, 1), false);
  assert.equal(isLocalCaptchaChallenge({ ...challenge, expiresAt: "2000-01-01T00:00:00Z" }, "PASSWORD_LOGIN", undefined, Date.parse("2020-01-01T00:00:00Z")), false);
});

test("slider trail supports discrete keyboard movement with ordered original-image coordinates", () => {
  const trail = buildSliderTrail(146, 52, 420, [
    { x: 14, y: 52, elapsedMs: 120 },
    { x: 32, y: 52, elapsedMs: 90 },
  ]);
  assert.deepEqual(trail, [
    { x: 0, y: 52, elapsedMs: 0 },
    { x: 14, y: 52, elapsedMs: 120 },
    { x: 32, y: 52, elapsedMs: 120 },
    { x: 146, y: 52, elapsedMs: 420 },
  ]);
  assert.equal(trail.length, 4);
  assert.equal(trail.at(-1).x, 146);
  assert.equal(trail.at(-1).elapsedMs, 420);
});

test("slider sampling stays bounded and preserves its original start and final positions", () => {
  let samples = [];
  for (let index = 1; index <= 1000; index += 1) {
    samples = appendSliderSample(samples, { x: index, y: 52, elapsedMs: index * 2 });
  }
  assert.equal(samples.length, 30);
  const trail = buildSliderTrail(1000, 52, 2000, samples);
  assert.ok(trail.length <= 32);
  assert.deepEqual(trail[0], { x: 0, y: 52, elapsedMs: 0 });
  assert.deepEqual(trail.at(-1), { x: 1000, y: 52, elapsedMs: 2000 });
  assert.ok(trail.every((point, index) => index === 0 || point.elapsedMs >= trail[index - 1].elapsedMs));
});
