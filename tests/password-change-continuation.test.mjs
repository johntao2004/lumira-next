import test from 'node:test';
import assert from 'node:assert/strict';
import { resolvePasswordChangeContinuation } from '../apps/web/src/platform/auth/password-change-continuation.mjs';
import { loginResponseDisposition } from '../apps/web/src/platform/auth/login-response-contract.mjs';

const submission = (response, overrides = {}) => resolvePasswordChangeContinuation({
  response,
  challengeId: 'password-change-1',
  activeChallengeId: 'password-change-1',
  submittedSessionRevision: 8,
  currentSessionRevision: 8,
  ...overrides,
});

test('completed password setup continues into the authenticated login result', () => {
  const response = {
    authenticated: true,
    secondFactorRequired: false,
    challengeId: null,
    expiresAt: null,
    state: 'COMPLETE',
    nextPath: '/dashboard/home',
  };

  assert.deepEqual(submission(response), { status: 'continue', response });
});

test('password setup can continue into MFA, CAPTCHA, enrollment, or recovery', () => {
  for (const state of ['MFA_REQUIRED', 'CAPTCHA_REQUIRED', 'ENROLLMENT_REQUIRED', 'RECOVERY_REQUIRED']) {
    const response = {
      authenticated: false,
      secondFactorRequired: true,
      challengeId: `next-${state.toLowerCase()}`,
      expiresAt: '2030-01-01T00:00:00Z',
      state,
    };
    assert.deepEqual(submission(response), { status: 'continue', response }, state);
    assert.equal(loginResponseDisposition(response), state === 'MFA_REQUIRED' ? 'mfa' : state.toLowerCase().replace('_required', ''));
  }
});

test('missing, no-content, unknown, and inconsistent password-change results are rejected', () => {
  const invalidResponses = [
    undefined,
    null,
    { authenticated: true, secondFactorRequired: false, challengeId: null },
    { authenticated: true, secondFactorRequired: false, challengeId: null, state: 'MFA_REQUIRED' },
    { authenticated: false, secondFactorRequired: true, challengeId: 'next', state: 'COMPLETE' },
    { authenticated: false, secondFactorRequired: false, challengeId: 'next', state: 'MFA_REQUIRED' },
    { authenticated: false, secondFactorRequired: true, challengeId: null, state: 'CAPTCHA_REQUIRED' },
    { authenticated: false, secondFactorRequired: true, challengeId: 'next', state: 'UNKNOWN' },
    { authenticated: false, secondFactorRequired: false, challengeId: 'next', state: 'PASSWORD_CHANGE_REQUIRED' },
  ];

  for (const response of invalidResponses) assert.deepEqual(submission(response), { status: 'invalid' });
});

test('a response retired by a session boundary or cancellation is ignored', () => {
  const response = {
    authenticated: true,
    secondFactorRequired: false,
    challengeId: null,
    state: 'COMPLETE',
  };

  assert.deepEqual(submission(response, { currentSessionRevision: 9 }), { status: 'stale' });
  assert.deepEqual(submission(response, { activeChallengeId: 'new-challenge' }), { status: 'stale' });
  assert.deepEqual(submission(response, { activeChallengeId: null }), { status: 'stale' });
});
