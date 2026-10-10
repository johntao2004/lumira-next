import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { buildPluginRuntimeLock, writePluginRuntimeBundle, writePluginRuntimeLock } from '../scripts/plugin-runtime-lock.mjs';

test('plugin runtime lock records the deterministic package SHA for each manifest version', () => {
  const temp = mkdtempSync(path.join(os.tmpdir(), 'lumira-plugin-lock-'));
  try {
    const plugins = path.join(temp, 'plugins');
    for (const [id, version, bytes] of [
      ['zeta', '2.1.0', Buffer.from('zeta archive')],
      ['alpha', '1.0.3', Buffer.from('alpha archive')],
    ]) {
      const directory = path.join(plugins, id);
      mkdirSync(path.join(directory, 'dist'), { recursive: true });
      writeFileSync(path.join(directory, 'plugin.json'), JSON.stringify({ id, version }));
      writeFileSync(path.join(directory, 'dist', `${id}-${version}.lumira-plugin.zip`), bytes);
    }

    const lock = buildPluginRuntimeLock(plugins);
    assert.deepEqual(lock.plugins.map(plugin => plugin.id), ['alpha', 'zeta']);
    assert.equal(lock.plugins[0].digest, createHash('sha256').update('alpha archive').digest('hex'));
    const output = path.join(temp, 'out', 'approved-graph.json');
    assert.deepEqual(writePluginRuntimeLock(plugins, output), lock);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});

test('plugin runtime lock refuses a descriptor without its exact versioned archive', () => {
  const temp = mkdtempSync(path.join(os.tmpdir(), 'lumira-plugin-lock-'));
  try {
    const directory = path.join(temp, 'plugins', 'missing');
    mkdirSync(directory, { recursive: true });
    writeFileSync(path.join(directory, 'plugin.json'), JSON.stringify({ id: 'missing', version: '1.0.0' }));
    assert.throws(() => buildPluginRuntimeLock(path.join(temp, 'plugins')), /Missing built plugin archive/);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});

test('fixed runtime bundle contains only the locked descriptor and exact versioned package', () => {
  const temp = mkdtempSync(path.join(os.tmpdir(), 'lumira-plugin-lock-'));
  try {
    const plugins = path.join(temp, 'plugins');
    const source = path.join(plugins, 'alpha');
    mkdirSync(path.join(source, 'dist'), { recursive: true });
    writeFileSync(path.join(source, 'plugin.json'), JSON.stringify({ id: 'alpha', version: '1.2.3' }));
    writeFileSync(path.join(source, 'dist', 'alpha-1.2.3.lumira-plugin.zip'), 'approved bytes');
    writeFileSync(path.join(source, 'dist', 'alpha-old.lumira-plugin.zip'), 'excluded bytes');

    const output = path.join(temp, 'bundle');
    const lock = writePluginRuntimeBundle(plugins, output);
    assert.equal(lock.plugins.length, 1);
    assert.deepEqual(JSON.parse(readFileSync(path.join(output, 'approved-graph.json'), 'utf8')), lock);
    assert.equal(readFileSync(path.join(output, 'alpha', 'plugin.json'), 'utf8'), readFileSync(path.join(source, 'plugin.json'), 'utf8'));
    assert.equal(readFileSync(path.join(output, 'alpha', 'dist', 'alpha-1.2.3.lumira-plugin.zip'), 'utf8'), 'approved bytes');
    assert.equal(existsSync(path.join(output, 'alpha', 'dist', 'alpha-old.lumira-plugin.zip')), false);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
