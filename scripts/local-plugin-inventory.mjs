import { createHash, randomUUID } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  copyFileSync,
  readdirSync,
  renameSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isIP } from 'node:net';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const defaultSnapshotRelativePath = '.runtime/local-plugin-inventory.json';
const requiredTables = [
  'flyway_schema_history',
  'platform_plugin_artifact',
  'platform_plugin_installation',
  'platform_module_state',
];
const verifiedTransferSnapshots = new WeakMap();

const inventorySql = `
SELECT jsonb_build_object(
  'database', jsonb_build_object(
    'name', current_database(),
    'oid', (SELECT oid::text FROM pg_database WHERE datname = current_database()),
    'user', current_user,
    'serverAddress', host(inet_server_addr()),
    'serverPort', inet_server_port(),
    'serverVersionNum', current_setting('server_version_num'),
    'serverVersion', version(),
    'writablePrimary', NOT pg_is_in_recovery(),
    'flywayVersion', (
      SELECT version FROM public.flyway_schema_history
      WHERE version = '52' AND success
      ORDER BY installed_rank DESC LIMIT 1
    ),
    'flywayChecksum', (
      SELECT checksum FROM public.flyway_schema_history
      WHERE version = '52' AND success
      ORDER BY installed_rank DESC LIMIT 1
    )
  ),
  'plugins', (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
      'id', artifact.module_id,
      'version', artifact.version,
      'digest', artifact.digest,
      'manifest', artifact.manifest,
      'installed', COALESCE(installation.installed, false),
      'explicitInstall', COALESCE(installation.explicit_install, false),
      'enabled', COALESCE(installation.installed AND COALESCE(module_state.enabled, true), false)
    ) ORDER BY artifact.module_id, artifact.version, artifact.digest), '[]'::jsonb)
    FROM public.platform_plugin_artifact artifact
    LEFT JOIN public.platform_plugin_installation installation
      ON installation.module_id = artifact.module_id AND installation.digest = artifact.digest
    LEFT JOIN public.platform_module_state module_state
      ON module_state.module_id = artifact.module_id
  ),
  'danglingInstallations', (
    SELECT COALESCE(jsonb_agg(installation.module_id ORDER BY installation.module_id), '[]'::jsonb)
    FROM public.platform_plugin_installation installation
    LEFT JOIN public.platform_plugin_artifact artifact
      ON artifact.module_id = installation.module_id AND artifact.digest = installation.digest
    WHERE artifact.digest IS NULL
  )
)::text;
`;

