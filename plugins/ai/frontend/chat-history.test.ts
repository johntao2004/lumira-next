import test from "node:test";
import assert from "node:assert/strict";
import { archiveChatConversation, chatHistoryStorageKey, decodeChatHistory, formatChatTranscript, loadChatHistory, saveChatHistory, type ChatHistory, type ChatSession } from "./chat-history.ts";

const legacyHistory = {
  version: 1,
  owner: "alice",
  activeSessionKey: "chat-legacy",
  sessions: [{ key: "chat-legacy", label: "Earlier chat", draft: "", transcript: [] }],
};

test("decodes existing v1 histories without archive metadata as active conversations", () => {
  const serialized = JSON.stringify(legacyHistory);
  const storage = {
    getItem: (key: string) => key === chatHistoryStorageKey("alice") ? serialized : null,
    setItem: () => undefined,
  };

  const decoded = loadChatHistory("alice", storage);

  assert.equal(decoded?.sessions[0].archived, false);
  assert.equal(decoded?.sessions[0].label, "Earlier chat");
  assert.equal(decodeChatHistory(legacyHistory, "another-owner"), null);
});

test("keeps a conversation's share link across reloads within its owner history", () => {
  const share = { id: "618c71d5-ad36-486f-ac0f-24e78d87adb1", path: `/share/ai/${"a".repeat(43)}` };
  const history = decodeChatHistory(legacyHistory, "alice")!;
  history.sessions[0].share = share;
  const records = new Map<string, string>();
  const storage = {
    getItem: (key: string) => records.get(key) ?? null,
    setItem: (key: string, value: string) => records.set(key, value),
  };

  saveChatHistory("alice", history, storage);
  assert.deepEqual(loadChatHistory("alice", storage)?.sessions[0].share, share);
  assert.equal(loadChatHistory("bob", storage), null);
  const reloaded = loadChatHistory("alice", storage)!;
  reloaded.sessions[0].share = undefined;
  saveChatHistory("alice", reloaded, storage);
  assert.equal(loadChatHistory("alice", storage)?.sessions[0].share, undefined);
  assert.equal(loadChatHistory("alice", storage)?.sessions[0].label, "Earlier chat");
});

test("drops malformed share references without discarding the saved conversation", () => {
  for (const share of [
    { id: "not-a-uuid", path: `/share/ai/${"a".repeat(43)}` },
    { id: "618c71d5-ad36-486f-ac0f-24e78d87adb1", path: `https://example.com/share/ai/${"a".repeat(43)}` },
    { id: "618c71d5-ad36-486f-ac0f-24e78d87adb1", path: `/share/ai/${"a".repeat(43)}?redirect=elsewhere` },
  ]) {
    const history = decodeChatHistory({ ...legacyHistory, sessions: [{ ...legacyHistory.sessions[0], share }] }, "alice");
    assert.equal(history?.sessions[0].label, "Earlier chat");
    assert.equal(history?.sessions[0].share, undefined);
  }
});

test("round-trips operation metadata while keeping old v1 execution steps readable", () => {
  const session: ChatSession = {
    key: "chat-steps",
    label: "Search",
    transcript: [{
      key: 1,
      role: "assistant",
      content: "Search complete",
      steps: [
        { code: "MODEL_RESPONSE", status: "success" },
        { code: "USER_SEARCH", status: "success", moduleCode: "user-management", operationCode: "query" },
      ],
    }],
    users: null,
    draft: "",
    archived: false,
  };
  const history: ChatHistory = { sessions: [session], activeSessionKey: session.key };
  const records = new Map<string, string>();
  const storage = {
    getItem: (key: string) => records.get(key) ?? null,
    setItem: (key: string, value: string) => records.set(key, value),
  };

  saveChatHistory("alice", history, storage);
  assert.deepEqual(loadChatHistory("alice", storage)?.sessions[0].transcript[0].steps, session.transcript[0].steps);

  const legacy = JSON.parse(JSON.stringify(legacyHistory));
  legacy.sessions[0].transcript = [{ key: 2, role: "assistant", content: "Legacy", steps: [{ code: "USER_SEARCH", status: "success" }] }];
  assert.equal(decodeChatHistory(legacy, "alice")?.sessions[0].transcript[0].steps?.[0].moduleCode, undefined);
});

