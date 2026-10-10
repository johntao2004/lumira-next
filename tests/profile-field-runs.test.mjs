import test from 'node:test';
import assert from 'node:assert/strict';
import { profileFieldRuns } from '../apps/web/src/platform/profile-fields/types.ts';

const field = (key, groupKey, groupLabel = groupKey) => ({
  key,
  label: key,
  type: 'TEXT',
  required: false,
  ...(groupKey ? { groupKey, groupLabel } : {}),
});

test('profile field grouping preserves interleaved placement order', () => {
  const runs = profileFieldRuns([
    field('a1', 'alpha', 'Alpha'),
    field('a2', 'alpha', 'Alpha'),
    field('loose', undefined),
    field('b1', 'beta', 'Beta'),
    field('a3', 'alpha', 'Alpha'),
  ]);

  assert.deepEqual(runs.map(run => run.kind === 'field'
    ? run.field.key
    : run.fields.map(item => item.key)), [
    ['a1', 'a2'],
    'loose',
    ['b1'],
    ['a3'],
  ]);
});
