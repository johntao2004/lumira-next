import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';

test('key inventory fails closed, validates complete aggregates and never approves key changes', () => {
  const result = spawnSync('python3', ['tests/key-inventory-cases.py'], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});