function sha256(data) {
  return createHash('sha256').update(data).digest('hex');
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function snapshotDigest(snapshot) {
  return sha256(stableJson(snapshot));
}

function snapshotState(snapshot) {
  return {
    formatVersion: snapshot.formatVersion,
    database: snapshot.database,
    plugins: snapshot.plugins,
  };
}

function snapshotPathFor(root, snapshotPath) {
  return path.resolve(root, snapshotPath ?? defaultSnapshotRelativePath);
}

export function parseLocalPluginDatabaseConfig(config) {
  if (!config || typeof config !== 'object') throw new Error('Local database configuration is missing.');
  const rawUrl = config.LUMIRA_DB_URL;
  if (typeof rawUrl !== 'string' || !rawUrl.startsWith('jdbc:postgresql://'))
    throw new Error('LUMIRA_DB_URL must be an explicit local PostgreSQL JDBC URL.');

  let url;
  try {
    url = new URL(rawUrl.slice('jdbc:'.length));
  } catch {
    throw new Error('LUMIRA_DB_URL is invalid.');
  }
  if (url.protocol !== 'postgresql:' || url.username || url.password)
    throw new Error('LUMIRA_DB_URL must not embed credentials.');

  const host = url.hostname.replace(/^\[|\]$/g, '').toLowerCase();
  const loopback = host === 'localhost'
    || (isIP(host) === 4 && Number(host.split('.')[0]) === 127)
    || (isIP(host) === 6 && host === '::1');
  if (!loopback) throw new Error('Plugin inventory checks only allow loopback PostgreSQL hosts.');

  const port = url.port ? Number(url.port) : 5432;
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('LUMIRA_DB_URL port is invalid.');
  let databaseName;
  try {
    databaseName = decodeURIComponent(url.pathname.slice(1));
  } catch {
    throw new Error('LUMIRA_DB_URL database name is invalid.');
  }
  if (!/^lumira(?:_[a-z0-9][a-z0-9_-]*)?$/i.test(databaseName))
    throw new Error('Plugin inventory checks only allow explicitly named lumira databases.');
  if (url.searchParams.size > 1 || (url.searchParams.size === 1 && !url.searchParams.has('sslmode')))
    throw new Error('LUMIRA_DB_URL has unsupported query parameters.');
  const sslmode = url.searchParams.get('sslmode');
  if (sslmode && !['disable', 'allow', 'prefer', 'require', 'verify-ca', 'verify-full'].includes(sslmode))
    throw new Error('LUMIRA_DB_URL sslmode is invalid.');

  const user = config.LUMIRA_DB_USER;
  if (typeof user !== 'string' || !user.trim() || /[\r\n\0]/.test(user))
    throw new Error('LUMIRA_DB_USER must be explicit.');
  const password = config.LUMIRA_DB_PASSWORD;
  if (typeof password !== 'string')
    throw new Error('LUMIRA_DB_PASSWORD must be explicit in the local configuration.');
  const psqlBin = config.PSQL_BIN ?? config.LUMIRA_PSQL_BIN ?? process.env.PSQL_BIN;
  if (psqlBin !== undefined && (typeof psqlBin !== 'string' || !psqlBin.trim() || /[\r\n\0]/.test(psqlBin)))
    throw new Error('PSQL_BIN must be a valid executable path when provided.');
  const dockerContainer = config.LUMIRA_PSQL_DOCKER_CONTAINER ?? process.env.LUMIRA_PSQL_DOCKER_CONTAINER;
  if (dockerContainer !== undefined && (typeof dockerContainer !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(dockerContainer)))
    throw new Error('LUMIRA_PSQL_DOCKER_CONTAINER must be a valid local container name.');

  return {
    host,
    port,
    databaseName,
    user,
    password,
    sslmode,
    psqlBin,
    dockerContainer,
  };
}

export function localPluginDatabaseIdentityDifferences(left, right) {
  const immutableFields = [
    'configuredHost', 'configuredPort', 'name', 'oid', 'user', 'serverAddress', 'serverPort',
  ];
  return immutableFields.filter(field => stableJson(left?.[field]) !== stableJson(right?.[field]));
}

function psqlArgs(connection, sql) {
  const args = [
    '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
    '--host', connection.host,
    '--port', String(connection.port),
    '--username', connection.user,
    '--dbname', connection.databaseName,
  ];
  args.push('-c', sql);
  return args;
}

function queryPostgres(connection, sql) {
  const args = psqlArgs(connection, sql);
  const env = { ...process.env };
  delete env.PGPASSWORD;
  env.PGPASSWORD = connection.password;
  if (connection.sslmode) env.PGSSLMODE = connection.sslmode;

  let command = connection.psqlBin || 'psql';
  let commandArgs = args;
  if (connection.dockerContainer) {
    command = 'docker';
    commandArgs = ['exec', '-i'];
    commandArgs.push('-e', 'PGPASSWORD');
    if (connection.sslmode) commandArgs.push('-e', 'PGSSLMODE');
    commandArgs.push(connection.dockerContainer, 'psql', ...args);
  }

  const result = spawnSync(command, commandArgs, {
    encoding: 'utf8',
    env,
    timeout: 30_000,
    maxBuffer: 4 * 1024 * 1024,
    windowsHide: true,
  });
  if (result.error || result.status !== 0) {
    const cause = result.error?.code === 'ENOENT'
      ? 'PostgreSQL client unavailable; set PSQL_BIN or LUMIRA_PSQL_DOCKER_CONTAINER.'
      : `PostgreSQL inventory query failed (psql exit ${result.status ?? 'unknown'}).`;
    throw new Error(cause);
  }
  return result.stdout.trim();
}

function requiredTableCheck(connection) {
  const sql = `SELECT COALESCE(json_agg(name ORDER BY name), '[]'::json)::text FROM (VALUES ${requiredTables.map(name => `('${name}')`).join(',')}) AS required(name) WHERE to_regclass('public.' || name) IS NULL`;
  let missing;
  try {
    missing = JSON.parse(queryPostgres(connection, sql));
  } catch (error) {
    throw new Error(`Cannot inspect the configured local database schema: ${error.message}`);
  }
  if (!Array.isArray(missing)) throw new Error('PostgreSQL returned an invalid schema check result.');
  if (missing.length) throw new Error(`Plugin inventory schema is incomplete: missing ${missing.join(', ')}.`);
}

function assertNoOtherClientConnections(config) {
  const connection = parseLocalPluginDatabaseConfig(config);
  let result;
  try {
    result = JSON.parse(queryPostgres(connection, `SELECT json_build_object(
      'canInspectAll', COALESCE((SELECT rolsuper FROM pg_roles WHERE rolname = current_user), false)
        OR pg_has_role(current_user, 'pg_read_all_stats', 'USAGE'),
      'otherClients', (SELECT count(*)::integer FROM pg_stat_activity
        WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend')
    )::text`));
  } catch (error) {
    throw new Error(`Cannot verify that local services are stopped before plugin database transfer: ${error.message}`);
  }
  if (!result || typeof result.canInspectAll !== 'boolean'
      || !Number.isInteger(result.otherClients) || result.otherClients < 0)
    throw new Error('PostgreSQL returned an invalid active client check for plugin database transfer.');
  if (!result.canInspectAll)
    throw new Error('Plugin database transfer requires a database role with pg_read_all_stats visibility so all client connections can be checked.');
  if (result.otherClients > 0)
    throw new Error('Plugin database transfer requires source and target databases to have no other client connections; stop local services and database clients first.');
}

const activityAuditIdentitySql = `SELECT json_build_object(
  'database', current_database(),
  'oid', (SELECT oid::text FROM pg_database WHERE datname = current_database()),
  'postmasterStartEpoch', extract(epoch FROM pg_postmaster_start_time())::text
)::text`;

function parseActivityAuditIdentity(raw, label) {
  let result;
  try {
    result = JSON.parse(raw);
  } catch {
    throw new Error(`PostgreSQL returned an invalid ${label} database identity for the Docker activity audit.`);
  }
  if (!result || typeof result.database !== 'string' || typeof result.oid !== 'string'
      || !/^\d+$/.test(result.oid) || typeof result.postmasterStartEpoch !== 'string'
      || !/^\d+(?:\.\d+)?$/.test(result.postmasterStartEpoch))
    throw new Error(`PostgreSQL returned an incomplete ${label} database identity for the Docker activity audit.`);
  return result;
}

function queryDockerAdminActivityAudit(connection, sql) {
  if (!connection.dockerContainer)
    throw new Error('Docker admin activity audit requires a configured local PostgreSQL container.');
  const shellCommand = 'test -n "${POSTGRES_USER:-}" || exit 90; exec psql --username "$POSTGRES_USER" "$@"';
  const args = [
    'exec', '-i', '--user', 'postgres', connection.dockerContainer,
    'sh', '-c', shellCommand, 'psql',
    '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
    '--dbname', connection.databaseName,
    '-c', sql,
  ];
  const env = { ...process.env };
  for (const name of ['PGPASSWORD', 'PGUSER', 'PGDATABASE', 'PGHOST', 'PGPORT', 'PGSSLMODE']) delete env[name];
  const result = spawnSync('docker', args, {
    encoding: 'utf8',
    env,
    timeout: 30_000,
    maxBuffer: 4 * 1024 * 1024,
    windowsHide: true,
  });
  if (result.error || result.status !== 0)
    throw new Error('Docker-local PostgreSQL admin activity audit failed; no inventory or runtime files were changed.');
  return result.stdout.trim();
}

function assertNoOtherClientConnectionsWithDockerAdmin(config, snapshotDatabase) {
  const connection = parseLocalPluginDatabaseConfig(config);
  let appIdentity;
  try {
    appIdentity = parseActivityAuditIdentity(queryPostgres(connection, activityAuditIdentitySql), 'application');
  } catch (error) {
    throw new Error(`Cannot verify the application database identity for Docker activity audit: ${error.message}`);
  }
  if (appIdentity.database !== connection.databaseName || appIdentity.database !== snapshotDatabase.name
      || appIdentity.oid !== String(snapshotDatabase.oid))
    throw new Error('The application database identity changed before the Docker activity audit.');

  const adminSql = `BEGIN READ ONLY;
SELECT json_build_object(
  'database', current_database(),
  'oid', (SELECT oid::text FROM pg_database WHERE datname = current_database()),
  'postmasterStartEpoch', extract(epoch FROM pg_postmaster_start_time())::text,
  'canInspectAll', COALESCE((SELECT rolsuper FROM pg_roles WHERE rolname = current_user), false)
    OR pg_has_role(current_user, 'pg_read_all_stats', 'USAGE'),
  'otherClients', (SELECT count(*)::integer FROM pg_stat_activity
    WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend')
)::text;
ROLLBACK;`;
  let admin;
  try {
    admin = JSON.parse(queryDockerAdminActivityAudit(connection, adminSql));
  } catch (error) {
    throw new Error(`Cannot verify local database activity with the Docker admin identity: ${error.message}`);
  }
  if (!admin || typeof admin.database !== 'string' || typeof admin.oid !== 'string'
      || !/^\d+$/.test(admin.oid) || typeof admin.postmasterStartEpoch !== 'string'
      || !/^\d+(?:\.\d+)?$/.test(admin.postmasterStartEpoch)
      || typeof admin.canInspectAll !== 'boolean'
      || !Number.isInteger(admin.otherClients) || admin.otherClients < 0)
    throw new Error('PostgreSQL returned an invalid Docker admin activity audit result.');
  if (admin.database !== appIdentity.database || admin.oid !== appIdentity.oid
      || admin.postmasterStartEpoch !== appIdentity.postmasterStartEpoch)
    throw new Error('Docker admin activity audit connected to a different PostgreSQL database instance.');
  if (!admin.canInspectAll)
    throw new Error('Docker admin activity audit requires a superuser or pg_read_all_stats visibility.');
  if (admin.otherClients > 0)
    throw new Error('Plugin database transfer requires source and target databases to have no other client connections; stop local services and database clients first.');
}

function pluginRootFor(root, config) {
  const value = config.LUMIRA_PLUGINS_PATH;
  const resolved = typeof value === 'string' && value.trim()
    ? path.resolve(root, value)
    : path.resolve(root, '.runtime', 'plugins');
  return resolved;
}

function safeRelativeFile(base, relative) {
  if (typeof relative !== 'string' || !relative || relative.includes('\\') || relative.startsWith('/'))
    throw new Error('Plugin manifest contains an invalid file path.');
  const segments = relative.split('/');
  if (segments.some(segment => !segment || segment === '.' || segment === '..'))
    throw new Error('Plugin manifest contains an invalid file path.');
  const candidate = path.resolve(base, ...segments);
  if (!candidate.startsWith(`${base}${path.sep}`)) throw new Error('Plugin manifest file path escapes its artifact directory.');
  return candidate;
}

function walkRegularFiles(directory) {
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error('Plugin artifact contains a symbolic link.');
    if (entry.isDirectory()) files.push(...walkRegularFiles(entryPath));
    else if (entry.isFile()) files.push(entryPath);
    else throw new Error('Plugin artifact contains an unsupported filesystem entry.');
  }
  return files;
}

