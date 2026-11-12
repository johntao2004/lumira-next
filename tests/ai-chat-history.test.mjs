import test from 'node:test';
import assert from 'node:assert/strict';
import { chatHistoryStorageKey, createChatHistory, decodeChatHistory, loadChatHistory, saveChatHistory } from '../plugins/ai/frontend/chat-history.ts';

function memoryStorage() {
  const entries = new Map();
  return { getItem: key => entries.get(key) ?? null, setItem: (key, value) => entries.set(key, value) };
}
const persistedFields = value => JSON.parse(JSON.stringify(value));

test('history survives remounts with account isolation, active selection, independent drafts and models', () => {
  const storage = memoryStorage();
  const first = createChatHistory(), second = createChatHistory();
  Object.assign(first.sessions[0], {
    label: 'First topic', draft: 'Continue the first topic', providerId: 'provider-a', modelName: 'model-a',
    transcript: [{ key: 1, role: 'user', content: 'First topic' }, { key: 2, role: 'assistant', content: 'First answer' }],
  });
  Object.assign(second.sessions[0], { label: 'Second topic', draft: 'Second draft', providerId: 'provider-b', modelName: 'model-b' });
  first.sessions.unshift(second.sessions[0]);
  saveChatHistory('alice', first, storage);
  assert.deepEqual(persistedFields(loadChatHistory('alice', storage)), persistedFields(first));
  assert.equal(loadChatHistory('bob', storage), null);
  const bob = createChatHistory();
  saveChatHistory('bob', bob, storage);
  assert.deepEqual(persistedFields(loadChatHistory('alice', storage)), persistedFields(first));
  assert.deepEqual(persistedFields(loadChatHistory('bob', storage)), persistedFields(bob));
  assert.notEqual(chatHistoryStorageKey('alice'), chatHistoryStorageKey('alice/bob'));
});

test('restored history never exposes stored user snapshots, credentials or actionable proposals', () => {
  const storage = memoryStorage(), history = createChatHistory();
  Object.assign(history.sessions[0], {
    users: [{ id: 'private-user', username: 'private', displayName: 'Private', enabled: true, version: 1 }],
    password: 'never-store-this', proposal: { id: 'action-id', kind: 'CREATE_USER' },
    transcript: [{ key: 1, role: 'assistant', content: 'A proposed action', proposalId: 'action-id', steps: [{ code: 'ACTION_CONFIRMATION', status: 'pending' }] }],
  });
  saveChatHistory('alice', history, storage);
  const saved = storage.getItem(chatHistoryStorageKey('alice'));
  assert.ok(!saved.includes('private-user'));
  assert.ok(!saved.includes('never-store-this'));
  assert.ok(!saved.includes('CREATE_USER'));
  const restored = loadChatHistory('alice', storage).sessions[0];
  assert.equal(restored.users, null);
  assert.equal(restored.password, undefined);
  assert.equal(restored.proposal, undefined);
  assert.equal(restored.transcript[0].steps[0].status, 'error');
  assert.equal(restored.transcript[0].proposalId, 'action-id');
});

test('v1 histories accept legacy steps and persist bounded module and operation codes', () => {
  const storage = memoryStorage(), history = createChatHistory();
  history.sessions[0].transcript = [{ key: 2, role: 'assistant', content: 'Found results', steps: [
    { code: 'MODEL_RESPONSE', status: 'success' },
    { code: 'USER_SEARCH', status: 'success', moduleCode: 'user-management', operationCode: 'query' },
  ] }];
  saveChatHistory('alice', history, storage);
  const saved = JSON.parse(storage.getItem(chatHistoryStorageKey('alice')));
  assert.deepEqual(saved.sessions[0].transcript[0].steps, history.sessions[0].transcript[0].steps);
  assert.deepEqual(loadChatHistory('alice', storage).sessions[0].transcript[0].steps[1], {
    code: 'USER_SEARCH', status: 'success', moduleCode: 'user-management', operationCode: 'query',
  });

  const legacy = JSON.parse(JSON.stringify(saved));
  legacy.sessions[0].transcript[0].steps[1] = { code: 'USER_SEARCH', status: 'success' };
  assert.equal(decodeChatHistory(legacy, 'alice').sessions[0].transcript[0].steps[1].moduleCode, undefined);
  for (const invalidStep of [
    { code: 'USER_SEARCH', status: 'success', moduleCode: 'user management', operationCode: 'query' },
    { code: 'USER_SEARCH', status: 'success', moduleCode: 'user-management', operationCode: 'query alice' },
    { code: 'USER_SEARCH', status: 'success', moduleCode: 'user-management' },
  ]) {
    const invalid = JSON.parse(JSON.stringify(saved));
    invalid.sessions[0].transcript[0].steps[1] = invalidStep;
    assert.equal(decodeChatHistory(invalid, 'alice'), null);
  }
});

test('invalid history and storage failures do not silently report successful persistence', () => {
  const storage = memoryStorage(), history = createChatHistory();
  saveChatHistory('alice', history, storage);
  const valid = JSON.parse(storage.getItem(chatHistoryStorageKey('alice')));
  for (const payload of [
    null, [], { ...valid, version: 2 }, { ...valid, owner: 'bob' }, { ...valid, sessions: [] },
    { ...valid, sessions: [valid.sessions[0], valid.sessions[0]] },
    { ...valid, sessions: [{ ...valid.sessions[0], draft: 42 }] },
    { ...valid, sessions: [{ ...valid.sessions[0], transcript: [{ key: 1, role: 'system', content: 'Invalid' }] }] },
    { ...valid, sessions: [{ ...valid.sessions[0], transcript: [{ key: 1, role: 'assistant', content: 'Invalid', steps: [{ code: 'ACTION_CONFIRMATION', status: 'unknown' }] }] }] },
  ]) assert.equal(decodeChatHistory(payload, 'alice'), null);
  assert.equal(decodeChatHistory({ ...valid, activeSessionKey: 'missing' }, 'alice').activeSessionKey, history.sessions[0].key);
  storage.setItem(chatHistoryStorageKey('alice'), '{broken');
  assert.throws(() => loadChatHistory('alice', storage));
  assert.throws(() => saveChatHistory('alice', history, { setItem() { throw new Error('QuotaExceededError'); } }), /QuotaExceededError/);
});

test('attachment references survive drafts, sent messages and reload without persisting file bytes', () => {
  const storage = memoryStorage(), history = createChatHistory();
  const file = { id: '4b0fe9d0-a303-4459-9124-369f970e0a80', name: 'report.pdf', sizeBytes: 100, image: false, truncated: false, dataUrl: 'never-store-image-bytes' };
  history.sessions[0].draftAttachments = [file];
  history.sessions[0].transcript = [{ key: 1, role: 'user', content: 'Summarize', attachments: [file] }];
  saveChatHistory('alice', history, storage);
  const saved = storage.getItem(chatHistoryStorageKey('alice'));
  assert.ok(!saved.includes('never-store-image-bytes'));
  const restored = loadChatHistory('alice', storage);
  assert.equal(restored.sessions[0].draftAttachments[0].id, file.id);
  assert.equal(restored.sessions[0].transcript[0].attachments[0].name, 'report.pdf');
  const payload = JSON.parse(saved);
  payload.sessions[0].draftAttachments[0].id = 'invalid-id';
  assert.equal(decodeChatHistory(payload, 'alice'), null);
});
