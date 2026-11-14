import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
import { createFrontendPageRegistry } from '../apps/web/src/platform/modules/contracts.ts';

function runtime() {
  const source = stripTypeScriptTypes(readFileSync(new URL('../apps/web/src/platform/modules/plugin-runtime.ts', import.meta.url), 'utf8')
    .replace(/^import .*;\r?\n/gm, '').replace(/export /g, ''));
  let data = [], effects = [], cleanup, count = 0, revision = 'one', renders = 0, invalidations = 0, events = 0, queryOptions;
  const registry = createFrontendPageRegistry([]);
  const timers = new Set();
  const client = { invalidateQueries: () => { invalidations++; return Promise.resolve(); }, removeQueries() {} };
  const document = { currentScript: null, createElement: () => ({ remove() {} }), head: { appendChild() {} } };
  const context = vm.createContext({ console: { error() {} }, URL, Event, document,
    setTimeout: fn => { timers.add(fn); return fn; }, clearTimeout: fn => timers.delete(fn),
    useState: () => [0, () => { renders++; }], useEffect: fn => effects.push(fn), useQueryClient: () => client,
    useQuery: options => { queryOptions = options; return { data, refetch() {} }; }, api() {}, getAuthorizationContext: () => ({ revision }),
    pluginSdkModules: {}, accountAvatarKey: ['avatar'], registerPluginPages: module => {
      const remove = registry.registerPlugin(module);
      count++;
      return () => { remove(); count--; };
    },
    window: { location: { origin: 'http://localhost' }, addEventListener() {}, removeEventListener() {}, dispatchEvent() { events++; } },
  });
  vm.runInContext(source + '\nglobalThis.run=usePluginRuntime;globalThis.requests=pending;', context);
  return {
    update(next) { cleanup?.(); effects = []; data = next; context.run(); cleanup = effects[0](); },
    script(id = 'probe') { return context.requests.get(id)?.script; },
    register(script, id = 'probe') { document.currentScript = script; context.window.__lumiraRegisterPlugin(id, { entry: {
      id, version: '1', required: false, dependencies: [], routes: [], pages: {},
      workspaceActions: [{ id: `inbox-${id}`, component: () => null }],
    } }); document.currentScript = null; },
    expire() { for (const fn of [...timers]) fn(); },
    observe() { return effects.at(-1)(); },
    switchAccount() { revision = 'two'; },
    count: () => count, pending: () => context.requests.size, timers: () => timers.size,
    renders: () => renders, invalidations: () => invalidations, events: () => events,
    queryOptions: () => queryOptions,
    actions: registry.workspaceActions,
  };
}
const plugin = (digest, id = 'probe', extra = {}) => ({ id, version: '1', digest, manifest: { routes: [], files: {} }, ...extra });

test('uninstall cancels a pending script and rejects late registration', async () => {
  const r = runtime(); r.update([plugin('old')]); const old = r.script();
  r.update([]); r.register(old); await Promise.resolve();
  assert.equal(r.count(), 0); assert.equal(r.pending(), 0); assert.equal(r.timers(), 0);
});
test('same-version replacement cannot be fulfilled by the old script', async () => {
  const r = runtime(); r.update([plugin('old')]); const old = r.script();
  r.update([plugin('new')]); const current = r.script(); r.register(old);
  assert.equal(r.count(), 0); assert.equal(r.pending(), 1);
  r.register(current); await Promise.resolve(); assert.equal(r.count(), 1);
  r.update([]); assert.equal(r.count(), 0);
});
test('account changes and load deadlines do not leave pending registrations', async () => {
  const r = runtime(); r.update([plugin('one')]); r.switchAccount(); r.register(r.script());
  assert.equal(r.count(), 0); assert.equal(r.pending(), 0);
  r.update([plugin('one')]); r.expire(); await Promise.resolve();
  assert.equal(r.pending(), 0); assert.equal(r.count(), 0);
});
test('one observer unmounting does not cancel the shared runtime, the last one cleans up', async () => {
  const r = runtime(); r.update([plugin('one')]);
  const closeShell = r.observe(), closePage = r.observe();
  const script = r.script(); closePage(); assert.equal(r.pending(), 1);
  r.register(script); await Promise.resolve(); assert.equal(r.count(), 1);
  closeShell(); assert.equal(r.count(), 0); assert.equal(r.pending(), 0);
});

test('account invalidation clears loaded and pending resources before new query data arrives', async () => {
  const loaded = runtime(); loaded.update([plugin('one')]); loaded.register(loaded.script());
  await Promise.resolve(); assert.equal(loaded.count(), 1); assert.equal(loaded.actions().length, 1);
  loaded.switchAccount(); loaded.update(undefined); assert.equal(loaded.count(), 0);
  assert.equal(loaded.actions().length, 0, 'account invalidation also removes plugin header controls');
  const pending = runtime(); pending.update([plugin('one')]); const old = pending.script();
  pending.switchAccount(); pending.update(undefined);
  assert.equal(pending.pending(), 0); assert.equal(pending.timers(), 0);
  pending.register(old); assert.equal(pending.count(), 0);
  assert.equal(pending.actions().length, 0);
});

