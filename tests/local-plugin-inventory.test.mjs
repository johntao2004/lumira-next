import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { chmodSync, existsSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  assertLocalPluginInventory,
  captureLocalPluginInventory,
  compareLocalPluginInventory,
  localPluginDatabaseIdentityDifferences,
  normalizePersistedPluginManifest,
  parseLocalPluginDatabaseConfig,
  parseLocalPluginInventorySnapshot,
  verifyLocalPluginInventoryTransfer,
  writeLocalPluginInventorySnapshot,
} from '../scripts/local-plugin-inventory.mjs';

const temporaryRoots = [];

test.after(() => {
  for (const root of temporaryRoots) rmSync(root, { recursive: true, force: true });
});

const digest = letter => letter.repeat(64);
const plugin = (id, version, marker) => ({
  id,
  version,
  digest: digest(marker),
  manifestDigest: digest(String.fromCharCode(marker.charCodeAt(0) + 1)),
  installed: true,
  explicitInstall: true,
  enabled: true,
});
const database = {
  configuredHost: '127.0.0.1',
  configuredPort: 5432,
  name: 'lumira_next',
  oid: '16384',
  user: 'lumira_next',
  serverAddress: '172.17.0.2',
  serverPort: 5432,
  serverVersionNum: '180006',
  serverVersion: 'PostgreSQL 18.6',
  flywayVersion: '52',
  flywayChecksum: 123456789,
  writablePrimary: true,
};
const source = () => ({
  formatVersion: 1,
  capturedAt: '2026-10-16T00:00:00.000Z',
  database: { ...database },
  plugins: [
    plugin('diffuse-avatar', '1.0.1', 'a'),
    plugin('feedback', '1.0.14', 'b'),
    plugin('localization', '1.0.11', 'c'),
    plugin('sensitive-words', '1.0.9', 'd'),
  ],
});

const sha256 = value => createHash('sha256').update(value).digest('hex');
const stableJson = value => {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  if (value && typeof value === 'object')
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
};

function artifact(id, marker) {
  const backend = Buffer.from(`backend-${marker}`);
  const frontend = Buffer.from(`frontend-${marker}`);
  const manifest = {
    formatVersion: 1,
    id,
    name: id,
    version: '1.0.0',
    sdkVersion: '1.0.0',
    entryClass: `com.lumira.plugin.${id}.PluginEntry`,
    backend: 'backend.jar',
    frontend: 'frontend.js',
    dependencies: [],
    permissions: [],
    permissionGroups: [],
    routes: [],
    apiRules: [],
    files: {
      'backend.jar': sha256(backend),
      'frontend.js': sha256(frontend),
    },
    dataSets: [],
  };
  return {
    row: {
      id,
      version: manifest.version,
      digest: sha256(Buffer.from(`archive-${marker}`)),
      manifest,
      installed: true,
      explicitInstall: true,
      enabled: true,
    },
    files: { 'backend.jar': backend, 'frontend.js': frontend },
  };
}

