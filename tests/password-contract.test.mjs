import test from 'node:test';
import assert from 'node:assert/strict';
import { passwordValidForRules } from '../apps/web/src/platform/auth/password-contract.ts';

const policy = allowConsecutiveCharacters => ({
  minLength: 8,
  maxBytes: 256,
  requireUppercase: true,
  requireLowercase: true,
  requireSpecialCharacter: true,
  allowConsecutiveCharacters,
});

test('browser validator matches the server policy sequence and repeat examples', () => {
  const rejectedByServerPolicy = [
    'Q!r7abc9', 'Q!r7cba9', 'Q!r71239', 'Q!r73219', 'Q!r7aBc9', 'Q!r7CbA9',
    'Q!r7aa9x', 'Q!r7119x', 'abcQ!r79', 'Q!r79xyz', 'Q!r7１２３9', 'Q!r7αβγ9',
    'Q!r7𐐨𐐩𐐪9',
  ];
  for (const password of rejectedByServerPolicy) {
    assert.equal(passwordValidForRules(password, policy(false)), false, password);
    assert.equal(passwordValidForRules(password, policy(true)), true, password);
  }
});

test('browser validator matches server acceptance of separated, wrapped, and mixed-class values', () => {
  const acceptedByServerPolicy = [
    'Q!r7abx9', 'Q!r7ab9x', 'Q!r7a-b-c9', 'Q!r71-2-39', 'Q!r7zab9',
    'Q!r7aAq9', 'Q!r7!"#9', 'Q!r689:9', 'Q!r7😀😁😂9',
  ];
  for (const password of acceptedByServerPolicy) assert.equal(passwordValidForRules(password, policy(false)), true, password);
});

test('server-provided minimum length, Unicode byte cap, and character requirements are applied', () => {
  const strictRules = { ...policy(true), minLength: 12, maxBytes: 256 };
  assert.equal(passwordValidForRules('shortA!1', strictRules), false);
  assert.equal(passwordValidForRules('Abcdefghijk1!', strictRules), true);
  assert.equal(passwordValidForRules(`${'é'.repeat(127)}A!`, strictRules), true);
  assert.equal(passwordValidForRules(`${'é'.repeat(129)}A!`, strictRules), false);
  assert.equal(passwordValidForRules('abcdefghijk1!', strictRules), false);
  assert.equal(passwordValidForRules('ABCDEFGHIJK1!', strictRules), false);
  assert.equal(passwordValidForRules('Abcdefghijk12', strictRules), false);
});
