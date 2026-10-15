import assert from "node:assert/strict";
import {
  mkdirSync,
  mkdtempSync,
  existsSync,
  readFileSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  adoptRunningBackend,
  assertNoJvmIdentityOverrides,
  assertNoSpringRuntimeOverrides,
  backupAndWriteRuntimeConfig,
  buildAdoptedRuntimeConfig,
  buildChildEnvironment,
  configFingerprint,
  createPinMetadata,
  environmentDrift,
  readLocalRuntime,
  readProcEnvironment,
  resolveLocalBackendPort,
  resolveWorkspaceDirectory,
  resolveWorkspaceFile,
  sanitizeConfigForDisplay,
  validatePinnedConfig,
  withLocalRuntimeLock,
  writeLocalJson,
} from "../scripts/local-runtime-config.mjs";

const temporaryRoots = [];

function fixture() {
  const root = mkdtempSync(path.join(os.tmpdir(), "lumira-local-runtime-test-"));
  temporaryRoots.push(root);
  mkdirSync(path.join(root, ".runtime", "files"), { recursive: true });
  mkdirSync(path.join(root, ".runtime", "plugins"), { recursive: true });
  return root;
}

function configFor(root, overrides = {}) {
  return {
    LUMIRA_DB_URL: "jdbc:postgresql://127.0.0.1:5432/quota_test",
    LUMIRA_DB_USER: "quota_test_user",
    LUMIRA_DB_PASSWORD: "fixture-db-secret",
    LUMIRA_BOOTSTRAP_PASSWORD: "fixture-bootstrap-secret",
    LUMIRA_SECRET_KEYS: "fixture-key-one,fixture-key-two",
    LUMIRA_SECRET_ACTIVE_KEY: "fixture-key-two",
    LUMIRA_FILES_LOCAL_ROOT: path.join(root, ".runtime", "files"),
    LUMIRA_PLUGINS_PATH: path.join(root, ".runtime", "plugins"),
    LUMIRA_PORT: "8090",
    LUMIRA_BIND: "127.0.0.1",
    LUMIRA_COOKIE_SECURE: "false",
    LUMIRA_API_ORIGIN: "http://127.0.0.1:8090",
    LUMIRA_LOCAL_RUNTIME_PIN_VERSION: "1",
    PSQL_BIN: "psql",
    SPRING_FLYWAY_ENABLED: "true",
    SPRING_FLYWAY_VALIDATE_ON_MIGRATE: "true",
    SPRING_FLYWAY_BASELINE_ON_MIGRATE: "false",
    SPRING_FLYWAY_BASELINE_VERSION: "52",
    CUSTOM_PRESERVED_FIELD: "preserve-me",
    ...overrides,
  };
}

test.after(() => {
  for (const root of temporaryRoots) rmSync(root, { recursive: true, force: true });
});

test("adoption takes active runtime identity while preserving unrelated canonical fields", () => {
  const root = fixture();
  const existing = {
    CUSTOM_PRESERVED_FIELD: "keep this value",
    LUMIRA_DB_URL: "jdbc:postgresql://127.0.0.1:5432/old_database",
    PSQL_BIN: "stale-client",
    LUMIRA_PSQL_DOCKER_CONTAINER: "stale-container",
  };
  const active = {
    LUMIRA_DB_URL: "jdbc:postgresql://127.0.0.1:5432/current_database",
    LUMIRA_DB_USER: "current_user",
    LUMIRA_DB_PASSWORD: "current-secret",
    LUMIRA_FILES_LOCAL_ROOT: path.join(root, ".runtime", "files"),
    LUMIRA_PLUGINS_PATH: path.join(root, ".runtime", "plugins"),
    LUMIRA_BIND: "127.0.0.1",
  };

  const adopted = buildAdoptedRuntimeConfig(existing, active, {
    psqlDockerContainer: "lumira-postgres",
  });

  assert.equal(adopted.CUSTOM_PRESERVED_FIELD, "keep this value");
  assert.equal(adopted.LUMIRA_DB_URL, active.LUMIRA_DB_URL);
  assert.equal(adopted.LUMIRA_DB_PASSWORD, active.LUMIRA_DB_PASSWORD);
  assert.equal(adopted.LUMIRA_PSQL_DOCKER_CONTAINER, "lumira-postgres");
  assert.equal(Object.hasOwn(adopted, "PSQL_BIN"), false);
  assert.equal(adopted.LUMIRA_API_ORIGIN, "http://127.0.0.1:8090");
  assert.equal(adopted.LUMIRA_LOCAL_RUNTIME_PIN_VERSION, "1");
});

