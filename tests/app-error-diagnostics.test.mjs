import test from 'node:test';
import assert from 'node:assert/strict';
import { describeAppError, errorComponent } from '../apps/web/src/platform/shell/app-error-diagnostics.ts';
import { ApiError } from '../apps/web/src/platform/api/client.ts';

test('a failing script gets a frontend code and an identifiable resource without URL credentials', () => {
  const error = Object.assign(new Error('Loading chunk common failed. (load : http://user:pass@127.0.0.1:8010/common-async.js?token=secret#fragment)'), {
    name: 'ChunkLoadError',
    request: 'http://user:pass@127.0.0.1:8010/common-async.js?token=secret#fragment',
  });
  const diagnostic = describeAppError(error, '/dashboard/localization?private=value', new Date('2026-10-02T14:00:00Z'));
  assert.equal(diagnostic.code, 'WEB_CHUNK_LOAD_FAILED');
  assert.equal(diagnostic.kind, 'chunk');
  assert.equal(diagnostic.resource, '/common-async.js');
  assert.equal(diagnostic.page, '/dashboard/localization');
  assert.equal(diagnostic.occurredAt, '2026-10-02T14:00:00.000Z');
  assert.match(diagnostic.number, /^ERR-20261002-[A-F0-9]{8}$/);
  assert.doesNotMatch(JSON.stringify(diagnostic), /secret|fragment|user:pass|private=value/);
});

test('runtime, response-content, network and server failures remain distinguishable', () => {
  assert.equal(describeAppError(new TypeError("Cannot read properties of undefined (reading 'items')"), '/dashboard/users').code, 'WEB_PAGE_RENDER_FAILED');
  assert.equal(describeAppError(new SyntaxError("Unexpected token '<'"), '/dashboard/users').code, 'WEB_ASSET_RESPONSE_INVALID');
  assert.equal(describeAppError(new TypeError('Failed to fetch'), '/dashboard/users').code, 'WEB_NETWORK_FAILED');
  assert.equal(describeAppError(new ApiError(403, 'forbidden'), '/dashboard/users').code, 'HTTP_403');
  assert.equal(describeAppError(new ApiError(503, 'unavailable'), '/dashboard/users').code, 'HTTP_503');
  assert.equal(errorComponent('\n    at LocalizationPage (http://127.0.0.1:8010/page.js:10:2)\n    at Suspense'), 'LocalizationPage');
});

test('each occurrence has a new reference while details remain bounded for long or missing errors', () => {
  const a = describeAppError(new Error('A'.repeat(2000)), '/dashboard/localization');
  const b = describeAppError(new Error('A'.repeat(2000)), '/dashboard/localization');
  assert.notEqual(a.number, b.number);
  assert.ok(a.detail.length <= 500);
  assert.equal(describeAppError(null, '/dashboard/localization').detail, '');
});
