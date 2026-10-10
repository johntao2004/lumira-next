import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const databaseNamespace = /^lumira_(?:security|qa|test|smoke)_[a-z0-9_]+$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const loopbackHosts = new Set(['127.0.0.1', 'localhost', '::1']);

function validateQaConfigFile({ projectRoot, configPath, configuredPath = process.env.LUMIRA_SMOKE_CONFIG }) {
  if (typeof configuredPath !== 'string' || configuredPath.trim() === '') {
    throw new Error('Set LUMIRA_SMOKE_CONFIG to an explicit private QA config; refusing the legacy local config.');
  }

  const resolvedConfigPath = path.resolve(projectRoot, configuredPath);
  if (resolvedConfigPath !== path.resolve(configPath)) {
    throw new Error('LUMIRA_SMOKE_CONFIG must identify the config used by this QA run.');
  }
  if (resolvedConfigPath === path.resolve(projectRoot, '.runtime', 'local.json')) {
    throw new Error('Refusing to use the legacy local.json config for fixture or browser QA.');
  }

  let file;
  try {
    file = lstatSync(resolvedConfigPath);
  } catch {
    throw new Error('The explicit QA config must exist before the smoke can run.');
  }
  if (!file.isFile() || file.isSymbolicLink()) {
    throw new Error('The QA config must be a regular private file, not a symlink.');
  }
  if ((file.mode & 0o077) !== 0) {
    throw new Error('The QA config must be private to its owner (mode 0600 or stricter).');
  }
  return resolvedConfigPath;
}

export function validateQaRunConfig({ projectRoot, configPath, config, baseURL, configuredPath = process.env.LUMIRA_SMOKE_CONFIG }) {
  const resolvedConfigPath = validateQaConfigFile({ projectRoot, configPath, configuredPath });

  let web;
  try {
    web = new URL(baseURL);
  } catch {
    throw new Error('BASE_URL must identify the loopback QA web origin.');
  }
  if (!['http:', 'https:'].includes(web.protocol) || !loopbackHosts.has(web.hostname)
    || web.username || web.password || web.pathname !== '/' || web.search || web.hash) {
    throw new Error('BASE_URL must be a loopback origin without credentials or a path.');
  }

  let database;
  try {
    database = new URL(String(config.LUMIRA_DB_URL ?? '').replace(/^jdbc:/, ''));
  } catch {
    throw new Error('LUMIRA_DB_URL must identify the dedicated local QA PostgreSQL database.');
  }
  if (database.protocol !== 'postgresql:' || !loopbackHosts.has(database.hostname)) {
    throw new Error('LUMIRA_DB_URL must use a loopback PostgreSQL database.');
  }
  const databaseName = decodeURIComponent(database.pathname.replace(/^\/+/, ''));
  if (!databaseNamespace.test(databaseName)) {
    throw new Error('The database name must use the dedicated lumira_{security|qa|test|smoke}_ namespace.');
  }

  const runToken = config.LUMIRA_QA_RUN_TOKEN;
  if (typeof runToken !== 'string' || !uuid.test(runToken)) {
    throw new Error('The private QA config must provide a valid LUMIRA_QA_RUN_TOKEN UUID.');
  }

  return { configPath: resolvedConfigPath, databaseName, runToken };
}

export function qaMarkerMatches(output, runToken) {
  const rows = String(output).trim().split(/\r?\n/).filter(Boolean);
  return rows.length === 1 && rows[0] === `${runToken.toLowerCase()}|isolated-security-qa`;
}

export function qaTargetBindingMatches(output) {
  return String(output).trim() === 'QA_TARGET_BOUND';
}

export function assertQaRunGuard({ projectRoot, configPath, config, baseURL, configuredPath, runProcess = execFileSync }) {
  const target = validateQaRunConfig({ projectRoot, configPath, config, baseURL, configuredPath });
  const query = 'SELECT run_token::text || \'|\' || purpose FROM public.lumira_qa_fixture_guard WHERE id = true;';
  let result;
  try {
    result = runProcess('python3', [
      '-c',
      'import sys; from smoke_support import sql; print(sql(sys.stdin.read()))',
    ], {
      cwd: path.join(projectRoot, 'scripts'),
      env: { ...process.env, LUMIRA_SMOKE_CONFIG: target.configPath, BASE_URL: baseURL },
      input: query,
      encoding: 'utf8',
      timeout: 15_000,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  } catch {
    throw new Error('The read-only QA marker check failed; refusing to create fixtures or open the browser.');
  }
  assert.ok(qaMarkerMatches(result, target.runToken), 'The configured token must match the single isolated-security-qa marker row.');

  const pythonEnv = { ...process.env, LUMIRA_SMOKE_CONFIG: target.configPath, BASE_URL: baseURL };
  const backendBaseURL = process.env.LUMIRA_BACKEND_BASE_URL || config.LUMIRA_BACKEND_BASE_URL;
  if (typeof backendBaseURL === 'string' && backendBaseURL) pythonEnv.LUMIRA_BACKEND_BASE_URL = backendBaseURL;
  let binding;
  try {
    binding = runProcess('python3', [
      '-c',
      'from smoke_support import ensure_qa_target_binding; ensure_qa_target_binding(); print("QA_TARGET_BOUND")',
    ], {
      cwd: path.join(projectRoot, 'scripts'),
      env: pythonEnv,
      encoding: 'utf8',
      timeout: 30_000,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch {
    throw new Error('The configured QA origin is not bound to the marked database session; refusing to create fixtures or open the browser.');
  }
  assert.ok(qaTargetBindingMatches(binding), 'The configured QA origin is not bound to the marked database session.');
  return target.databaseName;
}

export function loadQaRunContext() {
  const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const configuredPath = process.env.LUMIRA_SMOKE_CONFIG;
  const baseURL = process.env.BASE_URL;
  if (!configuredPath || !baseURL) {
    throw new Error('Set LUMIRA_SMOKE_CONFIG to a private isolated QA config and BASE_URL to its loopback origin.');
  }

  const configPath = path.isAbsolute(configuredPath) ? configuredPath : path.resolve(projectRoot, configuredPath);
  validateQaConfigFile({ projectRoot, configPath, configuredPath });
  let config;
  try {
    config = JSON.parse(readFileSync(configPath, 'utf8'));
  } catch {
    throw new Error('The explicitly selected QA config must be readable JSON.');
  }
  const databaseName = assertQaRunGuard({ projectRoot, configPath, config, baseURL });
  const origin = new URL(baseURL).origin;
  process.env.LUMIRA_SMOKE_CONFIG = configPath;
  process.env.BASE_URL = origin;
  return { config, configPath, baseURL: origin, databaseName, projectRoot };
}