test("canonical pin is shared by startup readers and rejects identity drift without printing secrets", () => {
  const root = fixture();
  const config = configFor(root);
  validatePinnedConfig(root, config);
  const pin = createPinMetadata(root, config);
  writeLocalJson(root, ".runtime/local.json", config);
  writeLocalJson(root, ".runtime/local-runtime.json", pin);

  const runtime = readLocalRuntime(root);
  assert.equal(runtime.pinned, true);
  assert.equal(runtime.config.LUMIRA_DB_URL, config.LUMIRA_DB_URL);
  assert.equal(runtime.pin.configSha256, configFingerprint(config));
  assert.equal(runtime.database.database, "quota_test");
  assert.equal(JSON.stringify(sanitizeConfigForDisplay(config)).includes("fixture-db-secret"), false);
  assert.equal(sanitizeConfigForDisplay(config).LUMIRA_DB_PASSWORD, "[redacted]");

  writeLocalJson(root, ".runtime/local.json", {
    ...config,
    LUMIRA_DB_URL: "jdbc:postgresql://127.0.0.1:5432/other_database",
  });
  assert.throws(() => readLocalRuntime(root), { code: "LOCAL_RUNTIME_CONFIG_DRIFT" });
});

test("a canonical pin marker without metadata fails closed", () => {
  const root = fixture();
  writeLocalJson(root, ".runtime/local.json", { LUMIRA_LOCAL_RUNTIME_PIN_VERSION: "1" });
  assert.throws(() => readLocalRuntime(root, { requireConfig: false }), {
    code: "LOCAL_RUNTIME_PIN_MISSING",
  });
});

test("pinned backend port comes from the pin even when the parent environment differs", () => {
  const root = fixture();
  const config = configFor(root);
  delete config.LUMIRA_PORT;
  const pin = createPinMetadata(root, config);
  writeLocalJson(root, ".runtime/local.json", config);
  writeLocalJson(root, ".runtime/local-runtime.json", pin);

  const runtime = readLocalRuntime(root);
  assert.equal(runtime.backendPort, 8090);
  assert.equal(resolveLocalBackendPort(runtime, { LUMIRA_PORT: "8091" }), 8090);
  assert.equal(resolveLocalBackendPort({ pinned: false, config: {} }, { LUMIRA_PORT: "8091" }), 8091);
});

