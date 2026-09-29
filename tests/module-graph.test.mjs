import test from 'node:test';
import assert from 'node:assert/strict';
import { orderModuleGraph } from '../apps/web/src/platform/modules/graph.ts';

test('orders modules by dependency while preserving independent registration order', () => {
  const ordered = orderModuleGraph([
    { id: 'feature', dependencies: ['core'] },
    { id: 'core', dependencies: [] },
    { id: 'audit', dependencies: ['core'] },
  ]);
  assert.deepEqual(ordered.map((module) => module.id), ['core', 'feature', 'audit']);
});

test('rejects duplicate, missing, and circular module dependencies', () => {
  assert.throws(
    () => orderModuleGraph([{ id: 'core', dependencies: [] }, { id: 'core', dependencies: [] }]),
    /Duplicate module: core/,
  );
  assert.throws(
    () => orderModuleGraph([{ id: 'feature', dependencies: ['missing'] }]),
    /Missing module dependency: missing/,
  );
  assert.throws(
    () => orderModuleGraph([
      { id: 'first', dependencies: ['second'] },
      { id: 'second', dependencies: ['first'] },
    ]),
    /Circular module dependency: first/,
  );
});

test('rejects duplicate edges, self-dependencies, and required-to-optional edges', () => {
  assert.throws(
    () => orderModuleGraph([{ id: 'feature', dependencies: ['core', 'core'] }]),
    /Duplicate module dependency: feature/,
  );
  assert.throws(
    () => orderModuleGraph([{ id: 'core', dependencies: ['core'] }]),
    /Module cannot depend on itself: core/,
  );
  assert.throws(
    () => orderModuleGraph([
      { id: 'optional', dependencies: [], required: false },
      { id: 'core', dependencies: ['optional'], required: true },
    ]),
    /Required module depends on optional module: core/,
  );
});
