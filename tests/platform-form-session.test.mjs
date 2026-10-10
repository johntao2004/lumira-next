import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { guardFormFinish } from '../apps/web/src/platform/components/session-form-finish.ts';
import { acceptSessionIdentity, invalidateSession, sessionSignal } from '../apps/web/src/platform/auth/session-lifecycle.ts';
import { createSessionActionEpoch } from '../apps/web/src/platform/auth/session-action-epoch.mjs';

// Resolve the actual FormStore used by this project's installed Ant Design runtime.
const web = createRequire(new URL('../apps/web/package.json', import.meta.url));
const runtime = createRequire(web.resolve('antd/package.json'));
const { FormStore } = runtime('@rc-component/form/lib/hooks/useForm');
const runtimeVersion = runtime('@rc-component/form/package.json').version;

function delayedForm(onFinish) {
  let resolve;
  const validation = new Promise(done => { resolve = done; });
  const store = new FormStore(() => {});
  store.formHooked = true;
  store.notifyWatch = () => {};
  store.setInitialValues({ private: 'account-a' }, true);
  const unregister = store.registerField({
    getNamePath: () => ['private'],
    props: { rules: [{}] },
    isList: () => false,
    isListField: () => false,
    isPreserve: () => true,
    validateRules: () => validation,
    onStoreChange: () => {},
    getMeta: () => ({}),
  });
  store.setCallbacks({ onFinish });
  return {
    store,
    submit: () => store.submit(),
    destroy: () => { unregister(false, true); store.destroyForm(false); },
    resolve,
  };
}

const settle = () => new Promise(resolve => setImmediate(resolve));

test(`actual @rc-component/form ${runtimeVersion} demonstrates late onFinish after validation and unmount`, async () => {
  const calls = [];
  const form = delayedForm(values => calls.push(values));
  form.submit();
  form.destroy();
  form.resolve();
  await settle();
  assert.deepEqual(calls, [{ private: 'account-a' }]);
});

test('shared guard suppresses the actual FormStore callback after form unmount', async () => {
  const lifetime = new AbortController();
  const calls = [];
  let result;
  const guarded = guardFormFinish(() => lifetime.signal, values => { calls.push(values); });
  const form = delayedForm(values => { result = guarded(values); });
  form.submit();
  lifetime.abort();
  form.destroy();
  form.resolve();
  await settle();
  assert.equal(await result, false);
  assert.deepEqual(calls, []);
});

test('shared guard rejects a form callback when the server-confirmed account changes', async () => {
  invalidateSession();
  acceptSessionIdentity('account-a');
  const origin = sessionSignal();
  const calls = [];
  let result;
  const guarded = guardFormFinish(() => origin, values => { calls.push(values); });
  const form = delayedForm(values => { result = guarded(values); });
  form.submit();
  acceptSessionIdentity('account-b');
  form.resolve();
  await settle();
  assert.equal(await result, false);
  assert.deepEqual(calls, []);
  form.destroy();
});

test('same-account refresh leaves the actual form submission valid', async () => {
  invalidateSession();
  acceptSessionIdentity('account-a');
  const origin = sessionSignal();
  const calls = [];
  let result;
  const guarded = guardFormFinish(() => origin, async values => { calls.push(values); return true; });
  const form = delayedForm(values => { result = guarded(values); });
  form.submit();
  acceptSessionIdentity('account-a');
  form.resolve();
  await settle();
  assert.equal(await result, true);
  assert.deepEqual(calls, [{ private: 'account-a' }]);
  form.destroy();
});

test('anonymous login remount rejects old delayed validation but permits one fresh FormStore submission', async () => {
  invalidateSession();
  acceptSessionIdentity('account-a');
  const retiredSignal = sessionSignal();
  let retiredResult;
  const retiredCalls = [];
  const retiredForm = delayedForm(values => {
    retiredResult = guardFormFinish(() => retiredSignal, value => { retiredCalls.push(value); })(values);
  });

  retiredForm.submit();
  invalidateSession(false);
  const anonymousSignal = sessionSignal();
  assert.equal(anonymousSignal.aborted, false, 'The login page may capture the newly invalidated anonymous epoch');
  let freshResult;
  const freshCalls = [];
  const freshForm = delayedForm(values => {
    freshResult = guardFormFinish(() => anonymousSignal, value => {
      freshCalls.push(value);
      return true;
    })(values);
  });
  assert.notEqual(freshForm.store, retiredForm.store, 'A login boundary must remount a new FormStore rather than rebind the retired one');
  freshForm.submit();

  retiredForm.destroy();
  retiredForm.resolve();
  freshForm.resolve();
  await settle();
  await settle();

  assert.equal(await retiredResult, false, 'The old delayed validation cannot invoke a login continuation after its session epoch is retired');
  assert.deepEqual(retiredCalls, []);
  assert.equal(await freshResult, true, 'A new explicit submission on the fresh anonymous form is accepted');
  assert.deepEqual(freshCalls, [{ private: 'account-a' }]);
  freshForm.destroy();
});

test('anonymous login renewal rejects a passkey continuation after its non-abortable browser prompt resolves', async () => {
  const epoch = createSessionActionEpoch();
  const oldRevision = epoch.capture();
  const calls = [];
  let resolveCredential;
  const credential = new Promise(resolve => { resolveCredential = resolve; });
  const oldPasskeyContinuation = (async () => {
    const result = await credential;
    if (!epoch.isCurrent(oldRevision)) return false;
    calls.push(result);
    return true;
  })();

  epoch.renew();
  resolveCredential({ id: 'credential-from-retired-session' });
  assert.equal(await oldPasskeyContinuation, false);
  assert.deepEqual(calls, [], 'The stale WebAuthn result must not reach the completion API');

  const freshRevision = epoch.capture();
  assert.equal(epoch.isCurrent(freshRevision), true, 'A newly rendered login gesture receives the renewed epoch');
  calls.push('new-login-gesture');
  assert.deepEqual(calls, ['new-login-gesture']);
});

test('unmounting the login page retires callbacks from its current anonymous epoch', () => {
  const epoch = createSessionActionEpoch();
  const mountedRevision = epoch.capture();
  epoch.renew(); // LoginPage effect cleanup.
  assert.equal(epoch.isCurrent(mountedRevision), false);
});
