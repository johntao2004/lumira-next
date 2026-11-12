import test from 'node:test';
import assert from 'node:assert/strict';
import { canSelectFileForBatch, fileBatchOperations, permittedFileBatchOperations, selectBatchFiles, runFileBatch } from '../apps/web/src/platform/files/file-batch.ts';
import { ApiError, clearAuthorizationContext, mutate } from '../apps/web/src/platform/api/client.ts';

const file = (id, state = 'TRASHED', version = 7) => ({ id, state, version, originalName: `${id}.txt` });
const authContext = () => Response.json({ userId: 'fixture-user', authorizationContext: { revision: null, rolePreviewSupported: false, preview: null } });
const csrfHeader = headers => new Headers(headers).get('X-CSRF-TOKEN');

test('selection is restricted to eligible records on the current page and tab', () => {
  const items = [file('ready', 'READY'), { ...file('protected', 'READY'), protectedByReference: true }, file('trash'), file('deleting', 'DELETING')];
  const ids = ['ready', 'protected', 'trash', 'deleting', 'other-page'];
  assert.deepEqual(selectBatchFiles(items, ids, 'files'), [items[0]]);
  assert.deepEqual(selectBatchFiles(items, ids, 'trash'), [items[2]]);
  assert.deepEqual(selectBatchFiles(undefined, ids, 'trash'), []);
  assert.deepEqual(fileBatchOperations('files', 0), []);
  assert.deepEqual(fileBatchOperations('trash', 0), []);
  assert.deepEqual(fileBatchOperations('files', 1), ['trash']);
  assert.deepEqual(fileBatchOperations('trash', 1), ['restore', 'purge']);
});

test('a permitted READY file remains selectable before toolbar operations appear', () => {
  const ready = file('ready', 'READY');
  assert.deepEqual(fileBatchOperations('files', 0), []);
  assert.deepEqual(permittedFileBatchOperations('files', { canDelete: true }), ['trash']);
  assert.equal(canSelectFileForBatch(ready, 'files', { canDelete: true }), true);
});

test('file selection keeps delete permission, state, and reference protections', () => {
  const ready = file('ready', 'READY');
  const referenced = { ...ready, protectedByReference: true };
  assert.equal(canSelectFileForBatch(ready, 'files', { canDelete: false }), false);
  assert.equal(canSelectFileForBatch(referenced, 'files', { canDelete: true }), false);
  assert.equal(canSelectFileForBatch(file('uploading', 'UPLOADING'), 'files', { canDelete: true }), false);
  assert.equal(canSelectFileForBatch(file('trash', 'TRASHED'), 'files', { canDelete: true }), false);
});

test('trash selection requires an eligible row and at least one permitted restore or purge action', () => {
  const trashed = file('trashed', 'TRASHED');
  assert.deepEqual(permittedFileBatchOperations('trash', { canDelete: false, canRestore: true }), ['restore']);
  assert.equal(canSelectFileForBatch(trashed, 'trash', { canDelete: false, canRestore: true }), true);
  assert.deepEqual(permittedFileBatchOperations('trash', { canDelete: true, canRestore: false }), ['purge']);
  assert.equal(canSelectFileForBatch(trashed, 'trash', { canDelete: true, canRestore: false }), true);
  assert.deepEqual(permittedFileBatchOperations('trash', { canDelete: false, canRestore: false }), []);
  assert.equal(canSelectFileForBatch(trashed, 'trash', { canDelete: false, canRestore: false }), false);
  assert.equal(canSelectFileForBatch(file('ready', 'READY'), 'trash', { canDelete: true, canRestore: true }), false);
});

test('batch transport uses per-file versions and fresh CSRF for every operation', async t => {
  clearAuthorizationContext();
  const calls = [];
  let tokens = 0;
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === '/api/auth/me') return authContext();
    if (url === '/api/auth/csrf') {
      tokens += 1;
      return Response.json({ headerName: 'X-CSRF-TOKEN', token: `token-${tokens}` });
    }
    calls.push({ url, method: init.method, token: csrfHeader(init.headers), credentials: init.credentials });
    return new Response(null, { status: 204 });
  });
  for (const operation of ['trash', 'restore', 'purge']) {
    const result = await runFileBatch([file('one', 'TRASHED', 2), file('two', 'TRASHED', 9)], operation, mutate);
    assert.equal(result.accepted.length, 2);
    assert.deepEqual(result.failures, []);
  }
  assert.deepEqual(calls.map(c => [c.url, c.method]), [
    ['/api/account/files/one?version=2', 'DELETE'], ['/api/account/files/two?version=9', 'DELETE'],
    ['/api/account/files/one/restore?version=2', 'POST'], ['/api/account/files/two/restore?version=9', 'POST'],
    ['/api/account/files/one/purge?version=2', 'DELETE'], ['/api/account/files/two/purge?version=9', 'DELETE'],
  ]);
  assert.deepEqual(calls.map(c => c.token), Array.from({ length: 6 }, (_, i) => `token-${i + 1}`));
  assert.ok(calls.every(c => c.credentials === 'same-origin'));
});

