import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../apps/web/src/platform/api/client.ts';
import { messageKeyFor } from '../apps/web/src/platform/api/errors.ts';
import { languages, loadLocaleMessages, messages } from '../apps/web/src/platform/i18n/messages.ts';

test('global content-policy rejection has the same message in every feature', () => {
  assert.equal(messageKeyFor(new ApiError(400, 'sensitiveContent'), ['staleRecord']), 'sensitiveContent');
});

test('message error mapping accepts only catalog keys and safe aliases', () => {
  assert.equal(messageKeyFor(new ApiError(409, 'staleRecord'), ['staleRecord']), 'staleRecord');
  assert.equal(messageKeyFor(new ApiError(400, 'unknownServerCode'), ['staleRecord']), 'unavailable');
  assert.equal(
    messageKeyFor(new ApiError(409, 'duplicateRecord'), ['duplicateDictionary'], 'unavailable', { duplicateRecord: 'duplicateDictionary' }),
    'duplicateDictionary',
  );
  assert.equal(messageKeyFor(new ApiError(400, '__proto__'), ['staleRecord'], 'unavailable', { duplicateRecord: 'duplicateDictionary' }), 'unavailable');
});

test('password hash capacity errors map to a retryable localized message', () => {
  const error = new ApiError(429, 'passwordHashCapacity', [], 1);
  assert.equal(messageKeyFor(error, []), 'passwordHashCapacity');
  assert.equal(error.retryAfterSeconds, 1);
});

test('pending file cleanup usage has count and size wording in every locale', async () => {
  assert.equal(languages.length, 8);
  for (const { value: locale } of languages) {
    const catalog = await loadLocaleMessages(locale);
    assert.match(catalog.fileUsagePendingDeletion, /\{count\}/, `${locale} count placeholder`);
    assert.match(catalog.fileUsagePendingDeletion, /\{size\}/, `${locale} size placeholder`);
  }
});