test("pinned web environment keeps only public app settings and strips unknown backend secrets", () => {
  const root = fixture();
  const config = configFor(root, {
    LUMIRA_OFFICE_WORKER_URL: "http://127.0.0.1:18091",
    LUMIRA_OFFICE_WORKER_TOKEN: "fixture-office-secret",
    LUMIRA_METRICS_TOKEN: "config-metrics-token",
    UNKNOWN_CONFIG_SECRET: "config-unknown-secret",
    EXTERNAL_SERVICE_TOKEN: "config-external-token",
  });
  const base = {
    LUMIRA_DB_URL: "jdbc:postgresql://127.0.0.1:5432/wrong_database",
    LUMIRA_DB_PASSWORD: "inherited-secret",
    LUMIRA_BOOTSTRAP_PASSWORD: "inherited-bootstrap-secret",
    LUMIRA_OFFICE_WORKER_TOKEN: "inherited-office-secret",
    LUMIRA_METRICS_TOKEN: "inherited-metrics-token",
    UNKNOWN_CONFIG_SECRET: "inherited-unknown-secret",
    EXTERNAL_SERVICE_TOKEN: "inherited-external-token",
    SPRING_DATASOURCE_URL: "jdbc:postgresql://127.0.0.1:5432/wrong_database",
    SPRING_FLYWAY_URL: "jdbc:postgresql://127.0.0.1:5432/wrong_database",
    SPRING_CONFIG_IMPORT: "optional:file:wrong.properties",
    PSQL_BIN: "private-client-path",
    LUMIRA_PSQL_DOCKER_CONTAINER: "private-container-name",
    JAVA_TOOL_OPTIONS: "-Xmx3g",
  };

  const backend = buildChildEnvironment(base, config, { pinned: true, service: "backend" });
  assert.equal(backend.LUMIRA_DB_URL, config.LUMIRA_DB_URL);
  assert.equal(backend.LUMIRA_DB_PASSWORD, config.LUMIRA_DB_PASSWORD);
  assert.equal(backend.LUMIRA_OFFICE_WORKER_TOKEN, config.LUMIRA_OFFICE_WORKER_TOKEN);
  assert.equal(backend.JAVA_TOOL_OPTIONS, "-Xmx3g");
  assert.equal(Object.hasOwn(backend, "SPRING_DATASOURCE_URL"), false);
  assert.equal(Object.hasOwn(backend, "SPRING_FLYWAY_URL"), false);
  assert.equal(Object.hasOwn(backend, "SPRING_CONFIG_IMPORT"), false);
  assert.equal(Object.hasOwn(backend, "PSQL_BIN"), false);
  assert.equal(Object.hasOwn(backend, "LUMIRA_LOCAL_RUNTIME_PIN_VERSION"), false);
  assert.equal(Object.hasOwn(backend, "LUMIRA_API_ORIGIN"), false);

  const web = buildChildEnvironment(base, config, {
    pinned: true,
    service: "web",
    extra: { LUMIRA_COOKIE_SECURE: config.LUMIRA_COOKIE_SECURE, PORT: "8010", HOST: "127.0.0.1" },
  });
  assert.equal(web.LUMIRA_API_ORIGIN, config.LUMIRA_API_ORIGIN);
  assert.equal(web.LUMIRA_COOKIE_SECURE, config.LUMIRA_COOKIE_SECURE);
  assert.equal(web.PORT, "8010");
  assert.equal(web.HOST, "127.0.0.1");
  for (const key of [
    "LUMIRA_DB_URL", "LUMIRA_DB_PASSWORD", "LUMIRA_BOOTSTRAP_PASSWORD", "LUMIRA_OFFICE_WORKER_TOKEN",
    "LUMIRA_METRICS_TOKEN", "UNKNOWN_CONFIG_SECRET", "EXTERNAL_SERVICE_TOKEN", "SPRING_DATASOURCE_URL",
    "SPRING_FLYWAY_URL", "SPRING_CONFIG_IMPORT", "PSQL_BIN", "LUMIRA_PSQL_DOCKER_CONTAINER",
  ])
    assert.equal(Object.hasOwn(web, key), false);
  assert.deepEqual(environmentDrift(config, web, { service: "web" }), []);
  assert.equal(Object.hasOwn(web, "CUSTOM_PRESERVED_FIELD"), false);
});