// Registration lifetime must distinguish replacement even if the plugin has no header actions.
test('embedded route lifetime changes on replacement and disappears on unload', () => {
  const registry = createFrontendPageRegistry([]);
  const module = { id: 'embedded', version: '1.0.0', required: false, dependencies: [], routes: [{ id: 'embedded-page', label: 'Embedded', permission: null, group: 'overview', order: 1 }], pages: { 'embedded-page': () => null } };
  const removeFirst = registry.registerPlugin(module);
  const first = registry.registrationKey('embedded-page');
  assert.ok(first);
  removeFirst();
  assert.equal(registry.registrationKey('embedded-page'), null);
  const removeSecond = registry.registerPlugin(module);
  assert.notEqual(registry.registrationKey('embedded-page'), first);
  removeFirst();
  assert.ok(registry.registrationKey('embedded-page'));
  removeSecond();
  assert.equal(registry.registrationKey('embedded-page'), null);
});

test('page-only registration preserves the header snapshot while notifying embedded page lifetimes', () => {
  const registry = createFrontendPageRegistry([]);
  const removeControl = registry.registerPlugin({ id: 'controls', version: '1.0.0', required: false, dependencies: [], routes: [], pages: {}, workspaceActions: [{ id: 'inbox', component: () => null }] });
  const initial = registry.workspaceActions();
  assert.equal(initial.length, 1);
  const observed = [];
  const unsubscribe = registry.subscribeWorkspaceActions(() => observed.push(registry.registrationKey('embedded-page')));
  const remove = registry.registerPlugin({ id: 'embedded', version: '1.0.0', required: false, dependencies: [], routes: [{ id: 'embedded-page', label: 'Embedded', permission: null, group: 'overview', order: 1 }], pages: { 'embedded-page': () => null } });
  assert.equal(registry.workspaceActions(), initial);
  assert.ok(observed[0]);
  remove();
  assert.equal(registry.workspaceActions(), initial);
  assert.equal(observed[1], null);
  unsubscribe();
  removeControl();
  assert.equal(registry.workspaceActions().length, 0);
});

test('an embedded editor preserves its completion callback and derives grants from the current registration', () => {
  const registry = createFrontendPageRegistry([]);
  const onSaved = () => {};
  const access = 'platform:smtp:access', update = 'platform:smtp:update';
  const globals = new Set([access, update]);
  const context = { authorities: [access, update], hasGlobal: permission => globals.has(permission), hasRouteAccess: () => true, hasRouteOperation: () => true, hasOperation: () => true, embedded: { onSaved } };
  const module = { id: 'delivery-settings', version: '1.0.0', required: false, dependencies: [], routes: [{ id: 'smtp', label: 'smtpSettings', permission: access, accessScope: 'global', operations: { update }, group: 'communication', order: 20 }], pages: { smtp: context => context } };
  const remove = registry.registerPlugin(module);
  const old = registry.render('smtp', context);
  assert.equal(old.hasOperation('update'), true);
  assert.equal(old.embedded.onSaved, onSaved);
  globals.delete(update);
  assert.equal(old.hasOperation('update'), false);
  globals.add(update);
  remove();
  registry.registerPlugin(module);
  assert.equal(old.hasOperation('update'), false);
  const current = registry.render('smtp', context);
  assert.equal(current.hasOperation('update'), true);
  assert.equal(current.embedded.onSaved, onSaved);
  globals.delete(access);
  assert.equal(current.hasOperation('update'), false);
});

test('concurrent plugin loads coalesce query invalidations and disable fixed-graph background polling', async () => {
  const r = runtime();
  r.update([
    plugin('d1', 'alpha', { lifecycleChangesAllowed: false, lifecycleBlockReason: 'pluginFixedGraph' }),
    plugin('d2', 'beta', { lifecycleChangesAllowed: false, lifecycleBlockReason: 'pluginFixedGraph' }),
    plugin('d3', 'gamma', { lifecycleChangesAllowed: false, lifecycleBlockReason: 'pluginFixedGraph' }),
  ]);
  assert.equal(r.queryOptions().refetchIntervalInBackground, false);
  assert.equal(r.queryOptions().refetchInterval({ state: { data: [plugin('d1', 'alpha', { lifecycleChangesAllowed: false, lifecycleBlockReason: 'pluginFixedGraph' })] } }), false);
  assert.equal(r.queryOptions().refetchInterval({ state: { data: [plugin('d1', 'alpha', { lifecycleChangesAllowed: true, lifecycleBlockReason: null })] } }), 2500);
  r.register(r.script('alpha'), 'alpha');
  r.register(r.script('beta'), 'beta');
  r.register(r.script('gamma'), 'gamma');
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(r.count(), 3);
  assert.equal(r.invalidations(), 4);
  assert.equal(r.events(), 1);
  assert.equal(r.renders(), 1);
});