function databaseSnapshot(name, oid, plugins) {
  return {
    database: {
      name,
      oid,
      user: 'inventory_user',
      serverAddress: '127.0.0.1',
      serverPort: 5432,
      serverVersionNum: '180006',
      serverVersion: 'PostgreSQL 18.6',
      writablePrimary: true,
      flywayVersion: '52',
      flywayChecksum: 123456789,
    },
    plugins,
    danglingInstallations: [],
  };
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`;
}

function createInventoryFixture({ sourcePlugins, targetPlugins = sourcePlugins, emptySource = false } = {}) {
  if (process.platform === 'win32') throw new Error('The isolated fake-psql fixture requires WSL/Linux.');
  const root = mkdtempSync(path.join(os.tmpdir(), 'lumira-plugin-inventory-test-'));
  temporaryRoots.push(root);
  const pluginsRoot = path.join(root, '.runtime', 'plugins');
  mkdirSync(path.join(pluginsRoot, 'artifacts'), { recursive: true });
  const artifacts = new Map();
  for (const item of [...sourcePlugins, ...targetPlugins]) artifacts.set(item.row.digest, item);
  for (const item of artifacts.values()) {
    const directory = path.join(pluginsRoot, 'artifacts', item.row.digest);
    mkdirSync(directory, { recursive: true });
    writeFileSync(path.join(directory, 'plugin.json'), `${JSON.stringify(item.row.manifest)}\n`);
    for (const [relative, bytes] of Object.entries(item.files)) writeFileSync(path.join(directory, relative), bytes);
  }

  const fixtureFile = path.join(root, '.runtime', 'postgres-fixtures.json');
  const processFile = path.join(root, '.runtime', 'processes.json');
  const mockClient = path.join(root, '.runtime', 'fake-psql.mjs');
  const psql = path.join(root, '.runtime', 'fake-psql');
  const fixtureData = {
    databases: {
      lumira_qa_source: {
        inventory: databaseSnapshot('lumira_qa_source', '41001', emptySource ? [] : sourcePlugins.map(item => item.row)),
        canInspectAll: true,
        otherClients: 0,
      },
      lumira_qa_target: {
        inventory: databaseSnapshot('lumira_qa_target', '41002', targetPlugins.map(item => item.row)),
        canInspectAll: true,
        otherClients: 0,
      },
    },
    processFile,
    parentPid: process.pid,
  };
  writeFileSync(fixtureFile, JSON.stringify(fixtureData));
  const mockSource = `import { readFileSync, writeFileSync } from 'node:fs';
const fixtures = JSON.parse(readFileSync(${JSON.stringify(fixtureFile)}, 'utf8'));
const args = process.argv.slice(2);
const database = args[args.indexOf('--dbname') + 1];
const statement = args[args.indexOf('-c') + 1] ?? '';
const fixture = fixtures.databases[database];
if (!fixture) process.exit(3);
if (statement.includes('to_regclass')) process.stdout.write('[]');
else if (statement.includes('pg_stat_activity')) {
  if (fixture.activateProcessOnCheck) writeFileSync(fixtures.processFile, JSON.stringify([{ name: 'backend', pid: fixtures.parentPid }]));
  process.stdout.write(JSON.stringify({ canInspectAll: fixture.canInspectAll, otherClients: fixture.otherClients }));
} else process.stdout.write(JSON.stringify(fixture.inventory));
`;
  writeFileSync(mockClient, mockSource);
  writeFileSync(psql, `#!/bin/sh\nexec ${shellQuote(process.execPath)} ${shellQuote(mockClient)} "$@"\n`, { mode: 0o700 });
  chmodSync(psql, 0o700);

  const config = databaseName => ({
    LUMIRA_DB_URL: `jdbc:postgresql://127.0.0.1:5432/${databaseName}`,
    LUMIRA_DB_USER: 'inventory_user',
    LUMIRA_DB_PASSWORD: 'fixture-only-secret',
    LUMIRA_PLUGINS_PATH: path.join(root, '.runtime', 'plugins'),
    PSQL_BIN: psql,
  });
  const writeFixtures = value => writeFileSync(fixtureFile, JSON.stringify(value));
  return {
    root,
    fixtureData,
    writeFixtures,
    sourceConfig: config('lumira_qa_source'),
    targetConfig: config('lumira_qa_target'),
  };
}

test('exact four-plugin inventory passes with database identity and all plugin states', () => {
  const pinned = source();
  const current = { ...pinned, capturedAt: '2026-10-16T00:05:00.000Z' };
  assert.deepEqual(compareLocalPluginInventory(pinned, current), []);
});

test('a three-plugin target fails with an explicit missing diffuse-avatar difference', () => {
  const pinned = source();
  const target = { ...source(), plugins: source().plugins.filter(item => item.id !== 'diffuse-avatar') };
  const differences = compareLocalPluginInventory(pinned, target);
  assert.ok(differences.some(item => item.includes('missing diffuse-avatar@1.0.1')));
});

test('version, digest, installed, explicit install, and enabled drift each fail closed', () => {
  const pinned = source();
  const mutations = [
    ['version', item => ({ ...item, version: '1.0.2' })],
    ['digest', item => ({ ...item, digest: digest('e') })],
    ['installed', item => ({ ...item, installed: false })],
    ['explicitInstall', item => ({ ...item, explicitInstall: false })],
    ['enabled', item => ({ ...item, enabled: false })],
    ['manifestDigest', item => ({ ...item, manifestDigest: digest('f') })],
  ];
  for (const [field, mutate] of mutations) {
    const target = source();
    target.plugins[0] = mutate(target.plugins[0]);
    const differences = compareLocalPluginInventory(pinned, target);
    assert.notDeepEqual(differences, [], `${field} drift should fail`);
  }
});

test('database identity drift fails even when the plugin list is identical', () => {
  const pinned = source();
  const target = source();
  target.database = { ...target.database, oid: '16385' };
  const differences = compareLocalPluginInventory(pinned, target);
  assert.ok(differences.some(item => item.includes('database.oid')));
});

test('PostgreSQL maintenance version changes need an explicit refresh but do not change database identity', () => {
  const pinned = source();
  const patched = source();
  patched.database = {
    ...patched.database,
    serverVersionNum: '180007',
    serverVersion: 'PostgreSQL 18.7',
    flywayChecksum: 987654321,
  };
  assert.deepEqual(localPluginDatabaseIdentityDifferences(pinned.database, patched.database), []);
  assert.ok(compareLocalPluginInventory(pinned, patched).some(item => item.startsWith('database.serverVersion')));

  const renamed = { ...patched.database, name: 'lumira_qa_plugin_transfer_test' };
  assert.ok(localPluginDatabaseIdentityDifferences(pinned.database, renamed).includes('name'));
});

