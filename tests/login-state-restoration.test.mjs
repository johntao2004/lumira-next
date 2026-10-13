import test from 'node:test';
import assert from 'node:assert/strict';
import { createSessionActionEpoch } from '../apps/web/src/platform/auth/session-action-epoch.mjs';
import { restorePendingLogin } from '../apps/web/src/platform/auth/login-state-restoration.mjs';
import { invalidateSession, subscribeSessionBoundary } from '../apps/web/src/platform/auth/session-lifecycle.ts';

const pendingPasswordChange = {
  authenticated: false,
  secondFactorRequired: false,
  challengeId: 'password-change-challenge',
  expiresAt: '2030-01-01T00:00:00Z',
  state: 'PASSWORD_CHANGE_REQUIRED',
};

test('LoginPage restoration accepts PASSWORD_CHANGE_REQUIRED after /auth/me 401 renews the epoch', async () => {
  invalidateSession(false);
  const epoch = createSessionActionEpoch();
  const unsubscribe = subscribeSessionBoundary(() => epoch.renew());
  const retiredRevision = epoch.capture();
  const sessionMe = async () => {
    invalidateSession(true);
    throw Object.assign(new Error('unauthorized'), { status: 401 });
  };
  await assert.rejects(sessionMe(), error => error.status === 401);
  assert.equal(epoch.isCurrent(retiredRevision), false);

  const accepted = [];
  await restorePendingLogin({
    epoch,
    signal: new AbortController().signal,
    loadLoginState: async () => pendingPasswordChange,
    loadSecondFactorPending: async () => { throw new Error('unexpected fallback'); },
    onState: (state, revision) => {
      if (epoch.isCurrent(revision)) accepted.push(state);
    },
  });

  unsubscribe();
  assert.deepEqual(accepted, [pendingPasswordChange]);
});

test('second-factor pending fallback uses the epoch renewed by its own 401', async () => {
  invalidateSession(false);
  const epoch = createSessionActionEpoch();
  const unsubscribe = subscribeSessionBoundary(() => epoch.renew());
  const accepted = [];

  await restorePendingLogin({
    epoch,
    signal: new AbortController().signal,
    loadLoginState: async () => {
      invalidateSession(true);
      throw Object.assign(new Error('unauthorized'), { status: 401 });
    },
    loadSecondFactorPending: async () => ({
      authenticated: false,
      secondFactorRequired: true,
      challengeId: 'mfa-challenge',
      expiresAt: '2030-01-01T00:00:00Z',
      state: 'MFA_REQUIRED',
    }),
    onState: (state, revision) => {
      if (epoch.isCurrent(revision)) accepted.push(state);
    },
  });

  unsubscribe();
  assert.equal(accepted.length, 1);
  assert.equal(accepted[0].state, 'MFA_REQUIRED');
});

test('pending fallback ignores a login-state result retired by another session boundary', async () => {
  invalidateSession(false);
  const epoch = createSessionActionEpoch();
  const unsubscribe = subscribeSessionBoundary(() => epoch.renew());
  let resolveLoginState;
  let pendingCalls = 0;
  const accepted = [];
  const restoration = restorePendingLogin({
    epoch,
    signal: new AbortController().signal,
    loadLoginState: () => new Promise(resolve => { resolveLoginState = resolve; }),
    loadSecondFactorPending: async () => { pendingCalls += 1; return { secondFactorRequired: true }; },
    onState: state => accepted.push(state),
  });

  invalidateSession(false);
  resolveLoginState(pendingPasswordChange);
  await restoration;

  unsubscribe();
  assert.deepEqual(accepted, []);
  assert.equal(pendingCalls, 0);
});
