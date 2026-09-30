#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
source scripts/cloud-env.sh
bash scripts/cloud-database.sh
if [[ ${1:-all} != backend ]]; then
  pnpm typecheck
  pnpm test
  pnpm build
fi
# Keep development credentials out of command arguments and console output.
node --input-type=module <<'JS'
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
const c = JSON.parse(readFileSync('.runtime/local.json', 'utf8'));
const args = ['-f', 'backend/pom.xml', '-B', 'verify'];
if (process.env.LUMIRA_CLOUD_MAVEN_REPO) args.push(`-Dmaven.repo.local=${process.env.LUMIRA_CLOUD_MAVEN_REPO}`);
if (process.env.LUMIRA_CLOUD_MAVEN_SETTINGS) args.push('--settings', process.env.LUMIRA_CLOUD_MAVEN_SETTINGS);
const result = spawnSync('./backend/mvnw', args, {
  stdio: 'inherit',
  env: {...process.env, LUMIRA_TEST_DB_URL: c.LUMIRA_DB_URL,
    LUMIRA_TEST_DB_USER: c.LUMIRA_DB_USER, LUMIRA_TEST_DB_PASSWORD: c.LUMIRA_DB_PASSWORD},
});
if (result.error) throw result.error;
process.exit(result.status ?? 1);
JS
