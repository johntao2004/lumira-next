import test from 'node:test';
import assert from 'node:assert/strict';
import { createFrontendPageRegistry, validateFrontendModule, validateFrontendModuleManifest } from '../apps/web/src/platform/modules/contracts.ts';

function module(id, routeIds, pages = Object.fromEntries(routeIds.map(routeId => [routeId, () => routeId])), tabGroups = []) {
  return {
    id,
    version: '1.0.0',
    required: true,
    dependencies: [],
    routes: routeIds.map((routeId, order) => ({ id: routeId, label: routeId, permission: null, group: 'overview', order })),
    tabGroups,
    pages,
  };
}

test('workspace actions publish immutable snapshots and follow registration ownership', () => {
  const component = () => null;
  const staticModule = { ...module('static', []), workspaceActions: [{ id: 'inbox', component }] };
  const registry = createFrontendPageRegistry([staticModule]);
  const initial = registry.workspaceActions();
  assert.equal(registry.workspaceActions(), initial, 'snapshot identity is stable between mutations');
  assert.ok(Object.isFrozen(initial) && Object.isFrozen(initial[0]));
  let notifications = 0;
  const unsubscribe = registry.subscribeWorkspaceActions(() => { notifications++; });
  const plugin = { ...module('reports', []), workspaceActions: [{ id: 'inbox', component }] };
  const removeOld = registry.registerPlugin(plugin);
  const first = registry.workspaceActions();
  assert.deepEqual(first.map(action => action.moduleId), ['static', 'reports']);
  assert.equal(first[1].component, component);
  removeOld();
  assert.deepEqual(registry.workspaceActions(), initial);
  const removeNew = registry.registerPlugin(plugin);
  const second = registry.workspaceActions();
  assert.notEqual(first[1].key, second[1].key, 'replacement must remount its component');
  removeOld();
  assert.equal(registry.workspaceActions(), second, 'stale cleanup cannot remove replacement actions');
  assert.equal(notifications, 3);
  unsubscribe();
  removeNew();
  assert.equal(notifications, 3);
});

test('invalid workspace controls cannot partially publish pages or actions', () => {
  const registry = createFrontendPageRegistry([]);
  const before = registry.workspaceActions();
  let publications = 0;
  registry.subscribeWorkspaceActions(() => { publications++; });
  for (const workspaceActions of [
    [{ id: 'bad id', component: () => null }],
    [{ id: 'item', component: null }],
    [{ id: 'item', component: () => null }, { id: 'item', component: () => null }],
  ]) {
    assert.throws(() => registry.registerPlugin({ ...module('reports', ['report']), workspaceActions }), /workspace action/);
    assert.equal(registry.owner('report'), null);
    assert.equal(registry.workspaceActions(), before);
  }
  assert.equal(publications, 0);
});

test('caller mutation cannot change registered action identities or cleanup', () => {
  const original = () => 'original';
  const action = { id: 'inbox', component: original };
  const actions = [action];
  const registry = createFrontendPageRegistry([]);
  const remove = registry.registerPlugin({ ...module('reports', []), workspaceActions: actions });
  action.id = 'changed';
  action.component = () => 'changed';
  actions.length = 0;
  assert.equal(registry.workspaceActions()[0].id, 'inbox');
  assert.equal(registry.workspaceActions()[0].component, original);
  remove();
  assert.deepEqual(registry.workspaceActions(), []);
});

test('a failed action observer cannot leak registrations or suppress other observers', t => {
  const log = t.mock.method(console, 'error', () => {});
  const registry = createFrontendPageRegistry([]);
  let updates = 0;
  registry.subscribeWorkspaceActions(() => { throw new Error('broken observer'); });
  registry.subscribeWorkspaceActions(() => { updates++; });
  const remove = registry.registerPlugin({ ...module('reports', []), workspaceActions: [{ id: 'inbox', component: () => null }] });
  assert.equal(updates, 1);
  assert.equal(registry.workspaceActions().length, 1);
  remove();
  assert.equal(updates, 2);
  assert.equal(registry.workspaceActions().length, 0);
  assert.equal(log.mock.callCount(), 2);
});

test('manifest metadata rejects duplicate routes and malformed tab aliases', () => {
  assert.throws(() => validateFrontendModuleManifest(module('sample', ['one', 'one'])), /Duplicate frontend route: one/);
  assert.throws(() => validateFrontendModuleManifest(module('sample', ['one', 'two'], undefined, [
    { id: 'main', routes: ['one', 'one'], anchor: 'one', collapse: 'when-all-visible' },
  ])), /Duplicate frontend tab route: sample:main:one/);
  assert.throws(() => validateFrontendModuleManifest(module('sample', ['one', 'two'], undefined, [
    { id: 'main', routes: ['one', 'two'], anchor: 'missing', collapse: 'when-all-visible' },
  ])), /anchor is not a member/);
  assert.throws(() => validateFrontendModuleManifest(module('sample', ['one', 'two'], undefined, [
    { id: 'main', routes: ['one', 'missing'], anchor: 'one', collapse: 'when-all-visible' },
  ])), /Unknown frontend tab route/);
});