function normalizedAuthorityScopes(scopes) {
  if (scopes == null) return {};
  if (typeof scopes !== 'object' || Array.isArray(scopes)
      || Object.values(scopes).some(value => value !== 'global' && value !== 'scoped')) {
    throw new Error('Plugin rule authorityScopes must contain explicit global/scoped values.');
  }
  const entries = Object.entries(scopes).sort(([left], [right]) => left.localeCompare(right));
  // Omit the additive empty field so existing published manifest digests remain stable.
  return entries.length ? { authorityScopes: Object.fromEntries(entries) } : {};
}

export function normalizePersistedPluginManifest(manifest) {
  const dependencies = Array.isArray(manifest.dependencies) ? manifest.dependencies : [];
  const permissions = Array.isArray(manifest.permissions) ? manifest.permissions : [];
  let permissionGroups = Array.isArray(manifest.permissionGroups) ? manifest.permissionGroups : [];
  if (!permissionGroups.length && permissions.length) {
    permissionGroups = [{
      id: manifest.id,
      title: manifest.name,
      permissions: permissions.map(code => ({
        code,
        label: typeof code === 'string' && code.includes(':') ? code.slice(code.lastIndexOf(':') + 1) : code,
        requires: [],
      })),
    }];
  }
  const routes = Array.isArray(manifest.routes) ? manifest.routes : [];
  const apiRules = Array.isArray(manifest.apiRules) ? manifest.apiRules : [];
  const dataSets = Array.isArray(manifest.dataSets) ? manifest.dataSets : [];
  return {
    formatVersion: manifest.formatVersion ?? 0,
    id: manifest.id ?? null,
    name: manifest.name ?? null,
    version: manifest.version ?? null,
    sdkVersion: manifest.sdkVersion ?? null,
    entryClass: manifest.entryClass ?? null,
    backend: manifest.backend ?? null,
    frontend: manifest.frontend ?? null,
    dependencies: dependencies.map(dependency => ({ id: dependency.id ?? null, version: dependency.version ?? null })),
    permissions,
    permissionGroups: permissionGroups.map(group => ({
      id: group.id ?? null,
      title: group.title ?? null,
      permissions: (Array.isArray(group.permissions) ? group.permissions : []).map(permission => ({
        code: permission.code ?? null,
        label: permission.label ?? null,
        requires: Array.isArray(permission.requires) ? permission.requires : [],
      })),
    })),
    routes: routes.map(route => ({
      id: route.id ?? null,
      label: route.label ?? null,
      permission: route.permission ?? null,
      operations: route.operations && typeof route.operations === 'object' ? route.operations : {},
      scopedOperations: Array.isArray(route.scopedOperations) ? route.scopedOperations : [],
      group: route.group ?? null,
      order: Number.isInteger(route.order) ? route.order : 0,
      global: route.global === true,
    })),
    apiRules: apiRules.map(rule => ({
      method: rule.method ?? null,
      path: rule.path ?? null,
      authorities: Array.isArray(rule.authorities) ? rule.authorities : null,
      global: rule.global === true,
      publicAccess: rule.publicAccess === true,
      authenticatedOnly: rule.authenticatedOnly === true,
      previewReadOnly: rule.previewReadOnly === true,
      signedCallback: rule.signedCallback === true,
      allAuthorities: Array.isArray(rule.allAuthorities) ? rule.allAuthorities : [],
      globalAuthorities: Array.isArray(rule.globalAuthorities) ? rule.globalAuthorities : [],
      ...normalizedAuthorityScopes(rule.authorityScopes),
    })),
    files: manifest.files && typeof manifest.files === 'object' && !Array.isArray(manifest.files) ? manifest.files : {},
    dataSets: dataSets
      .filter(set => !(manifest.id === 'feedback' && set.table === 'platform_account_draft'
        && set.column === 'draft_key' && set.value === 'feedback.create'))
      .map(set => ({
        table: set.table ?? null,
        label: set.label ?? null,
        column: set.column ?? null,
        value: set.value ?? null,
      })),
  };
}

