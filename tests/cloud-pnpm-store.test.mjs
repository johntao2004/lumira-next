import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

function read(t, content) {
  const root = mkdtempSync(join(tmpdir(), 'lumira-pnpm-store-'));
  t.after(() => rmSync(root, {recursive: true, force: true}));
  const file = join(root, '.modules.yaml');
  writeFileSync(file, content);
  return spawnSync('python3', ['scripts/cloud-pnpm-store.py', file, '/fallback/store'], {encoding: 'utf8'});
}

for (const [format, content] of [
  ['JSON', JSON.stringify({storeDir: '/workspace/prepared-store/v10'})],
  ['YAML', 'layoutVersion: 5\nstoreDir: /workspace/prepared-store/v10\n'],
]) test(`cloud installation preserves the existing ${format} pnpm store`, t => {
  const result = read(t, content);
  assert.equal(result.status, 0);
  assert.equal(result.stdout.trim(), '/workspace/prepared-store');
});

test('unrecognized existing metadata cannot trigger a silent modules replacement', t => {
  const result = read(t, 'layoutVersion: 5\n');
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /refusing to replace modules/);
});