test("reads backend null metadata as absent, keeps pending confirmations inert, and omits null pairs on save", () => {
  const backendHistory = {
    ...legacyHistory,
    sessions: [{
      ...legacyHistory.sessions[0],
      transcript: [{
        key: 1,
        role: "assistant",
        content: "Model reply",
        steps: [
          { code: "MODEL_RESPONSE", status: "success", moduleCode: null, operationCode: null },
          { code: "ACTION_CONFIRMATION", status: "pending", moduleCode: null, operationCode: null },
        ],
      }],
    }],
  };
  const records = new Map<string, string>([[chatHistoryStorageKey("alice"), JSON.stringify(backendHistory)]]);
  const storage = {
    getItem: (key: string) => records.get(key) ?? null,
    setItem: (key: string, value: string) => records.set(key, value),
  };

  const loaded = loadChatHistory("alice", storage);
  assert.ok(loaded);
  const steps = loaded.sessions[0].transcript[0].steps;
  assert.equal(steps?.[0].moduleCode, undefined);
  assert.equal(steps?.[0].operationCode, undefined);
  assert.equal(steps?.[1].status, "error", "a persisted pending confirmation must never reopen");

  const pendingStep = steps?.[1] as unknown as Record<string, unknown>;
  pendingStep.moduleCode = null;
  pendingStep.operationCode = null;
  saveChatHistory("alice", loaded, storage);

  const saved = JSON.parse(records.get(chatHistoryStorageKey("alice")) ?? "null") as {
    sessions: Array<{ transcript: Array<{ steps: Array<Record<string, unknown>> }> }>;
  };
  for (const step of saved.sessions[0].transcript[0].steps) {
    assert.equal(Object.hasOwn(step, "moduleCode"), false);
    assert.equal(Object.hasOwn(step, "operationCode"), false);
  }
  assert.equal(loadChatHistory("alice", storage)?.sessions[0].transcript[0].steps?.[1].status, "error");
});

test("rejects partial, malformed, and invalid operation metadata", () => {
  const invalidMetadata = [
    { moduleCode: "user-management", operationCode: null },
    { moduleCode: null, operationCode: "query" },
    { moduleCode: "user-management" },
    { moduleCode: null },
    { moduleCode: 7, operationCode: "query" },
    { moduleCode: "User-management", operationCode: "query" },
    { moduleCode: "user-management", operationCode: false },
  ];

  for (const metadata of invalidMetadata) {
    const payload = {
      ...legacyHistory,
      sessions: [{
        ...legacyHistory.sessions[0],
        transcript: [{
          key: 1,
          role: "assistant",
          content: "Invalid metadata",
          steps: [{ code: "USER_SEARCH", status: "success", ...metadata }],
        }],
      }],
    };
    assert.equal(decodeChatHistory(payload, "alice"), null, JSON.stringify(metadata));
  }
});

test("round-trips archive state in the owner-scoped v1 history format", () => {
  const session: ChatSession = {
    key: "chat-archived",
    label: "Saved chat",
    transcript: [],
    users: null,
    draft: "",
    archived: true,
  };
  const history: ChatHistory = { sessions: [session], activeSessionKey: session.key };
  const records = new Map<string, string>();
  const storage = {
    getItem: (key: string) => records.get(key) ?? null,
    setItem: (key: string, value: string) => records.set(key, value),
  };

  saveChatHistory("alice", history, storage);

  const saved = JSON.parse(records.get(chatHistoryStorageKey("alice")) ?? "null") as { version: number; owner: string };
  assert.equal(saved.version, 1);
  assert.equal(saved.owner, "alice");
  assert.equal(loadChatHistory("alice", storage)?.sessions[0].archived, true);
});

