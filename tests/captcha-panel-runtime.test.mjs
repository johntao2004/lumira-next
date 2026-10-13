import assert from "node:assert/strict";
import test from "node:test";
import { createCaptchaPanelAutoLoader } from "../apps/web/src/platform/auth/captcha-panel-runtime.mjs";
import { createCaptchaAttemptGuard, isLocalCaptchaChallenge } from "../apps/web/src/platform/auth/captcha-runtime.mjs";

const tick = () => new Promise(resolve => setImmediate(resolve));

function enterContext(loader, generation, context, onLoad) {
  generation.invalidate();
  return loader.schedule(context, () => {
    const request = generation.begin();
    onLoad({ request, isCurrent: () => generation.isCurrent(request) });
  });
}

test("mount and StrictMode effect replay create one automatic challenge request", async () => {
  const loader = createCaptchaPanelAutoLoader();
  const generation = createCaptchaAttemptGuard();
  const requests = [];
  const context = { action: "PASSWORD_LOGIN", locale: "zh-cn" };

  const strictSetupCleanup = enterContext(loader, generation, context, request => requests.push(request));
  strictSetupCleanup();
  generation.invalidate(); // The panel's mount cleanup is replayed by StrictMode.
  const mountedCleanup = enterContext(loader, generation, context, request => requests.push(request));

  await tick();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].isCurrent(), true, "the surviving mount owns a live request generation");
  mountedCleanup();
});

test("changing the action retires the old request and automatically loads the new context", async () => {
  const loader = createCaptchaPanelAutoLoader();
  const generation = createCaptchaAttemptGuard();
  const requests = [];
  const oldCleanup = enterContext(loader, generation, { action: "PASSWORD_LOGIN", locale: "en" }, request => requests.push(request));
  await tick();
  assert.equal(requests.length, 1);

  oldCleanup();
  const newCleanup = enterContext(loader, generation, { action: "LOGIN_CODE_SEND", locale: "en" }, request => requests.push(request));
  await tick();
  assert.equal(requests.length, 2);
  assert.equal(requests[0].isCurrent(), false, "a response from the previous action is stale");
  assert.equal(requests[1].isCurrent(), true);
  newCleanup();
});

test("changing locale retires the old request and loads a localized challenge", async () => {
  const loader = createCaptchaPanelAutoLoader();
  const generation = createCaptchaAttemptGuard();
  const requests = [];
  const oldCleanup = enterContext(loader, generation, { action: "PASSWORD_LOGIN", locale: "en" }, request => requests.push(request));
  await tick();

  oldCleanup();
  const newCleanup = enterContext(loader, generation, { action: "PASSWORD_LOGIN", locale: "zh-cn" }, request => requests.push(request));
  await tick();
  assert.equal(requests.length, 2);
  assert.equal(requests[0].isCurrent(), false, "a response from the previous locale is stale");
  assert.equal(requests[1].isCurrent(), true);
  newCleanup();
});

test("cancel before the automatic-load microtask prevents a challenge request", async () => {
  const loader = createCaptchaPanelAutoLoader();
  const requests = [];
  const cleanup = loader.schedule({ action: "PASSWORD_LOGIN", locale: "en" }, () => requests.push("request"));
  loader.cancel();
  cleanup();
  await tick();
  assert.deepEqual(requests, []);
});

test("unmount after a deferred start is flushed suppresses its queued microtask", async () => {
  const loader = createCaptchaPanelAutoLoader();
  let starts = 0;
  const cleanup = loader.schedule({ action: "PASSWORD_LOGIN", locale: "en" }, () => {});
  loader.defer(() => { starts += 1; });
  loader.flush();

  cleanup();
  await tick();
  assert.equal(starts, 0, "effect cleanup invalidates work already queued by flush");
});

test("cancel after a deferred start is flushed suppresses its queued microtask", async () => {
  const loader = createCaptchaPanelAutoLoader();
  let starts = 0;
  loader.schedule({ action: "PASSWORD_LOGIN", locale: "en" }, () => {});
  loader.defer(() => { starts += 1; });
  loader.flush();

  loader.cancel();
  await tick();
  assert.equal(starts, 0);
});

test("cancel invalidates an in-flight challenge response", async () => {
  const loader = createCaptchaPanelAutoLoader();
  const generation = createCaptchaAttemptGuard();
  const applied = [];
  let resolveResponse;
  const cleanup = enterContext(loader, generation, { action: "PASSWORD_LOGIN", locale: "en" }, ({ isCurrent }) => {
    void new Promise(resolve => { resolveResponse = resolve; }).then(value => {
      if (isCurrent()) applied.push(value);
    });
  });
  await tick();

  loader.cancel();
  generation.invalidate();
  cleanup();
  resolveResponse("late challenge");
  await tick();
  assert.deepEqual(applied, [], "a late response cannot restore a canceled challenge");
});

test("a later manual refresh prevents an older response from replacing the current challenge", async () => {
  const generation = createCaptchaAttemptGuard();
  const first = generation.begin();
  const refresh = generation.begin();
  const applied = [];
  if (generation.isCurrent(first)) applied.push("first challenge");
  if (generation.isCurrent(refresh)) applied.push("refreshed challenge");
  assert.deepEqual(applied, ["refreshed challenge"]);
  assert.equal(generation.isCurrent(refresh), true);
});

test("expired challenge response is rejected before the panel can display it", () => {
  const expired = {
    challengeId: "expired",
    action: "PASSWORD_LOGIN",
    attemptId: null,
    expiresAt: "2000-01-01T00:00:00.000Z",
    provider: "LOCAL",
    accessibleAlternativeAvailable: true,
    type: "IMAGE_TEXT",
    imageDataUrl: "data:image/png;base64,aGVsbG8=",
    width: 120,
    height: 48,
  };
  assert.equal(isLocalCaptchaChallenge(expired, "PASSWORD_LOGIN"), false);
});
