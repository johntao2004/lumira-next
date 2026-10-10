import test from 'node:test';
import assert from 'node:assert/strict';
import { restoreOwnedPolicy } from '../scripts/file-retention-policy-ownership.mjs';

const original = { mode: 'AUTOMATIC', retentionDays: 30 };
const proposed = { mode: 'MANUAL', retentionDays: 30 };

function fixture({ current, revision, intents = [{ expectedVersion: 8, proposed }] }) {
  const writes = [];
  return {
    writes,
    restore: () => restoreOwnedPolicy({
      actor: 'qa_retention_runner_123',
      baseline: { version: 7, data: original },
      intents,
      readCurrent: async () => current,
      readRevision: async version => revision?.version === version ? revision : null,
      save: async (snapshot, data) => {
        writes.push({ version: snapshot.version, data });
        if (snapshot.version !== current.version) throw new Error('staleRecord');
        return { version: snapshot.version + 1, data };
      },
    }),
  };
}

test('[fake] an uncommitted intent cannot claim another actor’s same-value same-version revision', async () => {
  const state = fixture({
    current: { version: 8, data: proposed },
    revision: { version: 8, data: proposed, changedBy: 'qa_retention_other_456' },
  });

  const result = await state.restore();

  assert.equal(result.status, 'not-owned');
  assert.deepEqual(state.writes, []);
});

test('[fake] a durable own revision recovers after the browser loses the save response', async () => {
  let current = { version: 8, data: proposed };
  const writes = [];
  const result = await restoreOwnedPolicy({
    actor: 'qa_retention_runner_123',
    baseline: { version: 7, data: original },
    intents: [{ expectedVersion: 8, proposed }],
    readCurrent: async () => current,
    readRevision: async version => version === 8
      ? { version: 8, data: proposed, changedBy: 'qa_retention_runner_123' }
      : null,
    save: async (snapshot, data) => {
      writes.push({ version: snapshot.version, data });
      current = { version: snapshot.version + 1, data };
      return current;
    },
  });

  assert.equal(result.status, 'restored');
  assert.equal(writes.length, 1);
  assert.equal(writes[0].version, 8);
  assert.deepEqual(current, { version: 9, data: original });
});

test('[fake] a later competing save is left untouched even when it reuses the proposed value', async () => {
  const state = fixture({
    current: { version: 9, data: proposed },
    revision: { version: 9, data: proposed, changedBy: 'qa_retention_other_456' },
  });

  const result = await state.restore();

  assert.equal(result.status, 'not-owned');
  assert.deepEqual(state.writes, []);
});

test('[fake] a restore version conflict is fail-closed and never retries with a newer version', async () => {
  let writes = 0;
  await assert.rejects(restoreOwnedPolicy({
    actor: 'qa_retention_runner_123',
    baseline: { version: 7, data: original },
    intents: [{ expectedVersion: 8, proposed }],
    readCurrent: async () => ({ version: 8, data: proposed }),
    readRevision: async () => ({ version: 8, data: proposed, changedBy: 'qa_retention_runner_123' }),
    save: async snapshot => {
      writes += 1;
      assert.equal(snapshot.version, 8);
      throw new Error('staleRecord');
    },
  }), /staleRecord/);
  assert.equal(writes, 1);
});
