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
