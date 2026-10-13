import test from 'node:test';
import assert from 'node:assert/strict';
import { acceptSessionIdentity, currentSessionRevision, currentSessionUserId, invalidateSession } from '../apps/web/src/platform/auth/session-lifecycle.ts';
import { api, clearAuthorizationContext, getAuthorizationContext, sessionMe, setAuthorizationContext } from '../apps/web/src/platform/api/client.ts';

test('late /auth/me responses that ignore AbortSignal cannot replace the current identity or role-preview revision', async t => {
  clearAuthorizationContext();
  invalidateSession();
  acceptSessionIdentity('account-b');
  const currentContext = { revision: 'revision-b', rolePreviewSupported: true, preview: null };
  setAuthorizationContext(currentContext);

  const controller = new AbortController();
  let releaseResponse;
  t.mock.method(globalThis, 'fetch', () => new Promise(resolve => { releaseResponse = resolve; }));
  const request = api('/auth/me', { signal: controller.signal });
  await new Promise(resolve => setImmediate(resolve));
  controller.abort();
  releaseResponse(Response.json({
    userId: 'account-a',
    authorizationContext: { revision: 'revision-a', rolePreviewSupported: true, preview: null },
  }));

  await assert.rejects(request, { name: 'AbortError' });
  assert.equal(currentSessionUserId(), 'account-b');
  assert.deepEqual(getAuthorizationContext(), currentContext);
});

test('an /auth/me query from a retired session epoch cannot accept A after the shared cookie changes to B', async t => {
  clearAuthorizationContext();
  invalidateSession();
  acceptSessionIdentity('account-a');
  const contextA = { revision: 'revision-a', rolePreviewSupported: true, preview: null };
  setAuthorizationContext(contextA);

  let releaseResponse;
  let requestSignal;
  t.mock.method(globalThis, 'fetch', (_url, init) => {
    requestSignal = init.signal;
    return new Promise(resolve => { releaseResponse = resolve; });
  });
  const request = api('/auth/me');
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(requestSignal instanceof AbortSignal, '/auth/me must keep an abortable request signal');
  assert.equal(requestSignal.aborted, false);

  // This models the storage nonce arriving after another tab logs out A and
  // changes the shared cookie to B while the A-authenticated response is held.
  invalidateSession(false);
  acceptSessionIdentity('account-b');
  const contextB = { revision: 'revision-b', rolePreviewSupported: true, preview: null };
  setAuthorizationContext(contextB);
  assert.equal(requestSignal.aborted, false, 'late identity reads are rejected by their captured epoch, not the controller accepting the next identity retires');
  releaseResponse(Response.json({ userId: 'account-a', authorizationContext: contextA }));

  await assert.rejects(request, { name: 'AbortError' });
  assert.equal(currentSessionUserId(), 'account-b');
  assert.deepEqual(getAuthorizationContext(), contextB);
});

test('the /auth/me response establishing B can complete after its own identity transition', async t => {
  clearAuthorizationContext();
  invalidateSession();
  acceptSessionIdentity('account-a');
  invalidateSession(false);

  let requestSignal;
  t.mock.method(globalThis, 'fetch', (_url, init) => {
    requestSignal = init.signal;
    return Promise.resolve(Response.json({
      userId: 'account-b',
      authorizationContext: { revision: 'revision-b', rolePreviewSupported: true, preview: null },
    }));
  });

  const identity = await api('/auth/me');
  assert.equal(identity.userId, 'account-b');
  assert.equal(requestSignal.aborted, false, 'accepting B must not cancel the request that established B');
  assert.equal(currentSessionUserId(), 'account-b');
  assert.deepEqual(getAuthorizationContext(), { revision: 'revision-b', rolePreviewSupported: true, preview: null });
});

test('same-epoch /auth/me callers identify their source and the workspace can refetch after another caller establishes B', async t => {
  clearAuthorizationContext();
  invalidateSession();
  acceptSessionIdentity('account-a');
  const revisionBeforeSwitch = currentSessionRevision();
  invalidateSession(false);

  const pending = [];
  t.mock.method(globalThis, 'fetch', (_url, init) => new Promise(resolve => pending.push({ resolve, signal: init.signal })));
  const bootstrap = sessionMe('authorization-context-bootstrap');
  const workspace = sessionMe('workspace-session');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(pending.length, 2, 'The authorization bootstrap and workspace query should be independently represented in this race fixture');

  const identityB = { userId: 'account-b', authorizationContext: { revision: 'revision-b', rolePreviewSupported: true, preview: null } };
  pending[0].resolve(Response.json(identityB));
  assert.deepEqual(await bootstrap, identityB);
  assert.equal(currentSessionUserId(), 'account-b');
  assert.ok(currentSessionRevision() > revisionBeforeSwitch, 'Accepting B must retire the previous identity epoch');

  pending[1].resolve(Response.json(identityB));
  await assert.rejects(workspace, { name: 'AbortError' }, 'The duplicate workspace response is rejected once the establishing request retires its epoch');
  assert.equal(currentSessionUserId(), 'account-b', 'The duplicate response cannot replace the accepted account');

  // Providers schedules this retry when the identity boundary arrives and the
  // session cache has not yet been populated with B. A fresh-epoch response
  // must then establish usable Workspace data without another identity change.
  const retry = sessionMe('workspace-session');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(pending.length, 3);
  pending[2].resolve(Response.json(identityB));
  assert.deepEqual(await retry, identityB);
  assert.equal(currentSessionUserId(), 'account-b');
});

test('public auth-method policy reads survive logout/session invalidation and remain anonymous', async t => {
  clearAuthorizationContext();
  invalidateSession();
  acceptSessionIdentity('account-a');
  setAuthorizationContext({ revision: 'revision-a', rolePreviewSupported: true, preview: null });

  let releaseResponse;
  let requestSignal;
  let requestCredentials;
  let requestHeaders;
  t.mock.method(globalThis, 'fetch', (_url, init) => {
    requestSignal = init.signal;
    requestCredentials = init.credentials;
    requestHeaders = new Headers(init.headers);
    return new Promise(resolve => { releaseResponse = resolve; });
  });

  const methodsRequest = api('/auth/methods');
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(requestSignal instanceof AbortSignal);
  assert.equal(requestSignal.aborted, false);
  assert.equal(requestCredentials, 'omit', 'the public policy request must not send a session cookie');
  assert.equal(requestHeaders.has('X-Lumira-Authorization-Context-Revision'), false,
    'a stale role-preview revision must not make the public policy endpoint return 409');

  // logout() retires the session-bound controller. The public policy read must
  // still be able to finish so LoginPage can safely rebuild its CAPTCHA gate.
  invalidateSession(false);
  assert.equal(requestSignal.aborted, false, 'session invalidation must not cancel the public policy fetch');
  releaseResponse(Response.json({
    methods: ['PASSWORD'],
    capabilities: { loginCode: false, wechat: false, captcha: true, rolePreview: false },
    captcha: { required: true, type: 'IMAGE_TEXT', actions: ['PASSWORD_LOGIN'], alwaysActions: ['PASSWORD_LOGIN'] },
  }));

  const result = await methodsRequest;
  assert.equal(result.capabilities.captcha, true);
  assert.deepEqual(result.captcha.alwaysActions, ['PASSWORD_LOGIN']);
  clearAuthorizationContext();
  invalidateSession(false);
});
