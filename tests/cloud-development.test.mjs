import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { spawnSync } from 'node:child_process';

function fixture(t, os = 'Linux') {
  const root = mkdtempSync(join(tmpdir(), 'lumira-cloud-guard-'));
  t.after(() => rmSync(root, {recursive: true, force: true}));
  for (const dir of ['scripts', '.runtime', 'bin', 'jdk/bin']) mkdirSync(join(root, dir), {recursive: true});
  for (const name of ['cloud-env.sh', 'cloud-database.sh', 'cloud-install.sh'])
    copyFileSync(resolve('scripts', name), join(root, 'scripts', name));
  const executable = (name, body) => writeFileSync(join(root, name), `#!/bin/sh\n${body}\n`, {mode: 0o700});
  executable('bin/uname', `printf '%s\\n' '${os}'`);
  executable('jdk/bin/javac', "printf 'javac 25.0.1\\n'");
  for (const command of ['sudo', 'pg_ctlcluster', 'pg_createcluster', 'apt-get'])
    executable(`bin/${command}`, `printf '%s\\n' '${command}' >> '${root}/mutations'; exit 99`);
  const env = {...process.env, PATH: `${root}/bin:${process.env.PATH}`};
  delete env.LUMIRA_CLOUD_DEV;
  writeFileSync(join(root, '.runtime/cloud.env'), `export JAVA_HOME='${root}/jdk'\n`);
  writeFileSync(join(root, '.runtime/cloud-development'), '');
  return {root, env, executable, run: name => spawnSync('bash', [join(root, 'scripts', name)], {cwd: root, env, encoding: 'utf8'})};
}

test('cloud installation refuses a local Mac before installing anything', t => {
  const f = fixture(t, 'Darwin');
  f.env.LUMIRA_CLOUD_DEV = '1';
  const result = f.run('cloud-install.sh');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Codex Ubuntu VM/);
  assert.equal(existsSync(join(f.root, 'mutations')), false);
});

test('Linux installation requires explicit disposable-development opt-in', t => {
  const f = fixture(t);
  assert.equal(f.run('cloud-install.sh').status, 1);
  assert.equal(existsSync(join(f.root, 'mutations')), false);
});

test('cloud database setup rejects a copied remote configuration before database commands', t => {
  const f = fixture(t);
  const config = JSON.stringify({LUMIRA_DB_URL: 'jdbc:postgresql://remote.example:5432/production', LUMIRA_DB_USER: 'production'});
  writeFileSync(join(f.root, '.runtime/local.json'), config);
  const result = f.run('cloud-database.sh');
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /isolated local lumira_next/);
  assert.equal(readFileSync(join(f.root, '.runtime/local.json'), 'utf8'), config);
  assert.equal(existsSync(join(f.root, 'mutations')), false);
});

test('a PostgreSQL cluster on a different port is not changed or replaced', t => {
  const f = fixture(t);
  f.executable('bin/pg_lsclusters', "printf '18 main 5433 online postgres /data /logs\\n'");
  const result = f.run('cloud-database.sh');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /must use port 5432/);
  assert.equal(existsSync(join(f.root, 'mutations')), false);
});
