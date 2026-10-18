import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, existsSync, rmSync, chmodSync, chownSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { spawnSync } from 'node:child_process';

let unprivilegedNodeDirectory;
function nodeDirectoryForFixture() {
  if (!unprivilegedNodeDirectory) {
    // A root-only local runtime cannot be reached after dropping to nobody.
    // Copy the current executable once into a traversable, disposable fixture.
    unprivilegedNodeDirectory = mkdtempSync(join(tmpdir(), 'lumira-cloud-test-node-'));
    chmodSync(unprivilegedNodeDirectory, 0o755);
    const executable = join(unprivilegedNodeDirectory, 'node');
    copyFileSync(process.execPath, executable);
    chmodSync(executable, 0o755);
  }
  return unprivilegedNodeDirectory;
}
after(() => {
  if (unprivilegedNodeDirectory) rmSync(unprivilegedNodeDirectory, {recursive: true, force: true});
});

function fixture(t, os = 'Linux') {
  const root = mkdtempSync(join(tmpdir(), 'lumira-cloud-guard-'));
  // WSL and container test runners may execute as root. These cases exercise
  // unprivileged cloud startup, so run their shell fixtures as nobody there.
  const runUnprivileged = process.platform === 'linux' && process.getuid?.() === 0;
  if (runUnprivileged) {
    chmodSync(root, 0o755);
    chownSync(root, 65534, 65534);
  }
  t.after(() => rmSync(root, {recursive: true, force: true}));
  for (const dir of ['scripts', '.runtime', 'bin', 'jdk/bin']) mkdirSync(join(root, dir), {recursive: true});
  for (const name of ['cloud-env.sh', 'cloud-database.sh', 'cloud-database-user.sh', 'cloud-install.sh']) {
    const target = join(root, 'scripts', name);
    copyFileSync(resolve('scripts', name), target);
    // Source files on a Windows mount may be owner-only; the nobody fixture must read them.
    if (runUnprivileged) chmodSync(target, 0o644);
  }
  const executable = (name, body) => writeFileSync(join(root, name), `#!/bin/sh\n${body}\n`, {mode: runUnprivileged ? 0o755 : 0o700});
  executable('bin/uname', `printf '%s\\n' '${os}'`);
  executable('jdk/bin/javac', "printf 'javac 25.0.1\\n'");
  for (const command of ['sudo', 'pg_ctlcluster', 'pg_createcluster', 'apt-get'])
    executable(`bin/${command}`, `printf '%s\\n' '${command}' >> '${root}/mutations'; exit 99`);
  const nodePath = runUnprivileged ? `${nodeDirectoryForFixture()}:` : '';
  const env = {...process.env, PATH: `${root}/bin:${nodePath}${process.env.PATH}`};
  // Test fixtures must not inherit the real VM's active database mode/client.
  for (const key of Object.keys(env)) {
    if (key.startsWith('LUMIRA_') || /^(DATABASE_URL|DB_|PGHOST$|PGPORT$|PGUSER$|PGPASSWORD$|PGDATABASE$|PSQL_BIN$)/.test(key)) delete env[key];
  }
  env.HOME = root;
  writeFileSync(join(root, '.runtime/cloud.env'), `export JAVA_HOME='${root}/jdk'\n`);
  writeFileSync(join(root, '.runtime/cloud-development'), '');
  const run = name => {
    const script = join(root, 'scripts', name);
    const options = {cwd: root, env, encoding: 'utf8'};
    if (runUnprivileged) {
      options.uid = 65534;
      options.gid = 65534;
    }
    return spawnSync('bash', [script], options);
  };
  return {root, env, executable, run};
}

test('cloud installation refuses a local Mac before installing anything', t => {
  const f = fixture(t, 'Darwin');
  f.env.LUMIRA_CLOUD_DEV = '1';
  const result = f.run('cloud-install.sh');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Codex Linux VM/);
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

function userDatabaseFixture(t, version = '18') {
  const f = fixture(t);
  mkdirSync(join(f.root, 'pg/bin'), {recursive: true});
  mkdirSync(join(f.root, '.runtime/cloud-postgres/data'), {recursive: true});
  if (process.platform === 'linux' && process.getuid?.() === 0)
    chownSync(join(f.root, '.runtime/cloud-postgres'), 65534, 65534);
  writeFileSync(join(f.root, '.runtime/cloud-postgres/data/PG_VERSION'), version);
  writeFileSync(join(f.root, '.runtime/cloud.env'), `export JAVA_HOME='${f.root}/jdk'\nexport LUMIRA_CLOUD_PG_MODE=user\nexport LUMIRA_CLOUD_PG_HOME='${f.root}/pg'\n`);
  f.executable('pg/bin/initdb', `echo initdb >> '${f.root}/mutations'; exit 99`);
  return f;
}

test('user-space startup refuses root before touching its cluster', t => {
  const source = readFileSync(resolve('scripts/cloud-database-user.sh'), 'utf8');
  const rootGuard = source.indexOf('[[ $EUID != 0 &&');
  assert.notEqual(rootGuard, -1);
  assert.ok(rootGuard < source.indexOf('mkdir -p "$socket"'));

  // A non-root runner cannot elevate for this check; it still verifies the
  // production guard stays ahead of every cluster operation. Root runners
  // exercise the real refusal path with the isolated fixture below.
  if (process.platform !== 'linux' || process.getuid?.() !== 0) return;

  const f = userDatabaseFixture(t, '17');
  const result = spawnSync('bash', [join(f.root, 'scripts', 'cloud-database.sh')], {
    cwd: f.root, env: f.env, encoding: 'utf8',
  });
  assert.equal(result.status, 1);
  assert.equal(readFileSync(join(f.root, '.runtime/cloud-postgres/data/PG_VERSION'), 'utf8'), '17');
  assert.equal(existsSync(join(f.root, '.runtime/cloud-postgres/socket')), false);
  assert.equal(existsSync(join(f.root, 'mutations')), false);
});

test('user-space startup preserves a database from another PostgreSQL major', t => {
  const f = userDatabaseFixture(t, '17');
  const result = f.run('cloud-database.sh');
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /database must be PostgreSQL 18/);
  assert.equal(readFileSync(join(f.root, '.runtime/cloud-postgres/data/PG_VERSION'), 'utf8'), '17');
  assert.equal(existsSync(join(f.root, 'mutations')), false);
});

test('user-space startup refuses a ready socket owned by another database', t => {
  const f = userDatabaseFixture(t);
  f.executable('pg/bin/pg_isready', 'exit 0');
  f.executable('pg/bin/psql', "printf '/other/database|180006\\n'");
  const result = f.run('cloud-database.sh');
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Unexpected cloud database owner or version/);
  assert.equal(existsSync(join(f.root, '.runtime/cloud-psql-admin.sh')), false);
  assert.equal(existsSync(join(f.root, 'mutations')), false);
});