test('snapshot parsing rejects missing identity, duplicate plugin keys, and invalid digest or state', () => {
  const malformedDatabase = source();
  delete malformedDatabase.database.oid;
  assert.throws(() => parseLocalPluginInventorySnapshot(JSON.stringify(malformedDatabase)), /incomplete database identity/);

  const duplicate = source();
  duplicate.plugins.push({ ...duplicate.plugins[0] });
  assert.throws(() => parseLocalPluginInventorySnapshot(JSON.stringify(duplicate)), /duplicate ID\/version/);

  const invalidDigest = source();
  invalidDigest.plugins[0].digest = 'bad';
  assert.throws(() => parseLocalPluginInventorySnapshot(JSON.stringify(invalidDigest)), /incomplete plugin record/);

  const unknownState = source();
  unknownState.plugins[0].installed = null;
  assert.throws(() => parseLocalPluginInventorySnapshot(JSON.stringify(unknownState)), /incomplete plugin record/);
});

test('manifest comparison mirrors PluginManifest defaults for live diffuse-avatar and feedback packages', () => {
  for (const id of ['diffuse-avatar', 'feedback']) {
    const disk = JSON.parse(readFileSync(new URL(`../plugins/${id}/plugin.json`, import.meta.url), 'utf8'));
    const persisted = structuredClone(disk);
    if (id === 'diffuse-avatar') {
      persisted.permissionGroups = [];
      persisted.dataSets = [];
    } else {
      persisted.permissionGroups = disk.permissionGroups.map(group => ({
        ...group,
        permissions: group.permissions.map(permission => ({ ...permission, requires: [] })),
      }));
      persisted.dataSets = disk.dataSets.filter(set => !(set.table === 'platform_account_draft'
        && set.column === 'draft_key' && set.value === 'feedback.create'));
    }
    assert.deepEqual(normalizePersistedPluginManifest(disk), normalizePersistedPluginManifest(persisted), id);
  }
  const disk = JSON.parse(readFileSync(new URL('../plugins/diffuse-avatar/plugin.json', import.meta.url), 'utf8'));
  const normalized = normalizePersistedPluginManifest(disk);
  assert.deepEqual(normalized.permissionGroups, []);
  assert.deepEqual(normalized.dataSets, []);
});

test('database configuration requires a loopback Lumira database and separates credentials', () => {
  const config = {
    LUMIRA_DB_URL: 'jdbc:postgresql://127.0.0.1:5432/lumira_qa_plugin_transfer_test',
    LUMIRA_DB_USER: 'lumira_next',
    LUMIRA_DB_PASSWORD: 'test-only-password',
  };
  assert.deepEqual(parseLocalPluginDatabaseConfig(config), {
    host: '127.0.0.1',
    port: 5432,
    databaseName: 'lumira_qa_plugin_transfer_test',
    user: 'lumira_next',
    password: 'test-only-password',
    sslmode: null,
    psqlBin: undefined,
    dockerContainer: undefined,
  });
  assert.equal(parseLocalPluginDatabaseConfig({ ...config, PSQL_BIN: 'C:/tools/psql.exe' }).psqlBin, 'C:/tools/psql.exe');
  assert.equal(parseLocalPluginDatabaseConfig({ ...config, LUMIRA_PSQL_DOCKER_CONTAINER: 'local-pg-client' }).dockerContainer, 'local-pg-client');
  assert.throws(() => parseLocalPluginDatabaseConfig({
    ...config,
    LUMIRA_DB_URL: 'jdbc:postgresql://bookkin-db:5432/bookkin',
  }), /loopback|lumira database/);
  assert.throws(() => parseLocalPluginDatabaseConfig({
    ...config,
    LUMIRA_DB_URL: 'jdbc:postgresql://user:secret@127.0.0.1:5432/lumira_next',
  }), /must not embed credentials/);
});