test("archiving the active conversation selects another active chat and preserves the archived record", () => {
  const archived: ChatSession = {
    key: "chat-current",
    label: "Current",
    transcript: [{
      key: 1,
      role: "user",
      content: "Keep this history",
      attachments: [{ id: "01234567-89ab-cdef-0123-456789abcdef", name: "brief.txt", sizeBytes: 42, image: false, truncated: false }],
    }],
    users: null,
    draft: "Keep this draft",
    archived: false,
    providerId: "provider-one",
    modelName: "model-one",
    draftAttachments: [{ id: "11234567-89ab-cdef-0123-456789abcdef", name: "draft.txt", sizeBytes: 12, image: false, truncated: false }],
  };
  const other: ChatSession = { key: "chat-other", label: "Other", transcript: [], users: null, draft: "", archived: false };
  const history: ChatHistory = { sessions: [archived, other], activeSessionKey: archived.key };

  const next = archiveChatConversation(history, archived.key, "chat-unused-replacement");

  assert.equal(next.activeSessionKey, other.key);
  assert.equal(next.sessions.find((session) => session.key === archived.key)?.archived, true);
  assert.deepEqual(next.sessions.find((session) => session.key === archived.key), { ...archived, archived: true });
  assert.equal(history.activeSessionKey, archived.key, "the input history remains unchanged");
});

test("archiving a non-active conversation leaves the active selection unchanged", () => {
  const active: ChatSession = { key: "chat-active", label: "Active", transcript: [], users: null, draft: "", archived: false };
  const archived: ChatSession = { key: "chat-other", label: "Other", transcript: [], users: null, draft: "", archived: false };
  const history: ChatHistory = { sessions: [active, archived], activeSessionKey: active.key };

  const next = archiveChatConversation(history, archived.key, "chat-unused-replacement");

  assert.equal(next.activeSessionKey, active.key);
  assert.deepEqual(next.sessions, [active, { ...archived, archived: true }]);
});

test("archiving the last active conversation creates a blank one with the same provider and model", () => {
  const archived: ChatSession = {
    key: "chat-last",
    label: "Last active",
    transcript: [{ key: 1, role: "assistant", content: "Retain me" }],
    users: null,
    draft: "Retain my draft",
    archived: false,
    providerId: "provider-two",
    modelName: "model-two",
    draftAttachments: [{ id: "21234567-89ab-cdef-0123-456789abcdef", name: "notes.txt", sizeBytes: 9, image: false, truncated: false }],
  };
  const history: ChatHistory = { sessions: [archived], activeSessionKey: archived.key };

  const next = archiveChatConversation(history, archived.key, "chat-replacement");

  assert.equal(next.activeSessionKey, "chat-replacement");
  assert.deepEqual(next.sessions[0], {
    key: "chat-replacement",
    label: "",
    transcript: [],
    users: null,
    draft: "",
    archived: false,
    providerId: "provider-two",
    modelName: "model-two",
  });
  assert.deepEqual(next.sessions[1], { ...archived, archived: true }, "the original history, draft, and attachment metadata stay archived");
});

test("formats a readable transcript with role names and attachment filenames", () => {
  const session: ChatSession = {
    key: "chat-share",
    label: "",
    transcript: [
      {
        key: 1,
        role: "user",
        content: "Please summarize this file.",
        attachments: [{ id: "01234567-89ab-cdef-0123-456789abcdef", name: "brief.txt", sizeBytes: 42, image: false, truncated: false }],
      },
      { key: 2, role: "assistant", content: "Here is the summary." },
    ],
    users: null,
    draft: "",
    archived: false,
  };

  assert.equal(formatChatTranscript(session, {
    untitledConversation: "New conversation",
    user: "You",
    assistant: "Assistant",
    attachments: "Attachments",
  }), "New conversation\n\nYou:\nPlease summarize this file.\nAttachments: brief.txt\n\nAssistant:\nHere is the summary.");
});

test("rejects malformed archive metadata instead of silently interpreting it", () => {
  const invalidHistory = {
    ...legacyHistory,
    sessions: [{ ...legacyHistory.sessions[0], archived: "yes" }],
  };

  assert.equal(decodeChatHistory(invalidHistory, "alice"), null);
});
