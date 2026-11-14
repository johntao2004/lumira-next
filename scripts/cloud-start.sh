#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
source scripts/cloud-env.sh
bash scripts/cloud-database.sh
# Always install from the refreshed lockfile and compile the current checkout.
pnpm install --frozen-lockfile
maven_args=(-f pom.xml -pl backend -am -B -DskipTests package)
[[ -z ${LUMIRA_CLOUD_MAVEN_REPO:-} ]] || maven_args+=("-Dmaven.repo.local=$LUMIRA_CLOUD_MAVEN_REPO")
[[ -z ${LUMIRA_CLOUD_MAVEN_SETTINGS:-} ]] || maven_args+=(--settings "$LUMIRA_CLOUD_MAVEN_SETTINGS")
./backend/mvnw "${maven_args[@]}"
pnpm plugins:build --no-host
# Published VM files can contain old PIDs. Never signal a process merely because
# it inherited the PID of a previous task's service.
node --input-type=module <<'JS'
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
const file = '.runtime/processes.json';
if (existsSync(file)) {
  const root = process.cwd();
  const owned = JSON.parse(readFileSync(file, 'utf8')).filter(({pid, name}) => {
    try {
      const args = readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0');
      return name === 'backend' ? args.some(a => a.startsWith(`${root}/.runtime/platform-server-`) && a.endsWith('.jar'))
        : name === 'web' && args.includes(`${root}/apps/web/node_modules/@umijs/max/bin/max.js`);
    } catch { return false; }
  });
  writeFileSync(file, JSON.stringify(owned), {mode: 0o600});
}
JS
# Only this prepared VM's registered development processes are stopped.
node scripts/stop.mjs
node --input-type=module <<'JS'
import { readdirSync, unlinkSync } from 'node:fs';
for (const file of readdirSync('.runtime'))
  if (file.startsWith('platform-server-') && file.endsWith('.jar')) unlinkSync(`.runtime/${file}`);
JS
node scripts/start.mjs
if ! node --input-type=module -e 'import {readFileSync} from "node:fs"; process.exit(JSON.parse(readFileSync(".runtime/local.json")).LUMIRA_SECRET_KEYS ? 0 : 1)'; then
  node scripts/init-secret-key.mjs
  node scripts/stop.mjs
  node scripts/start.mjs
fi
node scripts/status.mjs