function validateArtifactFiles(root, pluginsRoot, plugin) {
  const artifactRoot = path.resolve(pluginsRoot, 'artifacts');
  const artifactDirectory = path.resolve(artifactRoot, plugin.digest);
  if (!artifactDirectory.startsWith(`${artifactRoot}${path.sep}`)) throw new Error(`Invalid artifact directory for ${plugin.id}@${plugin.version}.`);
  if (!existsSync(artifactDirectory) || !lstatSync(artifactDirectory).isDirectory() || lstatSync(artifactDirectory).isSymbolicLink())
    throw new Error(`Plugin artifact directory is missing for ${plugin.id}@${plugin.version} (${plugin.digest}).`);

  let diskManifest;
  try {
    diskManifest = JSON.parse(readFileSync(path.join(artifactDirectory, 'plugin.json'), 'utf8'));
  } catch {
    throw new Error(`Plugin manifest is missing or invalid for ${plugin.id}@${plugin.version}.`);
  }
  if (diskManifest.id !== plugin.id || diskManifest.version !== plugin.version)
    throw new Error(`Plugin manifest identity mismatch for ${plugin.id}@${plugin.version}.`);
  if (!diskManifest.files || typeof diskManifest.files !== 'object' || Array.isArray(diskManifest.files))
    throw new Error(`Plugin file digest list is missing for ${plugin.id}@${plugin.version}.`);
  if (stableJson(normalizePersistedPluginManifest(diskManifest))
      !== stableJson(normalizePersistedPluginManifest(plugin.manifest)))
    throw new Error(`Database and disk manifests differ for ${plugin.id}@${plugin.version}.`);
  if (typeof diskManifest.backend !== 'string' || typeof diskManifest.frontend !== 'string'
      || !Object.hasOwn(diskManifest.files, diskManifest.backend)
      || !Object.hasOwn(diskManifest.files, diskManifest.frontend))
    throw new Error(`Plugin backend or frontend entry is not covered by the file digests for ${plugin.id}@${plugin.version}.`);

  const expectedFiles = new Set(['plugin.json']);
  for (const [relative, expectedDigest] of Object.entries(diskManifest.files)) {
    if (!/^[a-f0-9]{64}$/.test(expectedDigest))
      throw new Error(`Invalid plugin file digest for ${plugin.id}@${plugin.version}.`);
    const filename = safeRelativeFile(artifactDirectory, relative);
    if (!existsSync(filename) || !lstatSync(filename).isFile() || lstatSync(filename).isSymbolicLink())
      throw new Error(`Plugin file is missing for ${plugin.id}@${plugin.version}: ${relative}.`);
    if (sha256(readFileSync(filename)) !== expectedDigest)
      throw new Error(`Plugin file digest mismatch for ${plugin.id}@${plugin.version}: ${relative}.`);
    expectedFiles.add(path.relative(artifactDirectory, filename).split(path.sep).join('/'));
  }
  for (const filename of walkRegularFiles(artifactDirectory)) {
    const relative = path.relative(artifactDirectory, filename).split(path.sep).join('/');
    if (!expectedFiles.has(relative)) throw new Error(`Unlisted file in plugin artifact ${plugin.id}@${plugin.version}: ${relative}.`);
  }

  const sourceArchive = path.resolve(root, 'plugins', plugin.id, 'dist', `${plugin.id}-${plugin.version}.lumira-plugin.zip`);
  if (existsSync(sourceArchive) && sha256(readFileSync(sourceArchive)) !== plugin.digest)
    throw new Error(`Available source archive digest mismatch for ${plugin.id}@${plugin.version}.`);
}

