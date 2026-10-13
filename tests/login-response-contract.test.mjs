import test from 'node:test';
import assert from 'node:assert/strict';
import { isLoginResponse, loginResponseDisposition } from '../apps/web/src/platform/auth/login-response-contract.mjs';

test('mandatory password-change response is a recognized unauthenticated continuation', () => {
  const response = {
    authenticated: false,
    secondFactorRequired: false,
    challengeId: 'challenge-1',
    expiresAt: '2026-10-11T12:30:00Z',
    state: 'PASSWORD_CHANGE_REQUIRED',
  };

  assert.equal(isLoginResponse(response), true);
  assert.equal(loginResponseDisposition(response), 'password-change');
});

test('server password-change state takes precedence over an inconsistent authenticated flag', () => {
  assert.equal(loginResponseDisposition({
    authenticated: true,
    secondFactorRequired: false,
    challengeId: 'challenge-1',
    state: 'PASSWORD_CHANGE_REQUIRED',
  }), 'password-change');
});

test('an unauthenticated completed or unknown state never enters the workspace', () => {
  assert.equal(loginResponseDisposition({
    authenticated: false,
    secondFactorRequired: false,
    challengeId: null,
    state: 'COMPLETE',
  }), 'unavailable');
  assert.equal(loginResponseDisposition({
    authenticated: false,
    secondFactorRequired: false,
    challengeId: 'challenge-1',
    state: 'UNKNOWN',
  }), 'unavailable');
});

test('only an authenticated complete response is treated as a finished login', () => {
  assert.equal(loginResponseDisposition({
    authenticated: true,
    secondFactorRequired: false,
    challengeId: null,
    state: 'COMPLETE',
  }), 'complete');
});