test("JVM identity overrides fail while memory options remain valid", () => {
  assert.doesNotThrow(() => assertNoJvmIdentityOverrides({ JAVA_TOOL_OPTIONS: "-Xmx4g -XX:+UseG1GC" }));
  assert.throws(
    () => assertNoJvmIdentityOverrides({ JAVA_TOOL_OPTIONS: "-Xmx4g -Dspring.datasource.url=jdbc:postgresql://elsewhere/db" }),
    { code: "JVM_IDENTITY_OVERRIDE" },
  );
  assert.throws(
    () => assertNoJvmIdentityOverrides({ JDK_JAVA_OPTIONS: '"-Dspring.flyway.url=jdbc:postgresql://elsewhere/db"' }),
    { code: "JVM_IDENTITY_OVERRIDE" },
  );
  assert.throws(
    () => assertNoJvmIdentityOverrides({ JAVA_TOOL_OPTIONS: "-Dserver.address=0.0.0.0" }),
    { code: "JVM_IDENTITY_OVERRIDE" },
  );
  assert.throws(
    () => assertNoJvmIdentityOverrides({ JAVA_TOOL_OPTIONS: "--server.address=0.0.0.0" }),
    { code: "JVM_IDENTITY_OVERRIDE" },
  );
});

test("pinned startup strips inherited server binding overrides and rejects configured ones", () => {
  const root = fixture();
  const config = configFor(root);
  const inherited = buildChildEnvironment({ SERVER_ADDRESS: "0.0.0.0", "server.address": "0.0.0.0" }, config, {
    pinned: true,
    service: "backend",
  });
  assert.equal(Object.hasOwn(inherited, "SERVER_ADDRESS"), false);
  assert.equal(Object.hasOwn(inherited, "server.address"), false);
  assert.throws(() => validatePinnedConfig(root, { ...config, SERVER_ADDRESS: "0.0.0.0" }), {
    code: "SPRING_RUNTIME_OVERRIDE",
  });
  assert.throws(() => buildChildEnvironment({}, { ...config, "server.address": "0.0.0.0" }, {
    pinned: true,
    service: "backend",
  }), { code: "SPRING_RUNTIME_OVERRIDE" });
  assert.throws(() => validatePinnedConfig(root, { ...config, LUMIRA_BIND: "0.0.0.0" }), {
    code: "BACKEND_NOT_LOCAL",
  });
});

test("adoption and status inspect proc identity overrides without copying their values", async (t) => {
  if (process.platform === "win32") {
    t.skip("fake /proc cwd symlink test requires WSL/Linux");
    return;
  }
  const root = fixture();
  const jar = path.join(root, "backend", "target", "platform-server-test.jar");
  mkdirSync(path.dirname(jar), { recursive: true });
  writeFileSync(jar, "isolated fake jar bytes");
  const procRoot = path.join(root, "fake-proc");
  const pid = "41901";
  const procProcess = path.join(procRoot, pid);
  mkdirSync(procProcess, { recursive: true });
  symlinkSync(root, path.join(procProcess, "cwd"), "dir");
  writeFileSync(path.join(procProcess, "cmdline"), `java\0-jar\0${jar}\0`);
  const procEnvironment = [
    "LUMIRA_DB_URL=jdbc:postgresql://127.0.0.1:5432/quota_test",
    "LUMIRA_DB_USER=quota_test_user",
    "LUMIRA_DB_PASSWORD=fixture-db-secret",
    `LUMIRA_FILES_LOCAL_ROOT=${path.join(root, ".runtime", "files")}`,
    `LUMIRA_PLUGINS_PATH=${path.join(root, ".runtime", "plugins")}`,
    "LUMIRA_PORT=8090",
    "LUMIRA_BIND=127.0.0.1",
    "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/other_database",
    'SPRING_APPLICATION_JSON={"spring.datasource.url":"jdbc:postgresql://127.0.0.1:5432/another_database"}',
  ].join("\0") + "\0";
  writeFileSync(path.join(procProcess, "environ"), procEnvironment);

  const inspected = readProcEnvironment(pid, procRoot, { includeJvmOptions: true });
  assert.equal(inspected.LUMIRA_DB_URL.endsWith("/quota_test"), true);
  assert.equal(inspected.SPRING_DATASOURCE_URL.endsWith("/other_database"), true);
  assert.equal(Object.hasOwn(inspected, "SPRING_APPLICATION_JSON"), true);
  assert.throws(
    () => assertNoSpringRuntimeOverrides({ SPRING_APPLICATION_JSON: inspected.SPRING_APPLICATION_JSON }),
    { code: "SPRING_RUNTIME_OVERRIDE" },
  );
  await assert.rejects(
    () => adoptRunningBackend({
      root,
      pid,
      procRoot,
      fetchImpl: async () => ({ status: 200 }),
    }),
    { code: "SPRING_RUNTIME_OVERRIDE" },
  );
});