function validateRawPlugin(plugin) {
  if (!plugin || typeof plugin !== 'object') throw new Error('PostgreSQL returned an invalid plugin record.');
  if (typeof plugin.id !== 'string' || !/^[a-z][a-z0-9-]*$/.test(plugin.id)
      || typeof plugin.version !== 'string' || !plugin.version
      || typeof plugin.digest !== 'string' || !/^[a-f0-9]{64}$/.test(plugin.digest))
    throw new Error('Plugin inventory contains an invalid ID, version, or digest.');
  for (const field of ['installed', 'explicitInstall', 'enabled'])
    if (typeof plugin[field] !== 'boolean') throw new Error(`Plugin inventory ${field} state is unknown for ${plugin.id}@${plugin.version}.`);
  if (!plugin.manifest || typeof plugin.manifest !== 'object' || Array.isArray(plugin.manifest))
    throw new Error(`Plugin database manifest is missing for ${plugin.id}@${plugin.version}.`);
  if (plugin.manifest.id !== plugin.id || plugin.manifest.version !== plugin.version)
    throw new Error(`Plugin database manifest identity mismatch for ${plugin.id}@${plugin.version}.`);
  const manifestDigest = sha256(stableJson(plugin.manifest));
  return {
    item: {
      id: plugin.id,
      version: plugin.version,
      digest: plugin.digest,
      manifestDigest,
      installed: plugin.installed,
      explicitInstall: plugin.explicitInstall,
      enabled: plugin.enabled,
    },
    manifest: plugin.manifest,
  };
}

function readSnapshotFromDatabase(root, config) {
  const connection = parseLocalPluginDatabaseConfig(config);
  requiredTableCheck(connection);
  let result;
  try {
    result = JSON.parse(queryPostgres(connection, inventorySql));
  } catch (error) {
    throw new Error(`Cannot read the configured plugin inventory: ${error.message}`);
  }
  if (!result || !result.database || !Array.isArray(result.plugins) || !Array.isArray(result.danglingInstallations))
    throw new Error('PostgreSQL returned an invalid plugin inventory result.');
  const actualDatabase = result.database;
  if (actualDatabase.name !== connection.databaseName || actualDatabase.user !== connection.user)
    throw new Error('PostgreSQL connected to a database or role different from the explicit local configuration.');
  if (!actualDatabase.oid || !/^\d+$/.test(String(actualDatabase.oid)))
    throw new Error('PostgreSQL did not return a database identity.');
  if (actualDatabase.flywayVersion !== '52')
    throw new Error(`Configured database ${actualDatabase.name} is not initialized at B52.`);
  if (actualDatabase.writablePrimary !== true)
    throw new Error(`Configured database ${actualDatabase.name} is not a writable primary.`);
  if (result.danglingInstallations.length)
    throw new Error(`Plugin installation rows reference missing artifacts: ${result.danglingInstallations.join(', ')}.`);
  if (result.plugins.length === 0)
    throw new Error(`Configured database ${actualDatabase.name} has no plugin artifacts; inventory is unknown and cannot be pinned.`);

  const seen = new Set();
  const seenIdVersions = new Set();
  const plugins = [];
  const pluginsRoot = pluginRootFor(root, config);
  for (const raw of result.plugins) {
    const { item, manifest } = validateRawPlugin(raw);
    const key = `${item.id}\0${item.version}\0${item.digest}`;
    if (seen.has(key)) throw new Error(`Duplicate plugin artifact row for ${item.id}@${item.version}.`);
    seen.add(key);
    const idVersion = `${item.id}\0${item.version}`;
    if (seenIdVersions.has(idVersion)) throw new Error(`Duplicate plugin ID/version row for ${item.id}@${item.version}.`);
    seenIdVersions.add(idVersion);
    validateArtifactFiles(root, pluginsRoot, { ...item, manifest });
    plugins.push(item);
  }
  plugins.sort((left, right) => left.id.localeCompare(right.id) || left.version.localeCompare(right.version) || left.digest.localeCompare(right.digest));
  const database = {
    configuredHost: connection.host,
    configuredPort: connection.port,
    name: actualDatabase.name,
    oid: String(actualDatabase.oid),
    user: actualDatabase.user,
    serverAddress: actualDatabase.serverAddress,
    serverPort: actualDatabase.serverPort === null ? null : Number(actualDatabase.serverPort),
    serverVersionNum: String(actualDatabase.serverVersionNum),
    serverVersion: String(actualDatabase.serverVersion),
    flywayVersion: actualDatabase.flywayVersion,
    flywayChecksum: actualDatabase.flywayChecksum === null ? null : Number(actualDatabase.flywayChecksum),
    writablePrimary: actualDatabase.writablePrimary,
  };
  const snapshot = {
    formatVersion: 1,
    capturedAt: new Date().toISOString(),
    database,
    plugins,
  };
  parseLocalPluginInventorySnapshot(JSON.stringify(snapshot), 'live database snapshot');
  return snapshot;
}

