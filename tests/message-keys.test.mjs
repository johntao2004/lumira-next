import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../apps/web/src/platform/api/client.ts';
import { messageKeyFor } from '../apps/web/src/platform/api/errors.ts';

test('message error mapping accepts only catalog keys and safe aliases', () => {
  assert.equal(messageKeyFor(new ApiError(409, 'staleRecord'), ['staleRecord']), 'staleRecord');
  assert.equal(messageKeyFor(new ApiError(400, 'unknownServerCode'), ['staleRecord']), 'unavailable');
  assert.equal(
    messageKeyFor(new ApiError(409, 'duplicateRecord'), ['duplicateDictionary'], 'unavailable', { duplicateRecord: 'duplicateDictionary' }),
    'duplicateDictionary',
  );
  assert.equal(messageKeyFor(new ApiError(400, '__proto__'), ['staleRecord'], 'unavailable', { duplicateRecord: 'duplicateDictionary' }), 'unavailable');
});
