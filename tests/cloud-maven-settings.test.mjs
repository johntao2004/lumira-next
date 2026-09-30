import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, statSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

function run(t, mirror, proxy) {
  const root = mkdtempSync(join(tmpdir(), 'lumira-maven-settings-'));
  t.after(() => rmSync(root, {recursive: true, force: true}));
  const path = join(root, 'settings.xml');
  const env = {...process.env, LUMIRA_CLOUD_MAVEN_SETTINGS: path, MVNW_REPOURL: mirror};
  for (const key of ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy']) delete env[key];
  if (proxy) env.HTTPS_PROXY = proxy;
  const result = spawnSync('python3', ['scripts/cloud-maven-settings.py'], {env, encoding: 'utf8'});
  return {path, result};
}

test('Maven settings safely encode current proxy credentials and keep them private', t => {
  const f = run(t, 'https://maven-central.storage-download.googleapis.com/maven2', 'http://agent:a%26b%3Cc@proxy.example:8080');
  assert.equal(f.result.status, 0);
  assert.equal(statSync(f.path).mode & 0o777, 0o600);
  const xml = readFileSync(f.path, 'utf8');
  assert.match(xml, /<password>a&amp;b&lt;c<\/password>/);
  assert.match(xml, /<protocol>https<\/protocol>/);
  assert.match(xml, /<nonProxyHosts>localhost\|127\.\*\|\[::1\]<\/nonProxyHosts>/);
  assert.equal(f.result.stdout, '');
  assert.equal(f.result.stderr, '');
});

test('Maven configuration rejects insecure or unapproved repository destinations', t => {
  const f = run(t, 'http://other.example/maven2');
  assert.notEqual(f.result.status, 0);
  assert.equal(existsSync(f.path), false);
});
