import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

test('core comments gate rejects missing bilingual method contracts without accepting a file header alone', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'lumira-comments-'));
  try {
    mkdirSync(path.join(root, 'scripts'));
    mkdirSync(path.join(root, 'docs'));
    const relative = 'framework/contracts/src/main/java/example/Probe.java';
    mkdirSync(path.dirname(path.join(root, relative)), { recursive: true });
    copyFileSync('scripts/check-core-comments.py', path.join(root, 'scripts/check-core-comments.py'));
    writeFileSync(path.join(root, 'docs/core-file-inventory.json'), JSON.stringify({ [relative]: {} }));
    const run = () => spawnSync('python3', [path.join(root, 'scripts/check-core-comments.py')], { encoding: 'utf8' });
    const header = '/** 公开探针契约。 Public probe contract. */\npackage example;\npublic interface Probe {\n';
    writeFileSync(path.join(root, relative), header + '/** 查询当前状态。 Returns the current state. */\nboolean ready();\n}');
    assert.equal(run().status, 0);
    writeFileSync(path.join(root, relative), header + '/** 查询当前状态。 Returns the current state. */\nboolean ready();\n// Historical English-only explanation.\n}');
    assert.notEqual(run().status, 0);
    writeFileSync(path.join(root, relative), header + '/** 查询当前状态。 Returns the current state. */\nboolean ready();\nString ROUTE = \"/api/**/items\";\n}');
    assert.equal(run().status, 0);
    writeFileSync(path.join(root, relative), header + '/** Returns the current state. */\nboolean ready();\n}');
    assert.notEqual(run().status, 0);
    writeFileSync(path.join(root, relative), header + 'boolean ready();\n}');
    assert.notEqual(run().status, 0);
    writeFileSync(path.join(root, relative), header + 'default boolean ready() { return true; }\n}');
    assert.notEqual(run().status, 0);
    writeFileSync(path.join(root, relative), header + '/** 查询当前状态。 Returns the current state. */\ndefault boolean ready() { return true; }\n}');
    assert.equal(run().status, 0);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