export function parseLocalPluginInventorySnapshot(text, file = '<input>') {
  let snapshot;
  try {
    snapshot = JSON.parse(text);
  } catch {
    throw new Error(`Plugin inventory snapshot is invalid JSON: ${file}.`);
  }
  if (snapshot?.formatVersion !== 1 || typeof snapshot.capturedAt !== 'string'
      || !Number.isFinite(Date.parse(snapshot.capturedAt))
      || !snapshot.database || typeof snapshot.database !== 'object' || Array.isArray(snapshot.database)
      || !Array.isArray(snapshot.plugins))
    throw new Error(`Plugin inventory snapshot has an unsupported or incomplete format: ${file}.`);
  const database = snapshot.database;
  const host = String(database.configuredHost ?? '').replace(/^\[|\]$/g, '').toLowerCase();
  const loopback = host === 'localhost'
    || isIP(host) === 6 && host === '::1'
    || isIP(host) === 4 && Number(host.split('.')[0]) === 127;
  if (!loopback
      || !Number.isInteger(database.configuredPort) || database.configuredPort < 1 || database.configuredPort > 65535
      || typeof database.name !== 'string' || !/^lumira(?:_[a-z0-9][a-z0-9_-]*)?$/i.test(database.name)
      || typeof database.oid !== 'string' || !/^\d+$/.test(database.oid)
      || typeof database.user !== 'string' || !database.user.trim()
      || !(database.serverAddress === null || typeof database.serverAddress === 'string' && isIP(database.serverAddress))
      || !(database.serverPort === null || Number.isInteger(database.serverPort) && database.serverPort >= 1 && database.serverPort <= 65535)
      || typeof database.serverVersionNum !== 'string' || !/^\d+$/.test(database.serverVersionNum)
      || typeof database.serverVersion !== 'string' || !database.serverVersion
      || database.flywayVersion !== '52'
      || !(database.flywayChecksum === null || Number.isInteger(database.flywayChecksum))
      || database.writablePrimary !== true)
    throw new Error(`Plugin inventory snapshot has an incomplete database identity: ${file}.`);
  if (!snapshot.plugins.length) throw new Error(`Plugin inventory snapshot is empty: ${file}.`);
  const idsAndVersions = new Set();
  for (const plugin of snapshot.plugins) {
    if (!plugin || typeof plugin.id !== 'string' || !/^[a-z][a-z0-9-]*$/.test(plugin.id)
        || typeof plugin.version !== 'string' || !plugin.version
        || !/^[a-f0-9]{64}$/.test(plugin.digest) || !/^[a-f0-9]{64}$/.test(plugin.manifestDigest)
        || ['installed', 'explicitInstall', 'enabled'].some(field => typeof plugin[field] !== 'boolean')
        || plugin.enabled && !plugin.installed)
      throw new Error(`Plugin inventory snapshot contains an incomplete plugin record: ${file}.`);
    const idAndVersion = `${plugin.id}\0${plugin.version}`;
    if (idsAndVersions.has(idAndVersion))
      throw new Error(`Plugin inventory snapshot contains a duplicate ID/version: ${file}.`);
    idsAndVersions.add(idAndVersion);
  }
  return snapshot;
}

function readPinnedSnapshot(root, snapshotPath) {
  const file = snapshotPathFor(root, snapshotPath);
  if (!existsSync(file)) throw new Error(`Plugin inventory snapshot is missing: ${path.relative(root, file)}. Run the explicit capture command after checking the configured database and package directory.`);
  try {
    return parseLocalPluginInventorySnapshot(readFileSync(file, 'utf8'), file);
  } catch (error) {
    if (error.message.startsWith('Plugin inventory snapshot')) throw error;
    throw new Error(`Cannot read plugin inventory snapshot ${path.relative(root, file)}.`);
  }
}

