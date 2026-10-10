import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const sdkModules = JSON.parse(readFileSync('plugins/sdk/frontend-modules.json', 'utf8'));
const sdkSource = readFileSync('apps/web/src/platform/modules/plugin-sdk.ts', 'utf8');
const runtimeSource = readFileSync('apps/web/src/platform/modules/plugin-runtime.ts', 'utf8');

test('plugin frontend SDK allowlist has one typed host export for every exact module path', () => {
  const imports = new Map(
    [...sdkSource.matchAll(/import\s+\*\s+as\s+(module\d+)\s+from\s+["']([^"']+)["'];/g)]
      .map(([, localName, modulePath]) => [modulePath, localName]),
  );
  const exports = new Map(
    [...sdkSource.matchAll(/^\s*["']([^"']+)["']:\s*(module\d+),?\s*$/gm)]
      .map(([, modulePath, localName]) => [modulePath, localName]),
  );

  assert.equal(new Set(sdkModules).size, sdkModules.length, 'SDK paths must be unique');
  assert.deepEqual([...imports.keys()].sort(), [...sdkModules].sort());
  assert.deepEqual([...exports.keys()].sort(), [...sdkModules].sort());
  for (const modulePath of sdkModules) {
    assert.equal(exports.get(modulePath), imports.get(modulePath), `${modulePath} must export its imported module`);
  }

  for (const modulePath of [
    '@/platform/auth/reauthentication-contract',
    '@/platform/auth/use-reauthentication-ticket',
    '@/platform/auth/passkey',
  ]) {
    assert.ok(sdkModules.includes(modulePath), `${modulePath} must be explicitly allowlisted`);
  }
  assert.doesNotMatch(sdkModules.join('\n'), /[*{}]/, 'SDK entries must stay exact module paths');
  assert.match(sdkSource, /export type PluginSdkModules = typeof pluginSdkModules;/);
  assert.match(runtimeSource, /__lumiraPluginSdk\?: PluginSdkModules;/);
});
