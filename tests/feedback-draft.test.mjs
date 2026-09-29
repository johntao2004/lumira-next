import test from 'node:test';
import assert from 'node:assert/strict';
import { decodeFeedbackDraft, encodeFeedbackDraft } from '../apps/web/src/features/feedback/feedback-draft.ts';

test('feedback draft keeps only create fields and rejects incompatible payloads', () => {
  const encoded = encodeFeedbackDraft({ title: 'partial', priority: 'HIGH', category: 'BUG', detailHtml: '<p>details</p>', version: 99, ownerId: 'server-id' });
  assert.deepEqual(encoded, { schema: 1, values: { title: 'partial', priority: 'HIGH', category: 'BUG', detailHtml: '<p>details</p>' } });
  assert.deepEqual(decodeFeedbackDraft(encoded), encoded.values);
  for (const value of [
    null,
    [],
    { schema: 2, values: encoded.values },
    { schema: 1, values: { ...encoded.values, title: 'x'.repeat(161) } },
    { schema: 1, values: { ...encoded.values, priority: 1 } },
    { schema: 1, values: { ...encoded.values, category: 'x'.repeat(65) } },
    { schema: 1, values: { ...encoded.values, category: 1 } },
    { schema: 1, values: { ...encoded.values, detailHtml: 'x'.repeat(1_000_001) } },
  ]) assert.equal(decodeFeedbackDraft(value), null);
  assert.deepEqual(decodeFeedbackDraft({ schema: 1, values: { ...encoded.values, version: 999 } }), encoded.values);
});
