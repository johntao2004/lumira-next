import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// 合成命令只验证错误传播和内容比较；真实 PostgreSQL 演练另行执行。
// Synthetic commands test failure propagation and content comparison; real PostgreSQL drills run separately.
test('restore drill rejects partial catalog/digest output and corrupted restored content', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'lumira-restore-gate-'));
  const bin = path.join(root, 'bin');
  mkdirSync(bin);
  try {
    writeFileSync(path.join(bin, 'psql'), `#!/usr/bin/env python3
import os,sys,time
mode=os.environ.get('FAULT','')
a=sys.argv
if '-c' in a:
 sql=a[a.index('-c')+1]
 if 'json_agg' in sql:
  if mode=='hang': time.sleep(20)
  print('[["public","first"]]')
  if mode=='catalog': sys.exit(2)
 sys.exit(0)
for line in sys.stdin:
 if 'pg_export_snapshot' in line:
  if mode=='snapshot-hang': time.sleep(20)
  print('00000003-00000001-1',flush=True)
 if 'COPY (' in line:
  if mode=='digest-hang': time.sleep(20)
  restored=a[a.index('-d')+1].startswith('lumira_restore_drill_')
  print('changed' if mode=='corrupt' and restored else 'original',flush=True)
  if mode=='digest': sys.exit(2)
 if line.startswith('\\\\q'): break
`, { mode: 0o755 });
    writeFileSync(path.join(bin, 'pg_dump'), '#!/usr/bin/env python3\nimport sys\nopen(sys.argv[sys.argv.index("-f")+1],"w").write("synthetic")\n', { mode: 0o755 });
    writeFileSync(path.join(bin, 'pg_restore'), '#!/usr/bin/env bash\nexit 0\n', { mode: 0o755 });
    const run = (fault) => spawnSync('bash', ['scripts/backup-restore-drill.sh', 'synthetic', path.join(root, 'backups')], { env: { ...process.env, PG_BIN: bin, FAULT: fault, LUMIRA_BACKUP_TIMEOUT_SECONDS: "1" }, encoding: 'utf8', timeout: 15000 });
    const clean = run('');
    assert.equal(clean.status, 0, clean.stderr);
    for (const fault of ['catalog', 'digest', 'corrupt', 'hang', 'snapshot-hang', 'digest-hang']) {
      const result = run(fault);
      assert.equal(result.status, 1, fault + ": " + result.stderr);
      assert.doesNotMatch(result.stdout, /drill passed/);
    }
  } finally { rmSync(root, { recursive: true, force: true }); }
});