function writeSnapshotAtomic(root, snapshot, snapshotPath) {
  const file = snapshotPathFor(root, snapshotPath);
  mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const temporary = `${file}.${process.pid}.${randomUUID()}.tmp`;
  try {
    writeFileSync(temporary, `${JSON.stringify(snapshot, null, 2)}\n`, { mode: 0o600, flag: 'wx' });
    renameSync(temporary, file);
  } finally {
    try { rmSync(temporary, { force: true }); } catch { /* best-effort temporary cleanup */ }
  }
}

export function compareLocalPluginInventory(expected, actual) {
  const differences = [];
  const expectedState = snapshotState(expected);
  const actualState = snapshotState(actual);
  const expectedDb = stableJson(expectedState.database);
  const actualDb = stableJson(actualState.database);
  if (expectedDb !== actualDb) {
    for (const key of new Set([...Object.keys(expected.database), ...Object.keys(actual.database)])) {
      if (stableJson(expected.database[key]) !== stableJson(actual.database[key]))
        differences.push(`database.${key}: pinned=${JSON.stringify(expected.database[key])}, current=${JSON.stringify(actual.database[key])}`);
    }
  }
  const expectedByKey = new Map(expected.plugins.map(plugin => [`${plugin.id}\0${plugin.version}\0${plugin.digest}`, plugin]));
  const actualByKey = new Map(actual.plugins.map(plugin => [`${plugin.id}\0${plugin.version}\0${plugin.digest}`, plugin]));
  for (const [key, plugin] of expectedByKey) {
    if (!actualByKey.has(key)) differences.push(`missing ${plugin.id}@${plugin.version} digest=${plugin.digest}`);
  }
  for (const [key, plugin] of actualByKey) {
    if (!expectedByKey.has(key)) differences.push(`unexpected ${plugin.id}@${plugin.version} digest=${plugin.digest}`);
  }
  const expectedByIdVersion = new Map(expected.plugins.map(plugin => [`${plugin.id}\0${plugin.version}`, plugin]));
  const actualByIdVersion = new Map(actual.plugins.map(plugin => [`${plugin.id}\0${plugin.version}`, plugin]));
  for (const [key, pinned] of expectedByIdVersion) {
    const current = actualByIdVersion.get(key);
    if (!current) continue;
    for (const field of ['digest', 'manifestDigest', 'installed', 'explicitInstall', 'enabled'])
      if (pinned[field] !== current[field])
        differences.push(`${pinned.id}@${pinned.version} ${field}: pinned=${pinned[field]}, current=${current[field]}`);
  }
  return differences;
}

export function assertLocalPluginInventory({ root = projectRoot, config, snapshotPath } = {}) {
  const pinned = readPinnedSnapshot(root, snapshotPath);
  const current = readSnapshotFromDatabase(root, config);
  const differences = compareLocalPluginInventory(pinned, current);
  if (differences.length)
    throw new Error(`Local plugin inventory differs from its pin:\n${differences.map(item => `- ${item}`).join('\n')}`);
  return current;
}

export function captureLocalPluginInventory({ root = projectRoot, config, snapshotPath, acceptChangesReason } = {}) {
  const snapshot = readSnapshotFromDatabase(root, config);
  const file = snapshotPathFor(root, snapshotPath);
  if (existsSync(file)) {
    const pinned = readPinnedSnapshot(root, snapshotPath);
    const databaseChanges = localPluginDatabaseIdentityDifferences(pinned.database, snapshot.database);
    if (databaseChanges.length) {
      throw new Error(`Database identity changed (${[...databaseChanges].join(', ')}); capture cannot rebind a pin to another database. Use the explicit database switch flow.`);
    }
    const differences = compareLocalPluginInventory(pinned, snapshot);
    if (!differences.length) return pinned;
    if (typeof acceptChangesReason !== 'string' || !acceptChangesReason.trim()) {
      throw new Error(`Plugin inventory has drifted. Review the changes and pass --accept-changes with a non-empty reason before replacing the pin:\n${differences.map(item => `- ${item}`).join('\n')}`);
    }
    const backupDirectory = path.resolve(root, '.runtime', 'backups');
    mkdirSync(backupDirectory, { recursive: true, mode: 0o700 });
    const timestamp = new Date().toISOString().replaceAll(':', '-');
    const backup = path.join(backupDirectory, `local-plugin-inventory.${timestamp}.${randomUUID()}.json`);
    copyFileSync(file, backup);
  }
  writeSnapshotAtomic(root, snapshot, snapshotPath);
  return snapshot;
}

function sameDatabase(left, right) {
  return localPluginDatabaseIdentityDifferences(left, right).length === 0;
}

