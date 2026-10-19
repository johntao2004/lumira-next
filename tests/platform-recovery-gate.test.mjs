import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

// 安全前置失败必须早于数据库操作；恢复行为另由真实 PostgreSQL 门禁验证。
// Safety preconditions must fail before database actions; real PostgreSQL verifies recovery behavior.
test('platform recovery requires explicit disposable QA and enabled assertions', () => {
  const env = { ...process.env, PGHOST: '127.0.0.1', LUMIRA_RECOVERY_QA: '' };
  const missing = spawnSync('python3', ['scripts/verify-platform-recovery.py'], { env, encoding: 'utf8' });
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /Set LUMIRA_RECOVERY_QA=1/);
  const optimized = spawnSync('python3', ['-O', 'scripts/verify-platform-recovery.py'], { env, encoding: 'utf8' });
  assert.equal(optimized.status, 1);
  assert.match(optimized.stderr, /Assertions must remain enabled/);
});

test('CI wires real package recovery and only sanitized evidence', () => {
  const ci = readFileSync('.github/workflows/ci.yml', 'utf8');
  assert.match(ci, /node scripts\/build-plugins.mjs --no-host/);
  assert.match(ci, /python3 scripts\/verify-platform-recovery.py/);
  assert.match(ci, /path: \.runtime\/platform-restore-results.json/);
  const source = readFileSync('scripts/verify-platform-recovery.py', 'utf8');
  assert.match(source, /error.returncode == 42/);
  assert.match(source, /cleanupErrors/);
  assert.doesNotMatch(source, /--no-owner|--no-privileges/);
});
