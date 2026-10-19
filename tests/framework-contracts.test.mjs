import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';

// 固定构建边界，防止独立 SDK 再次隐式依赖宿主实现。
// Keep the build boundary explicit so independent SDKs cannot quietly depend on host implementations.
test('framework artifacts and plugin compiler have no host implementation dependency', () => {
  for (const name of ['platform-contracts', 'plugin-sdk', 'payment-sdk', 'plugin-build']) {
    const pom = readFileSync(`framework/${name}/pom.xml`, 'utf8');
    assert.doesNotMatch(pom, /platform-server|backend\/target|systemPath|build-helper/);
    assert.match(pom, /0\.1\.0-alpha\.1/);
  }
  const build = readFileSync('scripts/build-plugins.mjs', 'utf8');
  assert.match(build, /framework\/plugin-build\/target\/plugin-classpath\.txt/);
  assert.doesNotMatch(build, /\$\{root\}\/backend\/target\/classes/);
});

test('public artifacts exclude known host implementations', () => {
  const walk = dir => readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
  const sources = ['platform-contracts', 'plugin-sdk', 'payment-sdk'].flatMap(name => walk(`framework/${name}/src/main/java`));
  assert.ok(sources.length >= 50);
  for (const file of sources) {
    assert.doesNotMatch(path.basename(file), /^(SecretKeys|SecretCipher|ModuleRegistry|ModuleStateStore|AdministrationGuard|AccountSessionRepository)\.java$/);
    assert.doesNotMatch(readFileSync(file, 'utf8'), /import com\.lumira\.platform\.(?:identity\.(?!api)|moduleadmin|settings|users)\./);
  }
});

test('independent application does not use repository-relative parent or system dependencies', () => {
  const pom = readFileSync('examples/independent-notes/pom.xml', 'utf8');
  assert.match(pom, /<relativePath\/>/);
  assert.match(pom, /platform-contracts/);
  assert.doesNotMatch(pom, /platform-server|systemPath|build-helper|backend\/target/);
});