test('isolated capture pins a real artifact tree, reports drift, and requires explicit acceptance', { skip: process.platform === 'win32' }, () => {
  const plugins = [artifact('diffuse-avatar', 'a'), artifact('feedback', 'b')];
  const fixture = createInventoryFixture({ sourcePlugins: plugins });
  const snapshotPath = '.runtime/local-plugin-inventory.json';
  const captured = captureLocalPluginInventory({ root: fixture.root, config: fixture.sourceConfig });
  assert.deepEqual(captured.plugins.map(item => item.id), ['diffuse-avatar', 'feedback']);
  assert.equal(assertLocalPluginInventory({ root: fixture.root, config: fixture.sourceConfig }).plugins.length, 2);
  const snapshotFile = path.join(fixture.root, snapshotPath);
  const beforeDrift = readFileSync(snapshotFile);

  fixture.fixtureData.databases.lumira_qa_source.inventory.plugins[0].enabled = false;
  fixture.writeFixtures(fixture.fixtureData);
  assert.throws(() => assertLocalPluginInventory({ root: fixture.root, config: fixture.sourceConfig }), /enabled: pinned=true, current=false/);
  assert.throws(() => captureLocalPluginInventory({ root: fixture.root, config: fixture.sourceConfig }), /non-empty reason/);
  assert.deepEqual(readFileSync(snapshotFile), beforeDrift);
  captureLocalPluginInventory({ root: fixture.root, config: fixture.sourceConfig, acceptChangesReason: 'fixture state change accepted' });
  assert.equal(assertLocalPluginInventory({ root: fixture.root, config: fixture.sourceConfig }).plugins[0].enabled, false);
  assert.ok(readdirSync(path.join(fixture.root, '.runtime', 'backups')).length > 0);
});

test('isolated transfer rejects empty inventory, missing plugins, state drift, and visible active clients', { skip: process.platform === 'win32' }, () => {
  const sourcePlugins = [artifact('diffuse-avatar', 'c'), artifact('feedback', 'd')];
  const empty = createInventoryFixture({ sourcePlugins, emptySource: true });
  assert.throws(() => captureLocalPluginInventory({ root: empty.root, config: empty.sourceConfig }), /has no plugin artifacts/);
  assert.equal(existsSync(path.join(empty.root, '.runtime', 'local-plugin-inventory.json')), false);

  const fixture = createInventoryFixture({ sourcePlugins, targetPlugins: [sourcePlugins[1]] });
  captureLocalPluginInventory({ root: fixture.root, config: fixture.sourceConfig });
  const pinnedFile = path.join(fixture.root, '.runtime', 'local-plugin-inventory.json');
  const pinnedBytes = readFileSync(pinnedFile);
  assert.throws(
    () => verifyLocalPluginInventoryTransfer({ root: fixture.root, sourceConfig: fixture.sourceConfig, targetConfig: fixture.targetConfig }),
    /missing diffuse-avatar@1\.0\.0/,
  );
  assert.deepEqual(readFileSync(pinnedFile), pinnedBytes);

  fixture.fixtureData.databases.lumira_qa_target.inventory.plugins = sourcePlugins.map(item => ({ ...item.row }));
  fixture.fixtureData.databases.lumira_qa_target.inventory.plugins[0].enabled = false;
  fixture.writeFixtures(fixture.fixtureData);
  assert.throws(
    () => verifyLocalPluginInventoryTransfer({ root: fixture.root, sourceConfig: fixture.sourceConfig, targetConfig: fixture.targetConfig }),
    /enabled: pinned=true, current=false/,
  );

  fixture.fixtureData.databases.lumira_qa_target.inventory.plugins[0].enabled = true;
  fixture.fixtureData.databases.lumira_qa_source.otherClients = 1;
  fixture.writeFixtures(fixture.fixtureData);
  assert.throws(
    () => verifyLocalPluginInventoryTransfer({ root: fixture.root, sourceConfig: fixture.sourceConfig, targetConfig: fixture.targetConfig }),
    /no other client connections/,
  );

  fixture.fixtureData.databases.lumira_qa_source.otherClients = 0;
  fixture.fixtureData.databases.lumira_qa_source.canInspectAll = false;
  fixture.writeFixtures(fixture.fixtureData);
  assert.throws(
    () => verifyLocalPluginInventoryTransfer({ root: fixture.root, sourceConfig: fixture.sourceConfig, targetConfig: fixture.targetConfig }),
    /pg_read_all_stats visibility/,
  );
  assert.deepEqual(readFileSync(pinnedFile), pinnedBytes);
});

test('isolated matching transfer promotes only its verified target snapshot', { skip: process.platform === 'win32' }, () => {
  const plugins = [artifact('diffuse-avatar', 'e'), artifact('feedback', 'f')];
  const fixture = createInventoryFixture({ sourcePlugins: plugins, targetPlugins: plugins });
  captureLocalPluginInventory({ root: fixture.root, config: fixture.sourceConfig });
  const transfer = verifyLocalPluginInventoryTransfer({
    root: fixture.root,
    sourceConfig: fixture.sourceConfig,
    targetConfig: fixture.targetConfig,
  });
  assert.equal(transfer.verified, true);
  writeLocalPluginInventorySnapshot({
    root: fixture.root,
    snapshot: transfer.snapshotForTarget,
    expectedSourceDigest: transfer.sourceSnapshotDigest,
  });
  const promoted = assertLocalPluginInventory({ root: fixture.root, config: fixture.targetConfig });
  assert.equal(promoted.database.name, 'lumira_qa_target');
  assert.deepEqual(promoted.plugins.map(item => item.id), ['diffuse-avatar', 'feedback']);
});
