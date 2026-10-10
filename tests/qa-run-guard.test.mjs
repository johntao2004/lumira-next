import assert from 'node:assert/strict';
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { assertQaRunGuard, qaMarkerMatches, qaTargetBindingMatches, validateQaRunConfig } from '../scripts/qa-run-guard.mjs';

const token = 'd42ca763-c6ea-49cc-b826-e2c799c90de4';

function withPrivateConfig(run, { databaseName = 'lumira_qa_security_smoke', mode = 0o600 } = {}) {
  const projectRoot = mkdtempSync(path.join(os.tmpdir(), 'lumira-qa-guard-'));
  const configPath = path.join(projectRoot, 'qa-config.json');
  writeFileSync(configPath, '{}', { mode });
  chmodSync(configPath, mode);
  const config = {
    LUMIRA_DB_URL: `jdbc:postgresql://127.0.0.1:5432/${databaseName}`,
    LUMIRA_QA_RUN_TOKEN: token,
  };
  try {
    return run({
      projectRoot,
      configPath,
      config,
      baseURL: 'http://127.0.0.1:8010',
      configuredPath: configPath,
    });
  } finally {
    rmSync(projectRoot, { recursive: true, force: true });
  }
}

test('accepts an explicit private config in the strict Lumira QA namespace', () => {
  withPrivateConfig(input => {
    assert.deepEqual(validateQaRunConfig(input), {
      configPath: input.configPath,
      databaseName: 'lumira_qa_security_smoke',
      runToken: token,
    });
  });
});

test('refuses missing explicit config and the legacy local config', () => {
  withPrivateConfig(input => {
    assert.throws(() => validateQaRunConfig({ ...input, configuredPath: '' }), /explicit private QA config/);
    const legacyPath = path.join(input.projectRoot, '.runtime', 'local.json');
    mkdirSync(path.dirname(legacyPath), { recursive: true });
    writeFileSync(legacyPath, '{}', { mode: 0o600 });
    chmodSync(legacyPath, 0o600);
    assert.throws(() => validateQaRunConfig({ ...input, configPath: legacyPath, configuredPath: legacyPath }), /legacy local.json/);
  });
});

test('rejects weak database names, absent marker tokens, and public config permissions', () => {
  withPrivateConfig(input => {
    assert.throws(() => validateQaRunConfig({ ...input, config: { ...input.config, LUMIRA_DB_URL: 'jdbc:postgresql://127.0.0.1:5432/security-prod' } }), /dedicated lumira/);
    assert.throws(() => validateQaRunConfig({ ...input, config: { LUMIRA_DB_URL: input.config.LUMIRA_DB_URL } }), /LUMIRA_QA_RUN_TOKEN/);
  });
  withPrivateConfig(input => {
    assert.throws(() => validateQaRunConfig(input), /private to its owner/);
  }, { mode: 0o644 });
});

test('rejects non-loopback web or database targets', () => {
  withPrivateConfig(input => {
    assert.throws(() => validateQaRunConfig({ ...input, baseURL: 'https://example.com' }), /loopback origin/);
    assert.throws(() => validateQaRunConfig({ ...input, config: { ...input.config, LUMIRA_DB_URL: 'jdbc:postgresql://db.example.com:5432/lumira_qa_security_smoke' } }), /loopback PostgreSQL/);
  });
});

test('matches exactly one isolated-security marker row without exposing the token', () => {
  assert.equal(qaMarkerMatches(`${token}|isolated-security-qa\n`, token), true);
  assert.equal(qaMarkerMatches(`${token}|other-purpose`, token), false);
  assert.equal(qaMarkerMatches(`${token}|isolated-security-qa\n${token}|isolated-security-qa`, token), false);
  assert.equal(qaMarkerMatches('no marker row', token), false);
});

test('checks the marker before requiring Python to bind the app session to that database', () => {
  withPrivateConfig(input => {
    const calls = [];
    const databaseName = assertQaRunGuard({
      ...input,
      runProcess: (command, args, options) => {
        calls.push({ command, args, options });
        if (args[1].includes('from smoke_support import sql')) return `${token}|isolated-security-qa\n`;
        if (args[1].includes('ensure_qa_target_binding')) return 'QA_TARGET_BOUND\n';
        throw new Error('Unexpected guard subprocess');
      },
    });

    assert.equal(databaseName, 'lumira_qa_security_smoke');
    assert.equal(calls.length, 2);
    assert.ok(calls[0].options.input.includes('lumira_qa_fixture_guard'));
    assert.ok(calls[1].args[1].includes('ensure_qa_target_binding'));
    assert.equal(calls[1].options.timeout, 30_000);
    assert.equal(calls[1].options.env.BASE_URL, input.baseURL);
    assert.equal(calls[1].options.env.LUMIRA_SMOKE_CONFIG, input.configPath);
  });
});

test('refuses successful marker checks when target binding fails', () => {
  withPrivateConfig(input => {
    let call = 0;
    assert.throws(() => assertQaRunGuard({
      ...input,
      runProcess: () => {
        call += 1;
        return call === 1 ? `${token}|isolated-security-qa\n` : 'not-bound\n';
      },
    }), /not bound to the marked database session/);
    assert.equal(call, 2);
  });
});

test('accepts only the fixed target-binding success marker', () => {
  assert.equal(qaTargetBindingMatches('QA_TARGET_BOUND\n'), true);
  assert.equal(qaTargetBindingMatches('not-bound'), false);
  assert.equal(qaTargetBindingMatches('QA_TARGET_BOUND\nextra-output'), false);
});
