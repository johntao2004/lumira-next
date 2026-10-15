import { createHash, randomBytes } from "node:crypto";
import {
  chmodSync,
  constants,
  closeSync,
  copyFileSync,
  existsSync,
  fsyncSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  readlinkSync,
  realpathSync,
  renameSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const LOCAL_CONFIG_RELATIVE_PATH = ".runtime/local.json";
export const LOCAL_PIN_RELATIVE_PATH = ".runtime/local-runtime.json";
export const LOCAL_INVENTORY_RELATIVE_PATH = ".runtime/local-plugin-inventory.json";

export const PINNED_CONFIG_KEYS = Object.freeze([
  "LUMIRA_DB_URL",
  "LUMIRA_DB_USER",
  "LUMIRA_DB_PASSWORD",
  "LUMIRA_BOOTSTRAP_PASSWORD",
  "LUMIRA_BOOTSTRAP_USERNAME",
  "LUMIRA_SECRET_KEYS",
  "LUMIRA_SECRET_ACTIVE_KEY",
  "LUMIRA_FILES_LOCAL_ROOT",
  "LUMIRA_PLUGINS_PATH",
  "LUMIRA_PORT",
  "LUMIRA_BIND",
  "LUMIRA_COOKIE_SECURE",
  "LUMIRA_BACKEND_BASE_URL",
  "LUMIRA_API_ORIGIN",
  "LUMIRA_OFFICE_WORKER_URL",
  "LUMIRA_OFFICE_WORKER_TOKEN",
  "LUMIRA_LOCAL_RUNTIME_PIN_VERSION",
  "PSQL_BIN",
  "LUMIRA_PSQL_DOCKER_CONTAINER",
  "SPRING_FLYWAY_ENABLED",
  "SPRING_FLYWAY_VALIDATE_ON_MIGRATE",
  "SPRING_FLYWAY_BASELINE_ON_MIGRATE",
  "SPRING_FLYWAY_BASELINE_VERSION",
]);

const REQUIRED_PINNED_CONFIG_KEYS = Object.freeze([
  "LUMIRA_DB_URL",
  "LUMIRA_DB_USER",
  "LUMIRA_DB_PASSWORD",
  "LUMIRA_FILES_LOCAL_ROOT",
  "LUMIRA_PLUGINS_PATH",
  "LUMIRA_API_ORIGIN",
]);

const SECRET_CONFIG_KEYS = new Set([
  "LUMIRA_DB_PASSWORD",
  "LUMIRA_BOOTSTRAP_PASSWORD",
  "LUMIRA_SECRET_KEYS",
  "LUMIRA_OFFICE_WORKER_TOKEN",
]);
const WEB_PUBLIC_ENV_KEYS = new Set(["LUMIRA_API_ORIGIN", "LUMIRA_COOKIE_SECURE"]);
const WEB_SECRET_ENV_KEY = /(?:^|_)(?:SECRET|SECRETS|PASSWORD|PASSWD|TOKEN|CREDENTIALS?|API_KEY|ACCESS_KEY|PRIVATE_KEY)(?:_|$)/i;

const ADOPTABLE_ENV_KEYS = new Set(PINNED_CONFIG_KEYS);
const JVM_OPTION_ENV_KEYS = new Set(["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]);
const CONFIG_ONLY_KEYS = new Set([
  "LUMIRA_API_ORIGIN",
  "LUMIRA_LOCAL_RUNTIME_PIN_VERSION",
  "PSQL_BIN",
  "LUMIRA_PSQL_DOCKER_CONTAINER",
]);
const SPRING_ENV_OVERRIDES = Object.freeze([
  "SPRING_DATASOURCE_URL",
  "SPRING_DATASOURCE_USERNAME",
  "SPRING_DATASOURCE_PASSWORD",
  "SPRING_DATASOURCE_HIKARI_JDBC_URL",
  "SPRING_CONFIG_LOCATION",
  "SPRING_CONFIG_ADDITIONAL_LOCATION",
  "SPRING_CONFIG_IMPORT",
  "SPRING_APPLICATION_JSON",
  "SPRING_FLYWAY_LOCATIONS",
  "SPRING_FLYWAY_URL",
  "SPRING_FLYWAY_USER",
  "SPRING_FLYWAY_PASSWORD",
  "SPRING_FLYWAY_SCHEMAS",
  "SPRING_FLYWAY_DEFAULT_SCHEMA",
  "SERVER_PORT",
  "server.port",
  "SERVER_ADDRESS",
  "server.address",
]);

const FORBIDDEN_PINNED_OVERRIDES = new Set([
  "SPRING_DATASOURCE_URL",
  "SPRING_DATASOURCE_USERNAME",
  "SPRING_DATASOURCE_PASSWORD",
  "SPRING_DATASOURCE_HIKARI_JDBC_URL",
  "SPRING_APPLICATION_JSON",
  "SPRING_CONFIG_IMPORT",
  "spring.config.import",
  "SPRING_FLYWAY_URL",
  "SPRING_FLYWAY_USER",
  "SPRING_FLYWAY_PASSWORD",
  "SPRING_FLYWAY_SCHEMAS",
  "SPRING_FLYWAY_DEFAULT_SCHEMA",
  "spring.datasource.url",
  "spring.datasource.username",
  "spring.datasource.password",
  "spring.datasource.hikari.jdbc-url",
  "spring.application.json",
  "SERVER_ADDRESS",
  "server.address",
]);

export class LocalRuntimeConfigError extends Error {
  constructor(message, code = "LOCAL_RUNTIME_CONFIG_ERROR") {
    super(message);
    this.name = "LocalRuntimeConfigError";
    this.code = code;
  }
}

export function projectRootFromScript(scriptUrl = import.meta.url) {
  return path.resolve(path.dirname(fileURLToPath(scriptUrl)), "..");
}

export function runtimeConfigPaths(root) {
  return {
    runtimeDir: path.join(root, ".runtime"),
    configFile: path.join(root, LOCAL_CONFIG_RELATIVE_PATH),
    pinFile: path.join(root, LOCAL_PIN_RELATIVE_PATH),
    inventoryFile: path.join(root, LOCAL_INVENTORY_RELATIVE_PATH),
    processFile: path.join(root, ".runtime", "processes.json"),
  };
}

export function stableConfigProjection(config) {
  const projection = {};
  for (const key of [...PINNED_CONFIG_KEYS].sort()) {
    projection[key] = Object.hasOwn(config, key) ? String(config[key]) : null;
  }
  return projection;
}

export function configFingerprint(config) {
  return createHash("sha256")
    .update(JSON.stringify(stableConfigProjection(config)))
    .digest("hex");
}

export function describeDatabaseTarget(jdbcUrl, username = "") {
  let databaseUrl;
  try {
    const value = String(jdbcUrl ?? "");
    if (!value.startsWith("jdbc:postgresql://")) throw new Error();
    databaseUrl = new URL(value.slice("jdbc:".length));
  } catch {
    throw new LocalRuntimeConfigError(
      "Pinned local runtime requires a PostgreSQL JDBC URL.",
      "INVALID_DATABASE_URL",
    );
  }
  const database = decodeURIComponent(databaseUrl.pathname.replace(/^\//, ""));
  if (!database || !databaseUrl.hostname)
    throw new LocalRuntimeConfigError(
      "Pinned local runtime requires a database host and name.",
      "INVALID_DATABASE_URL",
    );
  return {
    host: databaseUrl.hostname,
    port: databaseUrl.port || "5432",
    database,
    user: String(username ?? ""),
  };
}

function directoryStatIdentity(value) {
  const stat = statSync(value);
  if (!stat.isDirectory())
    throw new LocalRuntimeConfigError(
      "Pinned workspace root is not a directory.",
      "INVALID_WORKSPACE_ROOT",
    );
  return { inode: String(stat.ino), name: path.basename(path.resolve(value)) };
}

export function workspaceIdentity(root) {
  const identity = directoryStatIdentity(root);
  return createHash("sha256")
    .update(`${identity.name}:${identity.inode}`)
    .digest("hex");
}

function samePhysicalDirectory(left, right) {
  try {
    const a = realpathSync(left);
    const b = realpathSync(right);
    if (process.platform === "win32" ? a.toLowerCase() === b.toLowerCase() : a === b)
      return true;
    if (process.platform !== "linux") return false;
    const leftStat = statSync(a);
    const rightStat = statSync(b);
    return leftStat.isDirectory() && rightStat.isDirectory()
      && leftStat.ino !== 0 && String(leftStat.ino) === String(rightStat.ino)
      && path.basename(a).toLowerCase() === path.basename(b).toLowerCase()
      && leftStat.size === rightStat.size && leftStat.mtimeMs === rightStat.mtimeMs;
  } catch {
    return false;
  }
}

function physicalRelativeParts(root, target) {
  try {
    const rootPath = realpathSync(root);
    const targetPath = realpathSync(target);
    if (!statSync(targetPath).isDirectory()) return null;
    const relative = path.relative(rootPath, targetPath);
    if (relative === "") return [];
    if (
      relative !== ".." &&
      !relative.startsWith(`..${path.sep}`) &&
      !path.isAbsolute(relative)
    )
      return relative.split(path.sep).filter(Boolean);

    // WSL can mount the same Windows directory through `/mnt/c` and a metadata
    // mount such as `/mnt/cmeta`. Their realpath strings differ, so find the
    // resolved target's physical ancestor by directory identity. Walking the
    // resolved target path (rather than the lexical input) keeps symlink escapes
    // outside the workspace rejected.
    const relativeParts = [];
    let ancestor = targetPath;
    for (;;) {
      if (samePhysicalDirectory(rootPath, ancestor)) return relativeParts.reverse();
      const parent = path.dirname(ancestor);
      if (parent === ancestor) return null;
      relativeParts.push(path.basename(ancestor));
      ancestor = parent;
    }
  } catch {
    return null;
  }
}

export function resolveWorkspaceDirectory(root, configuredPath, label) {
  const raw = String(configuredPath ?? "").trim();
  if (!raw)
    throw new LocalRuntimeConfigError(
      `Pinned local runtime is missing ${label}.`,
      "MISSING_RUNTIME_PATH",
    );
  const candidate = path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(root, raw);
  const parts = physicalRelativeParts(root, candidate);
  if (!parts)
    throw new LocalRuntimeConfigError(
      `Pinned ${label} must resolve to an existing directory inside this workspace.`,
      "RUNTIME_PATH_OUTSIDE_WORKSPACE",
    );
  return { path: candidate, relativePath: parts.join(path.sep) || "." };
}

export function resolveWorkspaceFile(root, configuredPath, label = "artifact") {
  const raw = String(configuredPath ?? "").trim();
  if (!raw || path.isAbsolute(raw) || raw.split(/[\\/]/).includes(".."))
    throw new LocalRuntimeConfigError(
      `Pinned ${label} must be a relative workspace path.`,
      "INVALID_RUNTIME_ARTIFACT_PATH",
    );
  const candidate = path.resolve(root, raw);
  const relative = path.relative(path.resolve(root), candidate);
  if (!relative || relative.startsWith(`..${path.sep}`) || relative === "..")
    throw new LocalRuntimeConfigError(
      `Pinned ${label} must stay inside this workspace.`,
      "RUNTIME_ARTIFACT_OUTSIDE_WORKSPACE",
    );
  try {
    const parentParts = physicalRelativeParts(root, path.dirname(candidate));
    const fileStat = lstatSync(candidate);
    if (!parentParts || !fileStat.isFile() || fileStat.isSymbolicLink()) throw new Error();
  } catch {
    throw new LocalRuntimeConfigError(
      `Pinned ${label} is missing or is not a file.`,
      "MISSING_RUNTIME_ARTIFACT",
    );
  }
  return candidate;
}

export function validatePinnedConfig(root, config) {
  if (!config || typeof config !== "object" || Array.isArray(config))
    throw new LocalRuntimeConfigError(
      "Local runtime configuration must be a JSON object.",
      "INVALID_LOCAL_CONFIG",
    );
  assertNoJvmIdentityOverrides(config);
  assertNoSpringRuntimeOverrides(config);
  for (const key of REQUIRED_PINNED_CONFIG_KEYS) {
    if (typeof config[key] !== "string" || config[key].trim() === "")
      throw new LocalRuntimeConfigError(
        `Pinned local runtime is missing ${key}.`,
        "MISSING_LOCAL_CONFIG_VALUE",
      );
  }
  const override = Object.keys(config).find((key) => {
    const normalized = key.toLowerCase();
    const datasource = normalized.startsWith("spring.datasource.") || normalized.startsWith("spring_datasource_");
    const configRedirect = FORBIDDEN_PINNED_OVERRIDES.has(key)
      || [
        "spring.application.json", "spring_application_json",
        "spring.config.location", "spring.config.additional-location", "spring.config.import",
        "spring_config_location", "spring_config_additional_location", "spring_config_import",
        "spring.flyway.locations", "spring_flyway_locations",
        "spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
        "spring.flyway.schemas", "spring.flyway.default-schema",
        "spring_flyway_url", "spring_flyway_user", "spring_flyway_password",
        "spring_flyway_schemas", "spring_flyway_default_schema",
        "server.port", "server_port", "server.address", "server_address",
      ].includes(normalized);
    return (datasource || configRedirect) && String(config[key] ?? "").trim();
  });
  if (override)
    throw new LocalRuntimeConfigError(
      `Pinned local runtime contains an alternate Spring datasource override (${override}); start refused.`,
      "PINNED_DATASOURCE_OVERRIDE",
    );
  if (
    Object.hasOwn(config, "SPRING_FLYWAY_LOCATIONS") &&
    String(config.SPRING_FLYWAY_LOCATIONS).trim() !== "classpath:db/migration"
  )
    throw new LocalRuntimeConfigError(
      "Pinned local runtime must use classpath:db/migration for Flyway.",
      "PINNED_FLYWAY_LOCATION_OVERRIDE",
    );
  if (
    Object.hasOwn(config, "SPRING_FLYWAY_BASELINE_VERSION") &&
    String(config.SPRING_FLYWAY_BASELINE_VERSION) !== "52"
  )
    throw new LocalRuntimeConfigError(
      "Pinned local runtime must use the B52 Flyway baseline version.",
      "INVALID_FLYWAY_CONFIG",
    );
  const hasPsqlBinary = typeof config.PSQL_BIN === "string" && config.PSQL_BIN.trim() !== "";
  const hasPsqlContainer = typeof config.LUMIRA_PSQL_DOCKER_CONTAINER === "string" && config.LUMIRA_PSQL_DOCKER_CONTAINER.trim() !== "";
  if (hasPsqlBinary === hasPsqlContainer)
    throw new LocalRuntimeConfigError(
      "Pinned local runtime requires exactly one canonical PSQL_BIN or LUMIRA_PSQL_DOCKER_CONTAINER setting.",
      "INVALID_PSQL_TOOL_CONFIG",
    );
  const database = describeDatabaseTarget(config.LUMIRA_DB_URL, config.LUMIRA_DB_USER);
  const files = resolveWorkspaceDirectory(root, config.LUMIRA_FILES_LOCAL_ROOT, "LUMIRA_FILES_LOCAL_ROOT");
  const plugins = resolveWorkspaceDirectory(root, config.LUMIRA_PLUGINS_PATH, "LUMIRA_PLUGINS_PATH");
  const officeUrl = String(config.LUMIRA_OFFICE_WORKER_URL ?? "").trim();
  const officeToken = String(config.LUMIRA_OFFICE_WORKER_TOKEN ?? "").trim();
  if (Boolean(officeUrl) !== Boolean(officeToken))
    throw new LocalRuntimeConfigError(
      "Pinned Office worker URL and token must either both be set or both be unset.",
      "INVALID_OFFICE_WORKER_CONFIG",
    );
  const backendPort = parsePort(config.LUMIRA_PORT ?? "8090", "LUMIRA_PORT");
  const flywayEnabled = normalizedBoolean(config.SPRING_FLYWAY_ENABLED ?? "true");
  const validateOnMigrate = normalizedBoolean(config.SPRING_FLYWAY_VALIDATE_ON_MIGRATE ?? "true");
  const baselineOnMigrate = normalizedBoolean(config.SPRING_FLYWAY_BASELINE_ON_MIGRATE ?? "false");
  if (!flywayEnabled || !validateOnMigrate || baselineOnMigrate)
    throw new LocalRuntimeConfigError(
      "Pinned local runtime requires Flyway enabled, validation enabled, and baseline-on-migrate disabled.",
      "INVALID_FLYWAY_CONFIG",
    );
  const bindAddress = String(config.LUMIRA_BIND ?? "127.0.0.1").toLowerCase();
  if (!new Set(["127.0.0.1", "localhost", "::1"]).has(bindAddress))
    throw new LocalRuntimeConfigError("Pinned local runtime must bind the backend to loopback.", "BACKEND_NOT_LOCAL");
  if (config.LUMIRA_API_ORIGIN) {
    let apiOrigin;
    try {
      apiOrigin = new URL(String(config.LUMIRA_API_ORIGIN));
    } catch {
      throw new LocalRuntimeConfigError("Pinned LUMIRA_API_ORIGIN is invalid.", "INVALID_API_ORIGIN");
    }
    if (
      apiOrigin.protocol !== "http:" ||
      !new Set(["127.0.0.1", "localhost", "[::1]", "::1"]).has(apiOrigin.hostname) ||
      Number(apiOrigin.port || 80) !== backendPort ||
      apiOrigin.pathname !== "/" ||
      apiOrigin.search ||
      apiOrigin.hash
    )
      throw new LocalRuntimeConfigError(
        "Pinned LUMIRA_API_ORIGIN must resolve to the configured local backend port.",
        "API_ORIGIN_DRIFT",
      );
  }
  return { database, files, plugins, backendPort };
}

function normalizedBoolean(value) {
  return String(value).toLowerCase() === "true";
}

export function parsePort(value, label = "port") {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new LocalRuntimeConfigError(`${label} must be a valid TCP port.`, "INVALID_PORT");
  return port;
}

export function resolveLocalBackendPort(runtime, environment = process.env) {
  const value = runtime?.pinned
    ? runtime.backendPort
    : runtime?.config?.LUMIRA_PORT ?? environment.LUMIRA_PORT ?? "8090";
  return parsePort(value, "LUMIRA_PORT");
}

function readJson(file, { optional = false } = {}) {
  if (!existsSync(file)) {
    if (optional) return null;
    throw new LocalRuntimeConfigError(
      "The canonical local runtime configuration is missing; run the normal local database preparation first.",
      "MISSING_LOCAL_CONFIG",
    );
  }
  try {
    return JSON.parse(readFileSync(file, "utf8"));
  } catch {
    throw new LocalRuntimeConfigError(
      "A local runtime JSON file is invalid; no configuration was changed.",
      "INVALID_LOCAL_CONFIG_JSON",
    );
  }
}

export function createPinMetadata(root, config, { artifact = null, now = new Date() } = {}) {
  const validated = validatePinnedConfig(root, config);
  let pinnedArtifact;
  if (artifact) {
    const relativePath = String(artifact.path ?? "");
    const file = resolveWorkspaceFile(root, relativePath, "backend JAR");
    const sha256 = sha256File(file);
    if (artifact.sha256 && artifact.sha256 !== sha256)
      throw new LocalRuntimeConfigError(
        "The selected backend JAR checksum changed; pinning was cancelled.",
        "BACKEND_JAR_CHECKSUM_MISMATCH",
      );
    if (!/\.jar$/i.test(file))
      throw new LocalRuntimeConfigError(
        "The selected backend artifact is not a JAR.",
        "INVALID_BACKEND_ARTIFACT",
      );
    pinnedArtifact = { path: relativePath.split(path.sep).join("/"), sha256 };
  }
  return {
    version: 1,
    createdAt: now.toISOString(),
    workspaceIdentity: workspaceIdentity(root),
    configSha256: configFingerprint(config),
    database: validated.database,
    paths: {
      files: validated.files.relativePath.split(path.sep).join("/"),
      plugins: validated.plugins.relativePath.split(path.sep).join("/"),
    },
    ports: { backend: validated.backendPort, frontend: 8010 },
    ...(pinnedArtifact ? { artifact: pinnedArtifact } : {}),
  };
}

export function validatePinMetadata(root, config, pin) {
  if (!pin || pin.version !== 1 || typeof pin.workspaceIdentity !== "string")
    throw new LocalRuntimeConfigError(
      "Local runtime pin metadata is invalid; start refused.",
      "INVALID_LOCAL_RUNTIME_PIN",
    );
  if (config.LUMIRA_LOCAL_RUNTIME_PIN_VERSION !== "1")
    throw new LocalRuntimeConfigError(
      "Canonical config does not carry the expected local runtime pin marker; start refused.",
      "LOCAL_RUNTIME_PIN_MARKER_MISSING",
    );
  if (workspaceIdentity(root) !== pin.workspaceIdentity)
    throw new LocalRuntimeConfigError(
      "Local runtime pin belongs to a different workspace identity; start refused.",
      "LOCAL_RUNTIME_WORKSPACE_DRIFT",
    );
  if (pin.configSha256 !== configFingerprint(config))
    throw new LocalRuntimeConfigError(
      "Canonical local runtime configuration drifted from its pin; re-adopt or explicitly switch the runtime before starting.",
      "LOCAL_RUNTIME_CONFIG_DRIFT",
    );
  const validated = validatePinnedConfig(root, config);
  if (
    pin.database?.host !== validated.database.host ||
    pin.database?.port !== validated.database.port ||
    pin.database?.database !== validated.database.database ||
    pin.database?.user !== validated.database.user ||
    pin.paths?.files !== validated.files.relativePath.split(path.sep).join("/") ||
    pin.paths?.plugins !== validated.plugins.relativePath.split(path.sep).join("/") ||
    Number(pin.ports?.backend) !== validated.backendPort
  )
    throw new LocalRuntimeConfigError(
      "Pinned database or runtime path identity drifted; start refused.",
      "LOCAL_RUNTIME_IDENTITY_DRIFT",
    );
  if (pin.artifact) {
    const artifactPath = resolveWorkspaceFile(root, pin.artifact.path, "backend JAR");
    if (sha256File(artifactPath) !== pin.artifact.sha256)
      throw new LocalRuntimeConfigError(
        "Pinned backend JAR checksum changed; start refused.",
        "BACKEND_JAR_CHECKSUM_MISMATCH",
      );
  }
  return { config, pin, ...validated, pinned: true };
}

export function readLocalRuntime(root, { requireConfig = true } = {}) {
  const paths = runtimeConfigPaths(root);
  const config = readJson(paths.configFile, { optional: !requireConfig }) ?? {};
  const pin = readJson(paths.pinFile, { optional: true });
  if (!pin) {
    if (config.LUMIRA_LOCAL_RUNTIME_PIN_VERSION !== undefined)
      throw new LocalRuntimeConfigError(
        "Canonical config records a local runtime pin, but pin metadata is missing; start refused.",
        "LOCAL_RUNTIME_PIN_MISSING",
      );
    return { ...paths, config, pin: null, pinned: false };
  }
  return { ...paths, ...validatePinMetadata(root, config, pin), configFile: paths.configFile, pinFile: paths.pinFile, processFile: paths.processFile, inventoryFile: paths.inventoryFile, runtimeDir: paths.runtimeDir };
}

export function sha256File(file) {
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

export function readProcEnvironment(pid, procRoot = "/proc", { includeJvmOptions = false } = {}) {
  if (!Number.isInteger(Number(pid)) || Number(pid) <= 0)
    throw new LocalRuntimeConfigError("A valid backend PID is required.", "INVALID_PID");
  const file = path.join(procRoot, String(pid), "environ");
  let bytes;
  try {
    bytes = readFileSync(file);
  } catch {
    throw new LocalRuntimeConfigError(
      "The selected process environment is unavailable; adoption requires a live WSL/Linux backend process.",
      "PROC_ENV_UNAVAILABLE",
    );
  }
  const values = {};
  for (const entry of bytes.toString("utf8").split("\0")) {
    const split = entry.indexOf("=");
    if (split < 1) continue;
    const key = entry.slice(0, split);
    if (
      ADOPTABLE_ENV_KEYS.has(key) ||
      (includeJvmOptions && JVM_OPTION_ENV_KEYS.has(key)) ||
      isSpringIdentityOverrideKey(key)
    )
      values[key] = entry.slice(split + 1);
  }
  return values;
}

export function readProcCommandLine(pid, procRoot = "/proc") {
  return readProcText(pid, "cmdline", procRoot);
}

function readProcText(pid, file, procRoot = "/proc") {
  try {
    return readFileSync(path.join(procRoot, String(pid), file), "utf8");
  } catch {
    throw new LocalRuntimeConfigError(
      "The selected process identity is unavailable; adoption requires a live WSL/Linux backend process.",
      "PROC_IDENTITY_UNAVAILABLE",
    );
  }
}

function readProcCwd(pid, procRoot = "/proc") {
  try {
    return readlinkSync(path.join(procRoot, String(pid), "cwd"));
  } catch {
    throw new LocalRuntimeConfigError(
      "The selected process working directory is unavailable; adoption requires a live backend process.",
      "PROC_IDENTITY_UNAVAILABLE",
    );
  }
}

export function findJarArgument(commandLine) {
  const args = Buffer.isBuffer(commandLine)
    ? commandLine.toString("utf8").split("\0").filter(Boolean)
    : String(commandLine ?? "").split("\0").filter(Boolean);
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === "-jar" && args[index + 1]) return args[index + 1];
  }
  return args.find((value) => /\.jar$/i.test(value)) ?? null;
}

export function relativeWorkspacePath(root, candidate) {
  const absoluteCandidate = path.resolve(candidate);
  const parentParts = physicalRelativeParts(root, path.dirname(absoluteCandidate));
  let parts = parentParts ? [...parentParts, path.basename(absoluteCandidate)] : null;
  try {
    const fileStat = lstatSync(absoluteCandidate);
    if (!fileStat.isFile() || fileStat.isSymbolicLink()) parts = null;
  } catch {
    parts = null;
  }
  if (!parts)
    throw new LocalRuntimeConfigError(
      "The backend JAR must be inside this workspace.",
      "BACKEND_JAR_OUTSIDE_WORKSPACE",
    );
  return parts.join(path.sep);
}

export function buildAdoptedRuntimeConfig(existingConfig, processEnvironment, { managedOfficeWorker = false, psqlDockerContainer } = {}) {
  const config = { ...(existingConfig ?? {}) };
  for (const key of ADOPTABLE_ENV_KEYS) {
    if (Object.hasOwn(processEnvironment, key)) config[key] = processEnvironment[key];
  }
  if (managedOfficeWorker) {
    delete config.LUMIRA_OFFICE_WORKER_URL;
    delete config.LUMIRA_OFFICE_WORKER_TOKEN;
  }
  if (!Object.hasOwn(config, "LUMIRA_PORT")) config.LUMIRA_PORT = "8090";
  if (!Object.hasOwn(config, "LUMIRA_BOOTSTRAP_USERNAME")) config.LUMIRA_BOOTSTRAP_USERNAME = "admin";
  if (!Object.hasOwn(config, "SPRING_FLYWAY_ENABLED")) config.SPRING_FLYWAY_ENABLED = "true";
  if (!Object.hasOwn(config, "SPRING_FLYWAY_VALIDATE_ON_MIGRATE")) config.SPRING_FLYWAY_VALIDATE_ON_MIGRATE = "true";
  if (!Object.hasOwn(config, "SPRING_FLYWAY_BASELINE_ON_MIGRATE")) config.SPRING_FLYWAY_BASELINE_ON_MIGRATE = "false";
  if (!Object.hasOwn(config, "SPRING_FLYWAY_BASELINE_VERSION")) config.SPRING_FLYWAY_BASELINE_VERSION = "52";
  config.LUMIRA_LOCAL_RUNTIME_PIN_VERSION = "1";
  config.LUMIRA_API_ORIGIN = `http://127.0.0.1:${config.LUMIRA_PORT}`;
  const selectedDockerContainer = psqlDockerContainer ?? processEnvironment.LUMIRA_PSQL_DOCKER_CONTAINER;
  if (selectedDockerContainer) {
    config.LUMIRA_PSQL_DOCKER_CONTAINER = String(selectedDockerContainer);
    delete config.PSQL_BIN;
  } else if (Object.hasOwn(processEnvironment, "PSQL_BIN")) {
    config.PSQL_BIN = processEnvironment.PSQL_BIN;
    delete config.LUMIRA_PSQL_DOCKER_CONTAINER;
  } else if (config.LUMIRA_PSQL_DOCKER_CONTAINER) delete config.PSQL_BIN;
  else {
    delete config.LUMIRA_PSQL_DOCKER_CONTAINER;
    if (!config.PSQL_BIN) config.PSQL_BIN = "psql";
  }
  return config;
}

export function sanitizeConfigForDisplay(config) {
  const sanitized = {};
  for (const [key, value] of Object.entries(config ?? {})) {
    sanitized[key] = SECRET_CONFIG_KEYS.has(key) ? "[redacted]" : value;
  }
  return sanitized;
}

export function environmentDrift(config, processEnvironment, { service = "backend" } = {}) {
  const drift = [];
  const keys = service === "web" ? WEB_PUBLIC_ENV_KEYS : PINNED_CONFIG_KEYS;
  for (const key of keys) {
    if (service !== "web" && CONFIG_ONLY_KEYS.has(key)) continue;
    if (!Object.hasOwn(config, key) && !(service === "web" && key === "LUMIRA_COOKIE_SECURE")) continue;
    if (key === "LUMIRA_BOOTSTRAP_USERNAME" && config[key] === "admin" && !Object.hasOwn(processEnvironment, key))
      continue;
    if (key === "SPRING_FLYWAY_BASELINE_VERSION" && config[key] === "52" && !Object.hasOwn(processEnvironment, key))
      continue;
    const expected = service === "web" && key === "LUMIRA_COOKIE_SECURE"
      ? config[key] ?? "false"
      : config[key];
    const missingFalseWebCookieFlag =
      service === "web" &&
      key === "LUMIRA_COOKIE_SECURE" &&
      expected === "false" &&
      !Object.hasOwn(processEnvironment, key);
    const actual = missingFalseWebCookieFlag ? "false" : processEnvironment[key] ?? "";
    if (String(actual) !== String(expected)) drift.push(key);
  }
  return drift;
}

export function buildChildEnvironment(baseEnvironment, config, { pinned, service, extra = {} } = {}) {
  const environment = { ...baseEnvironment };
  if (pinned) {
    assertNoJvmIdentityOverrides(baseEnvironment);
    for (const key of PINNED_CONFIG_KEYS) delete environment[key];
    for (const key of SPRING_ENV_OVERRIDES) delete environment[key];
    for (const key of Object.keys(environment)) {
      const normalized = key.toLowerCase();
      if (
        normalized.startsWith("spring.datasource.") || normalized.startsWith("spring_datasource_") ||
        normalized.startsWith("spring.flyway.") || normalized.startsWith("spring_flyway_") ||
        normalized.startsWith("spring.config.") || normalized.startsWith("spring_config_") ||
        normalized === "spring.application.json" || normalized === "spring_application_json" ||
        normalized === "server.port" || normalized === "server_port" ||
        normalized === "server.address" || normalized === "server_address"
      )
        delete environment[key];
    }
  }
  if (pinned && service !== "backend")
    Object.assign(environment, { LUMIRA_API_ORIGIN: config.LUMIRA_API_ORIGIN, ...extra });
  else Object.assign(environment, config, extra);
  if (pinned) {
    assertNoJvmIdentityOverrides(environment);
    assertNoSpringRuntimeOverrides(environment);
    for (const key of CONFIG_ONLY_KEYS) {
      if (key === "LUMIRA_API_ORIGIN" && service !== "backend") continue;
      delete environment[key];
    }
  }
  if (service !== "backend") {
    for (const key of PINNED_CONFIG_KEYS) {
      if (key === "LUMIRA_API_ORIGIN") continue;
      delete environment[key];
    }
  } else if (pinned) {
    // This origin is read by the frontend proxy and is not a backend setting.
    delete environment.LUMIRA_API_ORIGIN;
  }
  if (pinned && service === "web") {
    for (const key of Object.keys(environment)) {
      const normalized = key.toLowerCase();
      if (
        (normalized.startsWith("lumira_") && !WEB_PUBLIC_ENV_KEYS.has(key)) ||
        normalized.startsWith("spring_") || normalized.startsWith("spring.") ||
        key === "PSQL_BIN" || key === "LUMIRA_PSQL_DOCKER_CONTAINER" ||
        WEB_SECRET_ENV_KEY.test(key)
      )
        delete environment[key];
    }
    environment.LUMIRA_API_ORIGIN = config.LUMIRA_API_ORIGIN;
    environment.LUMIRA_COOKIE_SECURE = config.LUMIRA_COOKIE_SECURE ?? "false";
  }
  return environment;
}

export function assertNoSpringRuntimeOverrides(environment) {
  const allowedFlyway = new Set([
    "spring_flyway_enabled",
    "spring_flyway_validate_on_migrate",
    "spring_flyway_baseline_on_migrate",
    "spring_flyway_baseline_version",
  ]);
  const override = Object.keys(environment).find((key) => {
    const normalized = key.toLowerCase();
    if (normalized.startsWith("spring.datasource.") || normalized.startsWith("spring_datasource_")) return true;
    if (normalized.startsWith("spring.config.") || normalized.startsWith("spring_config_")) return true;
    if (normalized === "spring.application.json" || normalized === "spring_application_json") return true;
    if (normalized.startsWith("server.port") || normalized === "server_port") return true;
    if (normalized === "server.address" || normalized === "server_address") return true;
    if (normalized.startsWith("spring.flyway.") || normalized.startsWith("spring_flyway_"))
      return !allowedFlyway.has(normalized);
    return false;
  });
  if (override && String(environment[override] ?? "").trim())
    throw new LocalRuntimeConfigError(
      `Pinned startup refuses an alternate Spring runtime override (${override}).`,
      "SPRING_RUNTIME_OVERRIDE",
    );
}

function isSpringIdentityOverrideKey(key) {
  const normalized = String(key).toLowerCase();
  return normalized.startsWith("spring.datasource.") || normalized.startsWith("spring_datasource_") ||
    normalized.startsWith("spring.config.") || normalized.startsWith("spring_config_") ||
    normalized.startsWith("spring.flyway.") || normalized.startsWith("spring_flyway_") ||
    normalized === "spring.application.json" || normalized === "spring_application_json" ||
    normalized === "server.port" || normalized === "server_port" ||
    normalized === "server.address" || normalized === "server_address";
}

export function assertNoJvmIdentityOverrides(environment) {
  const sources = ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"];
  const argumentPattern = /(?:^|[\s"'])-D(?:["']?)([^=\s"']+)(?:["']?)(?:=|\s|$)/g;
  const appArgumentPattern = /(?:^|[\s"'])--(?:spring\.datasource\.[^=\s"']+|spring\.application\.json|spring\.config\.(?:location|additional-location|import)|spring\.flyway\.(?:locations|url|user|password|schemas|default-schema)|server\.(?:port|address))(?:=|\s|["']|$)/i;
  const forbidden = /^(?:spring\.datasource\..+|spring\.application\.json|spring\.config\.(?:location|additional-location|import)|spring\.flyway\.(?:locations|url|user|password|schemas|default-schema)|server\.(?:port|address))$/i;
  const source = sources.find((key) => {
    const value = String(environment[key] ?? "");
    argumentPattern.lastIndex = 0;
    for (const match of value.matchAll(argumentPattern)) if (forbidden.test(match[1])) return true;
    return appArgumentPattern.test(value);
  }) ?? (appArgumentPattern.test(String(environment.JAVA_COMMAND_LINE ?? "")) ? "JAVA_COMMAND_LINE" : undefined);
  if (source)
    throw new LocalRuntimeConfigError(
      `Pinned startup refuses a datasource or migration override in ${source}.`,
      "JVM_IDENTITY_OVERRIDE",
    );
}

function writeJsonAtomically(file, value) {
  mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const temporary = `${file}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`;
  try {
    writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, {
      mode: 0o600,
      flag: "wx",
    });
    chmodSync(temporary, 0o600);
    renameSync(temporary, file);
    chmodSync(file, 0o600);
  } catch (error) {
    throw new LocalRuntimeConfigError(
      "Unable to write private local runtime configuration; existing files were retained.",
      "LOCAL_CONFIG_WRITE_FAILED",
    );
  }
}

function createProtectedBackup(root, sourceFile, label = "local") {
  if (!existsSync(sourceFile)) return null;
  const backupDir = path.join(root, ".runtime", "backups");
  mkdirSync(backupDir, { recursive: true, mode: 0o700 });
  chmodSync(backupDir, 0o700);
  const stamp = new Date().toISOString().replaceAll(":", "-");
  const safeLabel = String(label).replace(/[^a-z0-9-]/gi, "-").slice(0, 32) || "local";
  const baseName = path.basename(sourceFile).replace(/[^a-z0-9.-]/gi, "-");
  const backupFile = path.join(backupDir, `${safeLabel}-${baseName}-${stamp}-${randomBytes(4).toString("hex")}.json`);
  try {
    copyFileSync(sourceFile, backupFile, constants.COPYFILE_EXCL);
    chmodSync(backupFile, 0o600);
  } catch {
    throw new LocalRuntimeConfigError(
      "Unable to create a protected backup of the current local configuration; adoption was cancelled.",
      "LOCAL_CONFIG_BACKUP_FAILED",
    );
  }
  return backupFile;
}

export function isSameWorkspaceDirectory(root, candidate) {
  return samePhysicalDirectory(root, candidate);
}

export function isManagedOfficeWorkerEnvironment(root, processEnvironment) {
  const file = path.join(root, ".runtime", "office-worker", "local.json");
  if (!existsSync(file)) return false;
  try {
    const config = JSON.parse(readFileSync(file, "utf8"));
    return Boolean(
      config.ownerId === createHash("sha256").update(root).digest("hex").slice(0, 12) &&
      config.token &&
        config.url &&
        config.token === processEnvironment.LUMIRA_OFFICE_WORKER_TOKEN &&
        config.url === processEnvironment.LUMIRA_OFFICE_WORKER_URL,
    );
  } catch {
    return false;
  }
}

export async function adoptRunningBackend({ root, pid, procRoot = "/proc", fetchImpl = fetch, psqlDockerContainer } = {}) {
  if (process.platform === "win32" && procRoot === "/proc")
    throw new LocalRuntimeConfigError(
      "Adoption reads the WSL/Linux backend environment; run this command from the repository in WSL.",
      "PROC_UNAVAILABLE",
    );
  const processCwd = readProcCwd(pid, procRoot);
  if (!isSameWorkspaceDirectory(root, processCwd))
    throw new LocalRuntimeConfigError(
      "The selected backend process is not running from this workspace; adoption was cancelled.",
      "BACKEND_WORKSPACE_MISMATCH",
    );
  const commandLine = readProcText(pid, "cmdline", procRoot);
  const jarArgument = findJarArgument(commandLine);
  const commandArgs = commandLine.split("\0").filter(Boolean);
  if (
    !jarArgument ||
    !/^(?:java|java\.exe)$/i.test(path.basename(commandArgs[0] ?? "")) ||
    !/platform-server.*\.jar$/i.test(path.basename(jarArgument))
  )
    throw new LocalRuntimeConfigError(
      "The selected process command line does not identify a backend JAR; adoption was cancelled.",
      "BACKEND_PROCESS_MISMATCH",
    );
  const processCwdPath = processCwd;
  const jarPath = path.isAbsolute(jarArgument)
    ? jarArgument
    : path.resolve(processCwdPath, jarArgument);
  const jarRelativePath = relativeWorkspacePath(root, jarPath);
  const jarFile = path.resolve(root, jarRelativePath);
  if (!/\.jar$/i.test(jarFile) || !existsSync(jarFile))
    throw new LocalRuntimeConfigError(
      "The selected backend JAR is unavailable inside this workspace; adoption was cancelled.",
      "BACKEND_ARTIFACT_UNAVAILABLE",
    );
  const environment = readProcEnvironment(pid, procRoot, { includeJvmOptions: true });
  assertNoJvmIdentityOverrides({
    ...environment,
    JAVA_TOOL_OPTIONS: `${environment.JAVA_TOOL_OPTIONS ?? ""} ${commandLine.replaceAll("\0", " ")}`,
  });
  assertNoSpringRuntimeOverrides(environment);
  const config = buildAdoptedRuntimeConfig(
    readJson(runtimeConfigPaths(root).configFile, { optional: true }) ?? {},
    environment,
    { managedOfficeWorker: isManagedOfficeWorkerEnvironment(root, environment), psqlDockerContainer },
  );
  const validated = validatePinnedConfig(root, config);
  const address = String(config.LUMIRA_BIND ?? "127.0.0.1");
  if (!new Set(["127.0.0.1", "localhost", "::1"]).has(address))
    throw new LocalRuntimeConfigError(
      "The selected backend is not bound to a local loopback address; adoption was cancelled.",
      "BACKEND_NOT_LOCAL",
    );
  let response;
  try {
    response = await fetchImpl(`http://127.0.0.1:${validated.backendPort}/actuator/health/readiness`, {
      signal: AbortSignal.timeout(3000),
      redirect: "error",
    });
  } catch {
    throw new LocalRuntimeConfigError(
      "The selected backend did not pass its local readiness check; adoption was cancelled.",
      "BACKEND_NOT_READY",
    );
  }
  if (response.status !== 200)
    throw new LocalRuntimeConfigError(
      "The selected backend did not pass its local readiness check; adoption was cancelled.",
      "BACKEND_NOT_READY",
    );
  return {
    config,
    pid: Number(pid),
    artifact: { path: jarRelativePath, sha256: sha256File(jarFile) },
  };
}

export function backupAndWriteRuntimeConfig(root, config, pin) {
  const paths = runtimeConfigPaths(root);
  const originals = new Map(
    [paths.configFile, paths.pinFile].map((file) => [
      file,
      existsSync(file) ? readFileSync(file) : null,
    ]),
  );
  const backups = [];
  for (const file of originals.keys()) {
    if (existsSync(file)) backups.push(createProtectedBackup(root, file, "runtime-adopt"));
  }
  try {
    writeJsonAtomically(paths.configFile, config);
    writeJsonAtomically(paths.pinFile, pin);
  } catch (error) {
    try {
      restoreRuntimeFiles(originals);
    } catch (rollbackError) {
      throw new LocalRuntimeConfigError(
        `${error.message} Rollback also failed: ${rollbackError.message}`,
        "LOCAL_RUNTIME_ROLLBACK_FAILED",
      );
    }
    throw error;
  }
  return { backup: backups[0] ?? null, backups };
}

export function parseArguments(argv) {
  const [command, ...rest] = argv;
  const options = {};
  for (let index = 0; index < rest.length; index += 1) {
    const arg = rest[index];
    if (!arg.startsWith("--")) {
      if (!options.pid && /^\d+$/.test(arg)) options.pid = Number(arg);
      else throw new LocalRuntimeConfigError(`Unexpected argument: ${arg}`, "INVALID_ARGUMENT");
      continue;
    }
    const [name, inlineValue] = arg.slice(2).split("=", 2);
    const value = inlineValue ?? rest[index + 1];
    if (!inlineValue) index += 1;
    if (!value || value.startsWith("--"))
      throw new LocalRuntimeConfigError(`Missing value for --${name}.`, "INVALID_ARGUMENT");
    if (!new Set(["pid", "target-config", "psql-docker-container", "accept-changes", "jar", "sha256"]).has(name))
      throw new LocalRuntimeConfigError(`Unknown option --${name}.`, "INVALID_ARGUMENT");
    options[name.replaceAll("-", "")] = name === "pid" ? Number(value) : value;
  }
  return { command, options };
}

export async function withLocalRuntimeLock(root, operationName, operation) {
  let workspaceRoot;
  try {
    workspaceRoot = realpathSync(root);
  } catch {
    throw new LocalRuntimeConfigError(
      "The local runtime workspace root is unavailable.",
      "INVALID_WORKSPACE_ROOT",
    );
  }
  const runtimeDir = path.join(workspaceRoot, ".runtime");
  let runtimeStat;
  try {
    runtimeStat = lstatSync(runtimeDir);
  } catch (error) {
    if (error?.code !== "ENOENT")
      throw new LocalRuntimeConfigError(
        "The local runtime directory cannot be inspected safely.",
        "RUNTIME_PATH_OUTSIDE_WORKSPACE",
      );
    try {
      mkdirSync(runtimeDir, { mode: 0o700 });
    } catch (createError) {
      if (createError?.code !== "EEXIST")
        throw new LocalRuntimeConfigError(
          "The local runtime directory could not be created safely.",
          "RUNTIME_PATH_OUTSIDE_WORKSPACE",
        );
    }
    try {
      runtimeStat = lstatSync(runtimeDir);
    } catch {
      throw new LocalRuntimeConfigError(
        "The local runtime directory could not be inspected safely.",
        "RUNTIME_PATH_OUTSIDE_WORKSPACE",
      );
    }
  }
  if (runtimeStat.isSymbolicLink() || !runtimeStat.isDirectory() || !physicalRelativeParts(workspaceRoot, runtimeDir))
    throw new LocalRuntimeConfigError(
      "The local runtime directory must stay inside this workspace.",
      "RUNTIME_PATH_OUTSIDE_WORKSPACE",
    );
  const lockFile = path.join(runtimeDir, "local-runtime.lock");
  let descriptor;
  try {
    descriptor = openSync(lockFile, "wx", 0o600);
    writeFileSync(descriptor, `${JSON.stringify({ pid: process.pid, operation: operationName, startedAt: new Date().toISOString() })}\n`);
    fsyncSync(descriptor);
    chmodSync(lockFile, 0o600);
  } catch (error) {
    if (descriptor !== undefined) {
      try { closeSync(descriptor); } catch { /* best-effort lock cleanup */ }
      try { unlinkSync(lockFile); } catch { /* best-effort lock cleanup */ }
    }
    if (error?.code === "EEXIST")
      throw new LocalRuntimeConfigError(
        "A local runtime mutation is already active, or its lock remains after interruption. Confirm no mutation is running before removing .runtime/local-runtime.lock.",
        "LOCAL_RUNTIME_LOCKED",
      );
    throw new LocalRuntimeConfigError(
      "Unable to create the local runtime mutation lock; no runtime files were changed.",
      "LOCAL_RUNTIME_LOCK_FAILED",
    );
  }
  try {
    return await operation();
  } finally {
    try { closeSync(descriptor); } catch { /* best-effort lock cleanup */ }
    try { unlinkSync(lockFile); } catch { /* best-effort lock cleanup */ }
  }
}

export function writeLocalJson(root, relativePath, value) {
  writeJsonAtomically(path.join(root, relativePath), value);
}

export function copyLocalJsonBackup(root, sourceFile, label = "local-runtime") {
  return createProtectedBackup(root, sourceFile, label);
}

export function chmodPrivate(file, mode = 0o600) {
  chmodSync(file, mode);
}

export function fileExists(file) {
  return existsSync(file);
}

export function parseLocalConfigFile(file) {
  return readJson(file);
}

export function writeRuntimeJson(file, value) {
  writeJsonAtomically(file, value);
}

export function restoreRuntimeFile(file, content) {
  if (content === null) {
    if (existsSync(file)) unlinkSync(file);
    return;
  }
  mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const temporary = `${file}.${process.pid}.${randomBytes(6).toString("hex")}.restore`;
  writeFileSync(temporary, content, { mode: 0o600, flag: "wx" });
  chmodSync(temporary, 0o600);
  renameSync(temporary, file);
  chmodSync(file, 0o600);
}

export function restoreRuntimeFiles(originals) {
  const failures = [];
  for (const [file, content] of originals) {
    try {
      restoreRuntimeFile(file, content);
    } catch {
      failures.push(path.basename(file));
    }
  }
  if (failures.length)
    throw new LocalRuntimeConfigError(
      `Runtime rollback could not restore every file (${failures.join(", ")}); inspect the canonical runtime files before retrying.`,
      "LOCAL_RUNTIME_ROLLBACK_FAILED",
    );
}

export function protectRuntimeFile(root, file) {
  const backup = createProtectedBackup(root, file);
  return backup;
}

export function describePinForUser(pin) {
  return {
    pinned: true,
    database: `${pin.database.host}:${pin.database.port}/${pin.database.database}`,
    filesRoot: pin.paths.files,
    pluginsRoot: pin.paths.plugins,
    artifact: pin.artifact?.path ?? null,
    artifactSha256: pin.artifact?.sha256 ?? null,
  };
}