test("environment drift is reported by key name without serializing configured secrets", () => {
  const root = fixture();
  const config = configFor(root);
  const drift = environmentDrift(config, {
    ...config,
    LUMIRA_DB_PASSWORD: "different-secret",
    LUMIRA_API_ORIGIN: config.LUMIRA_API_ORIGIN,
  });
  assert.deepEqual(drift, ["LUMIRA_DB_PASSWORD"]);
  assert.equal(JSON.stringify(drift).includes("fixture-db-secret"), false);
});

test("web status treats a missing false cookie flag as its default but still requires the API origin", () => {
  const root = fixture();
  const config = configFor(root, { LUMIRA_COOKIE_SECURE: "false" });

  assert.deepEqual(
    environmentDrift(config, { LUMIRA_API_ORIGIN: config.LUMIRA_API_ORIGIN }, { service: "web" }),
    [],
  );
  assert.deepEqual(
    environmentDrift(config, { LUMIRA_COOKIE_SECURE: "false" }, { service: "web" }),
    ["LUMIRA_API_ORIGIN"],
  );
  assert.deepEqual(
    environmentDrift(config, {
      LUMIRA_API_ORIGIN: config.LUMIRA_API_ORIGIN,
      LUMIRA_COOKIE_SECURE: "true",
    }, { service: "web" }),
    ["LUMIRA_COOKIE_SECURE"],
  );
});

test("canonical replacement keeps an owner-only backup of the previous config", () => {
  const root = fixture();
  writeLocalJson(root, ".runtime/local.json", { CUSTOM_PRESERVED_FIELD: "previous" });
  const config = configFor(root);
  const pin = createPinMetadata(root, config);
  const result = backupAndWriteRuntimeConfig(root, config, pin);

  assert.equal(JSON.parse(readFileSync(result.backup, "utf8")).CUSTOM_PRESERVED_FIELD, "previous");
  if (process.platform !== "win32") {
    assert.equal(statSync(result.backup).mode & 0o777, 0o600);
    assert.equal(statSync(path.dirname(result.backup)).mode & 0o777, 0o700);
  }
  assert.equal(readLocalRuntime(root).pinned, true);
});

test("failed config and pin replacement restores both original files", () => {
  const root = fixture();
  const configFile = path.join(root, ".runtime", "local.json");
  const pinFile = path.join(root, ".runtime", "local-runtime.json");
  writeLocalJson(root, ".runtime/local.json", { previous: "config" });
  writeLocalJson(root, ".runtime/local-runtime.json", { previous: "pin" });
  const originalConfig = readFileSync(configFile);
  const originalPin = readFileSync(pinFile);
  const config = configFor(root);
  const invalidPin = {};
  invalidPin.circular = invalidPin;

  assert.throws(() => backupAndWriteRuntimeConfig(root, config, invalidPin), { code: "LOCAL_CONFIG_WRITE_FAILED" });
  assert.deepEqual(readFileSync(configFile), originalConfig);
  assert.deepEqual(readFileSync(pinFile), originalPin);
});

