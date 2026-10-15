import assert from "node:assert/strict";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { createPinMetadata } from "../scripts/local-runtime-config.mjs";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const temporaryRoots = [];
const pluginIds = ["diffuse-avatar", "feedback", "localization", "sensitive-words"];
const hash = (value) => createHash("sha256").update(value).digest("hex");

function fixture({ targetPluginIds = pluginIds, targetConnections = 0 } = {}) {
  if (process.platform === "win32") return null;

  const root = mkdtempSync(path.join(os.tmpdir(), "lumira-local-runtime-cli-test-"));
  temporaryRoots.push(root);
  const scriptsDirectory = path.join(root, "scripts");
  const runtimeDirectory = path.join(root, ".runtime");
  const pluginsRoot = path.join(runtimeDirectory, "startup-b52", "plugins");
  const filesRoot = path.join(runtimeDirectory, "startup-b52", "files");
  mkdirSync(scriptsDirectory, { recursive: true });
  mkdirSync(path.join(pluginsRoot, "artifacts"), { recursive: true });
  mkdirSync(filesRoot, { recursive: true });

  for (const name of [
    "init-local.mjs",
    "local-runtime.mjs",
    "local-runtime-config.mjs",
    "local-plugin-inventory.mjs",
    "office-worker-runtime.mjs",
    "start.mjs",
  ])
    copyFileSync(path.join(repositoryRoot, "scripts", name), path.join(scriptsDirectory, name));

  const fakePsql = path.join(scriptsDirectory, "fake-psql.mjs");
  writeFileSync(
    fakePsql,
    `#!/usr/bin/env node\nimport { readFileSync } from "node:fs";\nconst args = process.argv.slice(2);\nconst database = args[args.indexOf("--dbname") + 1];\nconst statement = args[args.indexOf("-c") + 1] ?? "";\nconst state = JSON.parse(readFileSync(process.env.LOCAL_RUNTIME_TEST_DATABASES, "utf8"));\nif (statement.includes("to_regclass")) process.stdout.write("[]\\n");\nelse if (statement.includes("pg_stat_activity")) process.stdout.write(JSON.stringify({ canInspectAll: true, otherClients: state[database].otherConnections }) + "\\n");\nelse process.stdout.write(JSON.stringify(state[database].inventory) + "\\n");\n`,
    { mode: 0o700 },
  );
  chmodSync(fakePsql, 0o700);

  const importShim = path.join(scriptsDirectory, "local-runtime-test-shim.mjs");
  writeFileSync(
    importShim,
    `import net from "node:net";\nimport fs from "node:fs";\nimport path from "node:path";\nimport { EventEmitter } from "node:events";\nimport { syncBuiltinESMExports } from "node:module";\nnet.createConnection = () => { const socket = new EventEmitter(); socket.setTimeout = () => socket; socket.destroy = () => {}; process.nextTick(() => socket.emit("error", Object.assign(new Error("isolated port probe"), { code: "ECONNREFUSED" }))); return socket; };\nconst failOnRename = new Map(JSON.parse(process.env.LOCAL_RUNTIME_TEST_FAIL_RENAMES ?? "[]").map(([target, occurrence]) => [path.resolve(target), Number(occurrence)]));\nconst renameCounts = new Map();\nconst originalRename = fs.renameSync;\nfs.renameSync = function(source, destination, ...rest) { const target = path.resolve(destination); const count = (renameCounts.get(target) ?? 0) + 1; renameCounts.set(target, count); if (failOnRename.get(target) === count) { const error = new Error("isolated rename failure"); error.code = "EIO"; throw error; } return originalRename.call(fs, source, destination, ...rest); };\nsyncBuiltinESMExports();\n`,
    { mode: 0o600 },
  );

  const pluginRecords = pluginIds.map((id, index) => {
    const version = ["1.0.1", "1.0.14", "1.0.11", "1.0.9"][index];
    const jarBytes = Buffer.from(`isolated fake ${id} jar`);
    const frontendBytes = Buffer.from(`window.__fixturePlugin = ${JSON.stringify(id)};`);
    const digest = hash(`fixture archive:${id}@${version}`);
    const manifest = {
      formatVersion: 1,
      id,
      name: id,
      version,
      sdkVersion: "1",
      entryClass: `com.lumira.plugins.${id}.Entry`,
      backend: "backend/plugin.jar",
      frontend: "frontend/plugin.js",
      dependencies: [],
      permissions: [],
      routes: [],
      apiRules: [],
      files: {
        "backend/plugin.jar": hash(jarBytes),
        "frontend/plugin.js": hash(frontendBytes),
      },
    };
    const artifactDirectory = path.join(pluginsRoot, "artifacts", digest);
    mkdirSync(path.join(artifactDirectory, "backend"), { recursive: true });
    mkdirSync(path.join(artifactDirectory, "frontend"), { recursive: true });
    writeFileSync(path.join(artifactDirectory, "backend", "plugin.jar"), jarBytes);
    writeFileSync(path.join(artifactDirectory, "frontend", "plugin.js"), frontendBytes);
    writeFileSync(path.join(artifactDirectory, "plugin.json"), `${JSON.stringify(manifest, null, 2)}\n`);
    return {
      id,
      version,
      digest,
      manifest,
      installed: true,
      explicitInstall: true,
      enabled: true,
    };
  });

  const makeDatabase = (name, oid, records) => ({
    database: {
      name,
      oid: String(oid),
      user: "lumira_test",
      serverAddress: "127.0.0.1",
      serverPort: 5432,
      serverVersionNum: "180006",
      serverVersion: "PostgreSQL 18.6",
      writablePrimary: true,
      flywayVersion: "52",
      flywayChecksum: 123456789,
    },
    plugins: records,
    danglingInstallations: [],
  });
  const databases = {
    lumira_source: {
      otherConnections: 0,
      inventory: makeDatabase("lumira_source", 16384, pluginRecords),
    },
    lumira_target: {
      otherConnections: targetConnections,
      inventory: makeDatabase(
        "lumira_target",
        16385,
        pluginRecords.filter((plugin) => targetPluginIds.includes(plugin.id)),
      ),
    },
  };
  const databasesFile = path.join(runtimeDirectory, "fake-databases.json");
  writeFileSync(databasesFile, `${JSON.stringify(databases, null, 2)}\n`, { mode: 0o600 });

  const configFor = (databaseName) => ({
    LUMIRA_DB_URL: `jdbc:postgresql://127.0.0.1:5432/${databaseName}`,
    LUMIRA_DB_USER: "lumira_test",
    LUMIRA_DB_PASSWORD: "fixture-only-db-password",
    LUMIRA_FILES_LOCAL_ROOT: filesRoot,
    LUMIRA_PLUGINS_PATH: pluginsRoot,
    LUMIRA_API_ORIGIN: "http://127.0.0.1:8090",
    LUMIRA_PORT: "8090",
    LUMIRA_BIND: "127.0.0.1",
    LUMIRA_COOKIE_SECURE: "false",
    LUMIRA_LOCAL_RUNTIME_PIN_VERSION: "1",
    PSQL_BIN: fakePsql,
    SPRING_FLYWAY_ENABLED: "true",
    SPRING_FLYWAY_VALIDATE_ON_MIGRATE: "true",
    SPRING_FLYWAY_BASELINE_ON_MIGRATE: "false",
    SPRING_FLYWAY_BASELINE_VERSION: "52",
  });
  const sourceConfig = configFor("lumira_source");
  const targetConfig = configFor("lumira_target");
  const sourceSnapshot = {
    formatVersion: 1,
    capturedAt: "2026-10-16T00:00:00.000Z",
    database: {
      configuredHost: "127.0.0.1",
      configuredPort: 5432,
      ...databases.lumira_source.inventory.database,
    },
    plugins: [],
  };

  // The production guard hashes stable-key-order JSON.
  const canonicalManifestDigest = (manifest) => hash(stableJson(manifest));
  sourceSnapshot.plugins = pluginRecords.map(({ manifest, ...plugin }) => ({
    ...plugin,
    manifestDigest: canonicalManifestDigest(manifest),
  }));

  const jarPath = path.join(root, "backend", "target", "platform-server-fixture.jar");
  mkdirSync(path.dirname(jarPath), { recursive: true });
  writeFileSync(jarPath, "fixture backend jar bytes");
  mkdirSync(runtimeDirectory, { recursive: true });
  const configFile = path.join(runtimeDirectory, "local.json");
  const pinFile = path.join(runtimeDirectory, "local-runtime.json");
  const inventoryFile = path.join(runtimeDirectory, "local-plugin-inventory.json");
  const targetConfigFile = path.join(runtimeDirectory, "target.json");
  writeFileSync(configFile, `${JSON.stringify(sourceConfig, null, 2)}\n`, { mode: 0o600 });
  writeFileSync(targetConfigFile, `${JSON.stringify(targetConfig, null, 2)}\n`, { mode: 0o600 });

  const pin = createPinMetadata(root, sourceConfig, {
    artifact: { path: path.relative(root, jarPath).split(path.sep).join("/") },
    now: new Date("2026-10-16T00:00:00.000Z"),
  });
  writeFileSync(pinFile, `${JSON.stringify(pin, null, 2)}\n`, { mode: 0o600 });
  writeFileSync(inventoryFile, `${JSON.stringify(sourceSnapshot, null, 2)}\n`, { mode: 0o600 });

  return {
    root,
    databasesFile,
    configFile,
    pinFile,
    inventoryFile,
    targetConfigFile,
    importShim,
    sourceConfig,
  };
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object")
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(",")}}`;
  return JSON.stringify(value);
}

function readTrackedBytes(fixtureRoot) {
  return [
    path.join(fixtureRoot, ".runtime", "local.json"),
    path.join(fixtureRoot, ".runtime", "local-runtime.json"),
    path.join(fixtureRoot, ".runtime", "local-plugin-inventory.json"),
  ].map((file) => readFileSync(file));
}

function runtimeLockFixture({ createLock = false } = {}) {
  const root = mkdtempSync(path.join(os.tmpdir(), "lumira-local-runtime-lock-test-"));
  temporaryRoots.push(root);
  const scriptsDirectory = path.join(root, "scripts");
  const runtimeDirectory = path.join(root, ".runtime");
  mkdirSync(scriptsDirectory, { recursive: true });
  mkdirSync(runtimeDirectory, { recursive: true });
  for (const name of [
    "init-local.mjs",
    "local-runtime-config.mjs",
    "office-worker-runtime.mjs",
    "start.mjs",
  ])
    copyFileSync(path.join(repositoryRoot, "scripts", name), path.join(scriptsDirectory, name));
  const lockFile = path.join(runtimeDirectory, "local-runtime.lock");
  if (createLock) writeFileSync(lockFile, '{"pid":999999,"operation":"switch"}\n', { mode: 0o600 });
  return { root, lockFile, configFile: path.join(runtimeDirectory, "local.json") };
}

function runSwitch(fixtureData, { failRenameDestination, failRenameDestinations = [] } = {}) {
  const args = [
    "--import",
    fixtureData.importShim,
    path.join(fixtureData.root, "scripts", "local-runtime.mjs"),
    "switch",
    "--target-config",
    ".runtime/target.json",
  ];
  const env = {
    PATH: process.env.PATH ?? "",
    LOCAL_RUNTIME_TEST_DATABASES: fixtureData.databasesFile,
    LOCAL_RUNTIME_TEST_FAIL_RENAMES: JSON.stringify([
      ...(failRenameDestination ? [[failRenameDestination, 1]] : []),
      ...failRenameDestinations,
    ]),
  };
  return spawnSync(process.execPath, args, {
    cwd: fixtureData.root,
    env,
    encoding: "utf8",
    timeout: 20_000,
  });
}

test.after(() => {
  for (const root of temporaryRoots) rmSync(root, { recursive: true, force: true });
});

test("start and db:prepare refuse an active mutation lock before reading or creating canonical config", (t) => {
  if (process.platform === "win32") return t.skip("isolated runtime command checks require WSL/Linux");
  const data = runtimeLockFixture({ createLock: true });
  for (const script of ["start.mjs", "init-local.mjs"]) {
    const result = spawnSync(process.execPath, [path.join(data.root, "scripts", script)], {
      cwd: data.root,
      env: { PATH: process.env.PATH ?? "" },
      encoding: "utf8",
      timeout: 10_000,
    });
    assert.notEqual(result.status, 0, `${script} unexpectedly completed`);
    assert.match(result.stderr, /A local runtime mutation is already active/);
    assert.equal(existsSync(data.configFile), false, `${script} created canonical config while locked`);
    assert.equal(existsSync(path.join(data.root, ".runtime", "processes.json")), false);
  }
  assert.equal(existsSync(data.lockFile), true);
});

test("start releases its lock when preflight fails before launching services", (t) => {
  if (process.platform === "win32") return t.skip("isolated runtime command checks require WSL/Linux");
  const data = runtimeLockFixture();
  const result = spawnSync(process.execPath, [path.join(data.root, "scripts", "start.mjs")], {
    cwd: data.root,
    env: { PATH: process.env.PATH ?? "" },
    encoding: "utf8",
    timeout: 10_000,
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /canonical local runtime configuration is missing/);
  assert.equal(existsSync(data.lockFile), false);
  assert.equal(existsSync(path.join(data.root, ".runtime", "processes.json")), false);
});

test("actual local-runtime switch promotes an identical four-plugin target and binds all three runtime files", (t) => {
  if (process.platform === "win32") return t.skip("isolated executable fake psql requires WSL/Linux");
  const data = fixture();
  const before = readTrackedBytes(data.root);
  const result = runSwitch(data);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Verified and promoted/);

  const [configBytes, pinBytes, inventoryBytes] = readTrackedBytes(data.root);
  assert.notDeepEqual(configBytes, before[0]);
  assert.notDeepEqual(pinBytes, before[1]);
  assert.notDeepEqual(inventoryBytes, before[2]);
  const config = JSON.parse(configBytes);
  const pin = JSON.parse(pinBytes);
  const inventory = JSON.parse(inventoryBytes);
  assert.equal(config.LUMIRA_DB_URL, "jdbc:postgresql://127.0.0.1:5432/lumira_target");
  assert.equal(pin.database.database, "lumira_target");
  assert.equal(pin.database.user, "lumira_test");
  assert.equal(pin.configSha256, createPinMetadata(data.root, config).configSha256);
  assert.equal(inventory.database.name, "lumira_target");
  assert.equal(inventory.database.oid, "16385");
  assert.deepEqual(inventory.plugins.map((plugin) => plugin.id), pluginIds);
});

test("actual local-runtime switch rejects a target missing diffuse-avatar without changing the three pinned files", (t) => {
  if (process.platform === "win32") return t.skip("isolated executable fake psql requires WSL/Linux");
  const data = fixture({ targetPluginIds: pluginIds.filter((id) => id !== "diffuse-avatar") });
  const before = readTrackedBytes(data.root);
  const result = runSwitch(data);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /missing diffuse-avatar@/);
  assert.deepEqual(readTrackedBytes(data.root), before);
});

for (const filename of ["local-runtime.json", "local-plugin-inventory.json"]) {
  test(`actual local-runtime switch restores all original bytes when promotion fails at ${filename}`, (t) => {
    if (process.platform === "win32") return t.skip("isolated executable fake psql requires WSL/Linux");
    const data = fixture();
    const before = readTrackedBytes(data.root);
    const result = runSwitch(data, {
      failRenameDestination: path.join(data.root, ".runtime", filename),
    });
    assert.notEqual(result.status, 0);
    if (filename === "local-runtime.json")
      assert.match(result.stderr, /Unable to write private local runtime configuration/);
    else assert.match(result.stderr, /isolated rename failure/);
    assert.deepEqual(readTrackedBytes(data.root), before);
  });
}

test("actual local-runtime switch rejects a target with another database client before promoting files", (t) => {
  if (process.platform === "win32") return t.skip("isolated executable fake psql requires WSL/Linux");
  const data = fixture({ targetConnections: 1 });
  const before = readTrackedBytes(data.root);
  const result = runSwitch(data);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /no other client connections/);
  assert.deepEqual(readTrackedBytes(data.root), before);
});

test("actual local-runtime switch attempts every restore and reports a partial rollback", (t) => {
  if (process.platform === "win32") return t.skip("isolated executable fake psql requires WSL/Linux");
  const data = fixture();
  const before = readTrackedBytes(data.root);
  const configFile = path.join(data.root, ".runtime", "local.json");
  const inventoryFile = path.join(data.root, ".runtime", "local-plugin-inventory.json");
  const result = runSwitch(data, {
    failRenameDestinations: [
      [inventoryFile, 1],
      [configFile, 2],
    ],
  });

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Runtime rollback could not restore every file \(local\.json\)/);
  const after = readTrackedBytes(data.root);
  assert.notDeepEqual(after[0], before[0], "the injected config restore failure should be visible");
  assert.deepEqual(after[1], before[1], "pin rollback should still run after config restore failure");
  assert.deepEqual(after[2], before[2], "inventory rollback should still run after config restore failure");
  assert.equal(existsSync(path.join(data.root, ".runtime", "local-runtime.lock")), false);
});