test('renderer maps must exactly cover declared routes', () => {
  assert.throws(() => validateFrontendModule(module('sample', ['one'], {})), /Missing frontend page: sample:one/);
  assert.throws(() => validateFrontendModule(module('sample', ['one'], { one: () => null, stale: () => null })), /Uncatalogued frontend page: sample:stale/);
  assert.throws(() => createFrontendPageRegistry([module('first', ['shared']), module('second', ['shared'])]), /Duplicate frontend route: shared/);
  assert.doesNotThrow(() => validateFrontendModule(module('platform', ['home'], {})), 'the shell owns the Workbench renderer');
});

test('plugin registrations reject duplicate ownership and dispose idempotently', () => {
  const registry = createFrontendPageRegistry([module('static', ['home'], { home: () => 'home' })]);
  const plugin = module('reports', ['reports'], { reports: () => 'report page' });
  const remove = registry.registerPlugin(plugin);
  assert.equal(registry.owner('reports'), 'reports');
  assert.equal(registry.render('reports', {}), 'report page');
  assert.throws(() => registry.registerPlugin(plugin), /Duplicate plugin module: reports/);
  assert.throws(() => registry.registerPlugin(module('other', ['reports'])), /Duplicate plugin route: reports/);
  remove();
  remove();
  assert.equal(registry.owner('reports'), null);
  assert.equal(registry.render('reports', {}), null);
  assert.equal(registry.render('home', {}), 'home');
});

test('an old disposer cannot remove a newer version of the same plugin', () => {
  const registry = createFrontendPageRegistry([]);
  const removeOld = registry.registerPlugin(module('reports', ['reports'], { reports: () => 'old' }));
  removeOld();
  const removeNew = registry.registerPlugin(module('reports', ['reports'], { reports: () => 'new' }));
  removeOld();
  assert.equal(registry.render('reports', {}), 'new');
  assert.equal(registry.owner('reports'), 'reports');
  removeNew();
  assert.equal(registry.render('reports', {}), null);
});

test('a rejected plugin module leaves no partial route ownership', () => {
  const registry = createFrontendPageRegistry([]);
  assert.throws(() => registry.registerPlugin(module('broken', ['first', 'first'])), /Duplicate frontend route: first/);
  assert.equal(registry.owner('first'), null);
  assert.equal(registry.render('first', {}), null);
  assert.throws(() => registry.registerPlugin(module('broken-pages', ['first'], { first: () => null, extra: () => null })), /Uncatalogued frontend page/);
  assert.equal(registry.owner('first'), null);
});

test('unregistered dynamic routes keep the safe null renderer result', () => {
  const registry = createFrontendPageRegistry([]);
  assert.equal(registry.owner('server-owned-page'), null);
  assert.equal(registry.render('server-owned-page', {}), null);
});

test('static page renderers receive the original workspace context', () => {
  let receivedContext;
  const registry = createFrontendPageRegistry([module('static', ['static-page'], {
    'static-page': context => {
      receivedContext = context;
      return null;
    },
  })]);
  const context = permissionContext();
  registry.render('static-page', context);
  assert.equal(receivedContext, context);
});

function permissionContext({ authorities = [], globalAuthorities = [] } = {}) {
  const staticRoutes = new Set(['static-page']);
  return {
    authorities,
    hasGlobal: permission => globalAuthorities.includes(permission),
    hasRouteAccess: routeId => staticRoutes.has(routeId),
    hasRouteOperation: (routeId, actionId) => staticRoutes.has(routeId) && actionId === 'read',
    hasOperation: () => true,
  };
}

function dynamicModule(id, route, renderer = () => null) {
  return {
    id,
    version: '1.0.0',
    required: false,
    dependencies: [],
    routes: [{ label: route.id, group: 'overview', order: 0, ...route }],
    pages: { [route.id]: renderer },
  };
}