test("pinned backend artifacts reject file and parent symlinks that escape the workspace", t => {
  if (process.platform === "win32") {
    t.skip("physical symlink checks require WSL/Linux");
    return;
  }
  const root = fixture();
  const outsideRoot = mkdtempSync(path.join(os.tmpdir(), "lumira-runtime-artifact-outside-"));
  temporaryRoots.push(outsideRoot);
  const outsideFile = path.join(outsideRoot, "platform-server.jar");
  writeFileSync(outsideFile, "outside artifact");
  symlinkSync(outsideFile, path.join(root, ".runtime", "files", "outside.jar"));
  symlinkSync(outsideRoot, path.join(root, ".runtime", "external"), "dir");

  assert.throws(() => resolveWorkspaceFile(root, ".runtime/files/outside.jar", "backend JAR"), {
    code: "MISSING_RUNTIME_ARTIFACT",
  });
  assert.throws(() => resolveWorkspaceFile(root, ".runtime/external/platform-server.jar", "backend JAR"), {
    code: "MISSING_RUNTIME_ARTIFACT",
  });
});

test("workspace containment accepts two WSL mounts of the same Windows checkout", t => {
  if (process.platform !== "linux") {
    t.skip("requires WSL/Linux drive mounts");
    return;
  }
  const root = path.resolve(process.cwd());
  const aliasPrefix = root.startsWith("/mnt/cmeta/")
    ? "/mnt/c/"
    : root.startsWith("/mnt/c/")
      ? "/mnt/cmeta/"
      : null;
  if (!aliasPrefix) {
    t.skip("checkout is not mounted through the c/cmeta WSL drive aliases");
    return;
  }
  const alternateRoot = root.replace(/^\/mnt\/[^/]+\//, aliasPrefix);
  if (!existsSync(alternateRoot)) {
    t.skip("alternate WSL drive mount is unavailable");
    return;
  }

  const result = resolveWorkspaceDirectory(
    root,
    path.join(alternateRoot, ".runtime", "plugins"),
    "LUMIRA_PLUGINS_PATH",
  );
  assert.equal(result.relativePath, path.join(".runtime", "plugins"));
});

test("runtime mutation lock serializes concurrent commands and leaves interrupted locks fail-closed", async () => {
  const root = fixture();
  const lockFile = path.join(root, ".runtime", "local-runtime.lock");
  let releaseFirst;
  let markFirstAcquired;
  const holdFirst = new Promise(resolve => { releaseFirst = resolve; });
  const firstAcquired = new Promise(resolve => { markFirstAcquired = resolve; });
  const first = withLocalRuntimeLock(root, "capture", async () => {
    markFirstAcquired();
    await holdFirst;
  });
  await firstAcquired;
  await assert.rejects(() => withLocalRuntimeLock(root, "switch", async () => {}), {
    code: "LOCAL_RUNTIME_LOCKED",
  });
  releaseFirst();
  await first;
  assert.equal(existsSync(lockFile), false);

  writeFileSync(lockFile, JSON.stringify({ pid: 999999, operation: "interrupted" }));
  const previousLock = readFileSync(lockFile);
  await assert.rejects(() => withLocalRuntimeLock(root, "refresh-artifact", async () => {}), {
    code: "LOCAL_RUNTIME_LOCKED",
  });
  assert.deepEqual(readFileSync(lockFile), previousLock);
});

test("runtime lock refuses a symlinked .runtime directory without creating files outside the workspace", async (t) => {
  if (process.platform === "win32") {
    t.skip("physical symlink checks require WSL/Linux");
    return;
  }
  const root = mkdtempSync(path.join(os.tmpdir(), "lumira-runtime-lock-symlink-"));
  temporaryRoots.push(root);
  const outsideRoot = mkdtempSync(path.join(os.tmpdir(), "lumira-runtime-lock-outside-"));
  temporaryRoots.push(outsideRoot);
  const outsideRuntime = path.join(outsideRoot, "runtime-created-by-escape");
  symlinkSync(outsideRuntime, path.join(root, ".runtime"), "dir");

  await assert.rejects(() => withLocalRuntimeLock(root, "start", async () => {}), {
    code: "RUNTIME_PATH_OUTSIDE_WORKSPACE",
  });
  assert.equal(existsSync(outsideRuntime), false);
});
