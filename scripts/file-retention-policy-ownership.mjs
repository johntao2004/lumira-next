import assert from 'node:assert/strict';

export function samePolicyData(left, right) {
  return left?.mode === right?.mode && left?.retentionDays === right?.retentionDays;
}

export function ownsPolicyRevision({ actor, current, revision, intents }) {
  if (typeof actor !== 'string' || actor.length === 0 || !current || !revision) return false;
  if (revision.version !== current.version || revision.changedBy !== actor) return false;
  if (!samePolicyData(revision.data, current.data)) return false;

  return intents.some(intent =>
    intent.expectedVersion === current.version
    && samePolicyData(intent.proposed, current.data));
}

export async function restoreOwnedPolicy({
  actor,
  baseline,
  intents,
  readCurrent,
  readRevision,
  save,
}) {
  assert.equal(typeof readCurrent, 'function');
  assert.equal(typeof readRevision, 'function');
  assert.equal(typeof save, 'function');
  if (!baseline) return { status: 'no-owned-write' };

  const current = await readCurrent();
  if (samePolicyData(current.data, baseline.data)) return { status: 'already-baseline', current };
  if (intents.length === 0) return { status: 'not-owned', current, revision: null };

  const revision = await readRevision(current.version);
  if (!ownsPolicyRevision({ actor, current, revision, intents })) {
    return { status: 'not-owned', current, revision };
  }

  // `save` must use current.version as the server-side CAS value. A conflict is
  // intentionally propagated; rereading and retrying would overwrite a newer
  // actor's policy.
  const saved = await save(current, baseline.data);
  const restored = await readCurrent();
  if (saved.version !== current.version + 1
    || restored.version !== saved.version
    || !samePolicyData(restored.data, baseline.data)) {
    throw new Error('The original file-retention policy was not restored at the expected guarded version.');
  }
  return { status: 'restored', current, saved, restored };
}