test('dynamic page helpers enforce manifest access and operation grants by scope', () => {
  const registry = createFrontendPageRegistry([]);
  const route = {
    id: 'runtime-page',
    permission: 'runtime.page.access',
    accessScope: 'scoped',
    operations: { read: 'runtime.item.read', write: 'runtime.item.write' },
    scopedOperations: ['read'],
  };
  let pageContext;
  registry.registerPlugin(dynamicModule('runtime-plugin', route, context => {
    pageContext = context;
    return 'rendered';
  }));

  const context = permissionContext({
    authorities: ['runtime.page.access', 'runtime.item.read'],
    globalAuthorities: ['runtime.item.write'],
  });
  assert.equal(registry.render('runtime-page', context), 'rendered');
  assert.equal(pageContext.hasRouteAccess('runtime-page'), true);
  assert.equal(pageContext.hasRouteOperation('runtime-page', 'read'), true);
  assert.equal(pageContext.hasOperation('read'), true);
  assert.equal(pageContext.hasRouteOperation('runtime-page', 'write'), true);
  assert.equal(pageContext.hasOperation('write'), true);
  assert.equal(pageContext.hasOperation('undeclared'), false);
  assert.equal(pageContext.hasRouteOperation('runtime-page', 'undeclared'), false);
  assert.equal(pageContext.hasRouteAccess('unknown-page'), false);
  assert.equal(pageContext.hasRouteOperation('unknown-page', 'read'), false);
  assert.equal(pageContext.hasRouteAccess('static-page'), true, 'non-plugin routes use the supplied static helper');
  assert.equal(pageContext.hasRouteOperation('static-page', 'read'), true);
  assert.equal(pageContext.hasRouteOperation('static-page', 'write'), false);

  const missingPageGrant = permissionContext({
    authorities: ['runtime.item.read'],
    globalAuthorities: ['runtime.item.write'],
  });
  registry.render('runtime-page', missingPageGrant);
  assert.equal(pageContext.hasRouteAccess('runtime-page'), false);
  assert.equal(pageContext.hasOperation('read'), false, 'an operation cannot bypass page access');
  assert.equal(pageContext.hasOperation('write'), false);

  const missingActionGrant = permissionContext({ authorities: ['runtime.page.access'] });
  registry.render('runtime-page', missingActionGrant);
  assert.equal(pageContext.hasRouteAccess('runtime-page'), true);
  assert.equal(pageContext.hasOperation('read'), false, 'the scoped operation comes from caller authorities');
  assert.equal(pageContext.hasOperation('write'), false, 'the global operation comes from global authorities');
});

test('dynamic global route access and global operations use only global grants', () => {
  const registry = createFrontendPageRegistry([]);
  let pageContext;
  const route = {
    id: 'global-runtime-page',
    permission: 'runtime.global.access',
    global: true,
    operations: { read: 'runtime.global.read' },
  };
  registry.registerPlugin(dynamicModule('global-runtime-plugin', route, context => {
    pageContext = context;
    return null;
  }));

  registry.render('global-runtime-page', permissionContext({
    authorities: ['runtime.global.access', 'runtime.global.read'],
  }));
  assert.equal(pageContext.hasRouteAccess('global-runtime-page'), false, 'scoped authorities do not grant a global page');
  assert.equal(pageContext.hasOperation('read'), false);

  registry.render('global-runtime-page', permissionContext({
    globalAuthorities: ['runtime.global.access', 'runtime.global.read'],
  }));
  assert.equal(pageContext.hasRouteAccess('global-runtime-page'), true);
  assert.equal(pageContext.hasOperation('read'), true);
});

test('disposed and superseded dynamic page contexts cannot retain or inherit grants', () => {
  const registry = createFrontendPageRegistry([]);
  let oldContext;
  const oldRoute = {
    id: 'replaceable-page',
    permission: null,
    operations: { read: 'runtime.old.read' },
  };
  const removeOld = registry.registerPlugin(dynamicModule('replaceable-plugin', oldRoute, context => {
    oldContext = context;
    return null;
  }));
  const grants = permissionContext({ globalAuthorities: ['runtime.old.read', 'runtime.new.delete'] });
  registry.render('replaceable-page', grants);
  assert.equal(oldContext.hasOperation('read'), true);

  removeOld();
  assert.equal(oldContext.hasOperation('read'), false, 'a disposed page loses its dynamic operation context');
  assert.equal(oldContext.hasRouteAccess('replaceable-page'), false);
  assert.equal(oldContext.hasRouteOperation('replaceable-page', 'read'), false);

  let newContext;
  const newRoute = {
    id: 'replaceable-page',
    permission: null,
    operations: { delete: 'runtime.new.delete' },
  };
  const removeNew = registry.registerPlugin(dynamicModule('replaceable-plugin', newRoute, context => {
    newContext = context;
    return null;
  }));
  registry.render('replaceable-page', grants);
  assert.equal(oldContext.hasOperation('delete'), false, 'an old context cannot borrow the newer manifest');
  assert.equal(oldContext.hasRouteOperation('replaceable-page', 'delete'), false);
  assert.equal(newContext.hasOperation('delete'), true);

  removeOld();
  assert.equal(registry.owner('replaceable-page'), 'replaceable-plugin');
  registry.render('replaceable-page', grants);
  assert.equal(newContext.hasOperation('delete'), true, 'an old disposer cannot remove the newer registration');

  removeNew();
  assert.equal(newContext.hasOperation('delete'), false);
  assert.equal(newContext.hasRouteAccess('replaceable-page'), false);
});

test('live plugin metadata replaces stale host metadata and disappears on unload', () => {
 const registry=createFrontendPageRegistry([]);
 const current=dynamicModule('optional',{id:'optional',permission:'new:read',accessScope:'global'});
 const remove=registry.registerPlugin(current);
 assert.equal(registry.pluginRoute('optional').permission,'new:read');
 remove();assert.equal(registry.pluginRoute('optional'),null);
});