test('managed batch transport uses platform paths and retains each file version', async t => {
  clearAuthorizationContext();
  const calls = [];
  let tokens = 0;
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === '/api/auth/me') return authContext();
    if (url === '/api/auth/csrf') {
      tokens += 1;
      return Response.json({ headerName: 'X-CSRF-TOKEN', token: `managed-${tokens}` });
    }
    calls.push({ url, method: init.method, token: csrfHeader(init.headers), credentials: init.credentials });
    return new Response(null, { status: 204 });
  });
  for (const operation of ['trash', 'restore', 'purge']) {
    const files = [file('one', operation === 'trash' ? 'READY' : 'TRASHED', 3), file('two', operation === 'trash' ? 'READY' : 'TRASHED', 11)];
    const result = await runFileBatch(files, operation, mutate, '/platform/files');
    assert.deepEqual(result.accepted, files);
    assert.deepEqual(result.failures, []);
  }
  assert.deepEqual(calls.map(c => [c.url, c.method]), [
    ['/api/platform/files/one?version=3', 'DELETE'], ['/api/platform/files/two?version=11', 'DELETE'],
    ['/api/platform/files/one/restore?version=3', 'POST'], ['/api/platform/files/two/restore?version=11', 'POST'],
    ['/api/platform/files/one/purge?version=3', 'DELETE'], ['/api/platform/files/two/purge?version=11', 'DELETE'],
  ]);
  assert.deepEqual(calls.map(c => c.token), Array.from({ length: 6 }, (_, i) => `managed-${i + 1}`));
  assert.ok(calls.every(c => c.credentials === 'same-origin'));
});

test('managed partial failure preserves errors and continues with later files', async t => {
  clearAuthorizationContext();
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === '/api/auth/me') return authContext();
    if (url === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' });
    calls.push({ url, method: init.method });
    if (url.includes('/forbidden/')) return Response.json({ detail: 'forbidden' }, { status: 403 });
    if (url.includes('/stale/')) return Response.json({ detail: 'fileStateConflict' }, { status: 409 });
    return new Response(null, { status: 204 });
  });
  const files = [file('forbidden', 'TRASHED', 2), file('accepted', 'TRASHED', 6), file('stale', 'TRASHED', 10), file('later', 'TRASHED', 14)];
  const result = await runFileBatch(files, 'purge', mutate, '/platform/files');
  assert.deepEqual(calls, [
    { url: '/api/platform/files/forbidden/purge?version=2', method: 'DELETE' },
    { url: '/api/platform/files/accepted/purge?version=6', method: 'DELETE' },
    { url: '/api/platform/files/stale/purge?version=10', method: 'DELETE' },
    { url: '/api/platform/files/later/purge?version=14', method: 'DELETE' },
  ]);
  assert.deepEqual(result.accepted, [files[1], files[3]]);
  assert.deepEqual(result.failures.map(({ file, error }) => [file.originalName, error.status, error.message]), [
    ['forbidden.txt', 403, 'forbidden'], ['stale.txt', 409, 'fileStateConflict'],
  ]);
  assert.ok(result.failures.every(({ error }) => error instanceof ApiError));
});

test('accepted file refresh runs before the next write and cannot turn a committed write into a failure', async () => {
  const events = [];
  const files = [file('first'), file('rejected'), file('last')];
  const result = await runFileBatch(files, 'purge', async path => {
    const id = path.match(/\/([^/]+)\/purge/)[1];
    events.push(`write:${id}`);
    if (id === 'rejected') throw new Error('write rejected');
  }, '/account/files', async accepted => {
    events.push(`refresh:${accepted.id}`);
    if (accepted.id === 'first') throw new Error('usage refresh failed');
  });

  assert.deepEqual(events, [
    'write:first', 'refresh:first',
    'write:rejected',
    'write:last', 'refresh:last',
  ]);
  assert.deepEqual(result.accepted, [files[0], files[2]]);
  assert.deepEqual(result.failures.map(({ file: failed }) => failed.id), ['rejected']);
  assert.deepEqual(result.refreshFailures.map(({ file: failed }) => failed.id), ['first']);
});

test('purge keeps forbidden and stale failures while accepting other files', async t => {
  clearAuthorizationContext();
  t.mock.method(globalThis, 'fetch', async url => {
    if (url === '/api/auth/me') return authContext();
    if (url === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' });
    if (url.includes('/foreign/')) return Response.json({ detail: 'forbidden' }, { status: 403 });
    if (url.includes('/stale/')) return Response.json({ detail: 'staleRecord' }, { status: 409 });
    return new Response(null, { status: 204 });
  });
  const files = [file('foreign'), file('accepted'), file('stale')];
  const result = await runFileBatch(files, 'purge', mutate);
  assert.deepEqual(result.accepted, [files[1]]);
  assert.deepEqual(result.failures.map(({ file, error }) => [file.originalName, error.status, error.message]), [
    ['foreign.txt', 403, 'forbidden'], ['stale.txt', 409, 'staleRecord'],
  ]);
  assert.ok(result.failures.every(({ error }) => error instanceof ApiError));
});

test('an empty batch never makes a write', async () => {
  const result = await runFileBatch([], 'purge', () => assert.fail('unexpected write'));
  assert.deepEqual(result, { accepted: [], failures: [], refreshFailures: [] });
});

test('an unresponsive write cannot let a retired account continue the batch', async () => {
  const controller = new AbortController();
  const calls = [];
  const refreshes = [];
  let resolveFirstWrite;
  const firstWrite = new Promise(resolve => { resolveFirstWrite = resolve; });
  const batch = runFileBatch(
    [file('first'), file('second')],
    'purge',
    path => { calls.push(path); return firstWrite; },
    '/account/files',
    item => refreshes.push(item.id),
    controller.signal,
  );
  await new Promise(resolve => setImmediate(resolve));
  controller.abort();
  resolveFirstWrite(); // deliberately ignores the signal and resolves late
  await assert.rejects(batch, { name: 'AbortError' });
  assert.equal(calls.length, 1);
  assert.match(calls[0], /\/first\/purge/);
  assert.deepEqual(refreshes, []);
});
