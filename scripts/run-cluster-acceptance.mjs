import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// Run only in an owned immutable QA checkout, after acquiring performance.lock and
// that checkout's build-fixture.lock. The administrator command must be explicitly selected.
assert.equal(process.env.LUMIRA_CLUSTER_ACCEPTANCE_LOCKED, '1', 'Acquire both QA locks first');
const admin = process.env.LUMIRA_CLUSTER_ACCEPTANCE_PSQL_ADMIN;
assert.ok(admin && path.isAbsolute(admin), 'Explicit QA PostgreSQL administrator helper required');
const suffix = randomBytes(6).toString('hex');
const database = `lumira_qa_cluster_acceptance_${suffix}`;
const role = `lumira_qa_cluster_acceptance_${suffix}`;
const password = randomBytes(32).toString('hex');
const runToken = randomUUID();
const output = path.resolve('.runtime/architecture-fix/parallel-acceptance');
mkdirSync(output, { recursive: true, mode: 0o700 });
const psql = (db, input) => {
  const result = spawnSync(admin, ['-X', '-q', '-v', 'ON_ERROR_STOP=1', '-d', db],
    { input, encoding: 'utf8', timeout: 30_000 });
  // Do not print PostgreSQL errors which might include a credential-bearing statement.
  assert.equal(result.status, 0, `Private QA fixture initialization failed (database ${db})`);
};
psql('postgres', `CREATE ROLE ${role} LOGIN PASSWORD '${password}';\nCREATE DATABASE ${database} OWNER ${role};\n`);
psql(database, `SET ROLE ${role};
CREATE TABLE public.lumira_qa_fixture_guard (
  id boolean PRIMARY KEY CHECK (id), run_token uuid NOT NULL,
  purpose text NOT NULL CHECK (purpose='isolated-security-qa'));
INSERT INTO public.lumira_qa_fixture_guard VALUES(true,'${runToken}','isolated-security-qa');\n`);
const env = { ...process.env, LUMIRA_CLUSTER_ACCEPTANCE: '1',
  LUMIRA_TEST_DB_URL: `jdbc:postgresql://127.0.0.1:5432/${database}`,
  LUMIRA_TEST_DB_USER: role, LUMIRA_TEST_DB_PASSWORD: password, LUMIRA_QA_RUN_TOKEN: runToken };
writeFileSync(path.join(output, `fixture-${suffix}.json`), JSON.stringify({
  LUMIRA_TEST_DB_URL: env.LUMIRA_TEST_DB_URL, LUMIRA_TEST_DB_USER: role,
  LUMIRA_TEST_DB_PASSWORD: password, LUMIRA_QA_RUN_TOKEN: runToken,
}, null, 2), { mode: 0o600, flag: 'wx' });
const started = new Date().toISOString();
const args = ['-B', '-f', 'backend/pom.xml', 'test',
  '-Dtest=ClusterCommitBoundaryAcceptanceTest', '-DfailIfNoTests=true'];
const result = spawnSync('./backend/mvnw', args, { env, stdio: 'inherit', timeout: 15 * 60_000 });
const reportPath = path.resolve('backend/target/surefire-reports/TEST-com.lumira.platform.plugins.ClusterCommitBoundaryAcceptanceTest.xml');
let summary = null;
try {
  const xml = readFileSync(reportPath, 'utf8');
  const suite = xml.match(/<testsuite\b[^>]+>/)?.[0] ?? '';
  summary = Object.fromEntries(['tests', 'failures', 'errors', 'skipped'].map(name =>
    [name, Number(suite.match(new RegExp(`\\b${name}="(\\d+)"`))?.[1] ?? -1)]));
} catch { /* A missing report remains an explicit unsuccessful acceptance run. */ }
const passed = result.status === 0 && summary?.tests === 8
  && summary.failures === 0 && summary.errors === 0 && summary.skipped === 0;
writeFileSync(path.join(output, `result-${suffix}.json`), JSON.stringify({
  started, finished: new Date().toISOString(), database, exitCode: result.status,
  passed, summary, reportPath, scope: 'PostgreSQL commit and coordinator boundaries with fixture participant callbacks',
  realJvmRestartVerified: false, productionRuntimeCallbacksVerified: false, capacityVerified: false,
}, null, 2), { flag: 'wx' });
console.log(JSON.stringify({ stage: 'CLUSTER_ACCEPTANCE_FINISHED', database, passed, summary }));
process.exitCode = passed ? 0 : 1;