export function verifyLocalPluginInventoryTransfer({ root = projectRoot, sourceConfig, targetConfig, snapshotPath, activityAuditDockerAdmin = false } = {}) {
  if (typeof activityAuditDockerAdmin !== 'boolean')
    throw new Error('Docker admin activity audit must be explicitly enabled with a boolean option.');
  if (activityAuditDockerAdmin) {
    const sourceConnection = parseLocalPluginDatabaseConfig(sourceConfig);
    const targetConnection = parseLocalPluginDatabaseConfig(targetConfig);
    if (!sourceConnection.dockerContainer || sourceConnection.dockerContainer !== targetConnection.dockerContainer
        || sourceConnection.host !== targetConnection.host || sourceConnection.port !== targetConnection.port)
      throw new Error('Docker admin activity audit requires source and target to use the same configured local PostgreSQL container and server.');
  }
  const pinned = readPinnedSnapshot(root, snapshotPath);
  const pinDigest = snapshotDigest(pinned);
  const source = readSnapshotFromDatabase(root, sourceConfig);
  const sourceDifferences = compareLocalPluginInventory(pinned, source);
  if (sourceDifferences.length)
    throw new Error(`Source database does not match the pinned plugin inventory:\n${sourceDifferences.map(item => `- ${item}`).join('\n')}`);
  if (activityAuditDockerAdmin) assertNoOtherClientConnectionsWithDockerAdmin(sourceConfig, source.database);
  else assertNoOtherClientConnections(sourceConfig);

  const target = readSnapshotFromDatabase(root, targetConfig);
  if (activityAuditDockerAdmin) assertNoOtherClientConnectionsWithDockerAdmin(targetConfig, target.database);
  else assertNoOtherClientConnections(targetConfig);
  if (sameDatabase(source.database, target.database))
    throw new Error(`Target database ${target.database.name} is the same database as the source.`);
  const sourcePlugins = { ...source, database: pinned.database };
  const targetPlugins = { ...target, database: pinned.database };
  const differences = compareLocalPluginInventory(sourcePlugins, targetPlugins);
  if (differences.length)
    throw new Error(`Target plugin inventory does not exactly match the source:\n${differences.map(item => `- ${item}`).join('\n')}`);

  const snapshotForTarget = target;
  verifiedTransferSnapshots.set(snapshotForTarget, {
    sourceDigest: pinDigest,
    targetDigest: snapshotDigest(snapshotForTarget),
  });
  return { verified: true, snapshotForTarget, sourceSnapshotDigest: pinDigest };
}

export function writeLocalPluginInventorySnapshot({ root = projectRoot, snapshot, expectedSourceDigest, snapshotPath } = {}) {
  const verified = snapshot && verifiedTransferSnapshots.get(snapshot);
  if (!verified || verified.sourceDigest !== expectedSourceDigest || verified.targetDigest !== snapshotDigest(snapshot))
    throw new Error('A target snapshot from a successful source/target inventory verification is required.');
  const pinned = readPinnedSnapshot(root, snapshotPath);
  if (snapshotDigest(pinned) !== expectedSourceDigest)
    throw new Error('Plugin inventory pin changed during the database switch; target snapshot was not written.');
  parseLocalPluginInventorySnapshot(JSON.stringify(snapshot), 'verified target snapshot');
  const backupDirectory = path.resolve(root, '.runtime', 'backups');
  mkdirSync(backupDirectory, { recursive: true, mode: 0o700 });
  const timestamp = new Date().toISOString().replaceAll(':', '-');
  const backup = path.join(backupDirectory, `local-plugin-inventory.${timestamp}.${randomUUID()}.json`);
  copyFileSync(snapshotPathFor(root, snapshotPath), backup);
  writeSnapshotAtomic(root, snapshot, snapshotPath);
  verifiedTransferSnapshots.delete(snapshot);
  return snapshot;
}

function readConfig(root, file) {
  const absolute = path.resolve(root, file);
  let config;
  try {
    config = JSON.parse(readFileSync(absolute, 'utf8'));
  } catch {
    throw new Error(`Cannot read local configuration file ${path.relative(root, absolute)}.`);
  }
  if (!config || typeof config !== 'object' || Array.isArray(config))
    throw new Error(`Local configuration file is invalid: ${path.relative(root, absolute)}.`);
  return config;
}

function parseArguments(argv) {
  const [command, ...rest] = argv;
  if (!['check', 'verify-transfer'].includes(command))
    throw new Error('Usage: node scripts/local-plugin-inventory.mjs check [--config file] [--snapshot file] | verify-transfer --source-config file --target-config file; use scripts/local-runtime.mjs capture for pinned capture.');
  const options = {};
  for (let index = 0; index < rest.length; index += 1) {
    const key = rest[index];
    const value = rest[index + 1];
    if (!['--config', '--source-config', '--target-config', '--snapshot'].includes(key) || !value || value.startsWith('--'))
      throw new Error('Invalid local plugin inventory command arguments.');
    options[key] = value;
    index += 1;
  }
  return { command, options };
}

function main() {
  const { command, options } = parseArguments(process.argv.slice(2));
  if (command === 'check') {
    const config = readConfig(projectRoot, options['--config'] ?? '.runtime/local.json');
    const snapshot = assertLocalPluginInventory({ root: projectRoot, config, snapshotPath: options['--snapshot'] });
    console.log(`Plugin inventory matches the pin: ${snapshot.plugins.length} artifact records in ${snapshot.database.name}.`);
    return;
  }
  const sourceConfig = readConfig(projectRoot, options['--source-config'] ?? '.runtime/local.json');
  if (!options['--target-config']) throw new Error('verify-transfer requires --target-config.');
  const targetConfig = readConfig(projectRoot, options['--target-config']);
  const result = verifyLocalPluginInventoryTransfer({ root: projectRoot, sourceConfig, targetConfig, snapshotPath: options['--snapshot'] });
  console.log(`Verified transfer: ${result.snapshotForTarget.plugins.length} matching artifact records from ${result.snapshotForTarget.database.name}.`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
