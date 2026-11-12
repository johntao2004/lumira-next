import test from "node:test";
import assert from "node:assert/strict";
import { createAiChatShareSnapshot, decodePublicAiChatShare, validateAiChatShareSnapshot } from "./chat-share.ts";
import type { ChatSession } from "./chat-history.ts";
import { createChatShareSyncQueue, type ChatShareSyncEntry } from "./chat-share-sync.ts";
import { setImmediate } from "node:timers/promises";

function session(overrides: Partial<ChatSession> = {}): ChatSession {
  return {
    key: "local-chat-id",
    label: "  Example conversation  ",
    transcript: [
      {
        key: 1,
        role: "user",
        content: "Summarize the file",
        attachments: [{ id: "private-file-id", name: "private.docx", sizeBytes: 12, image: false, truncated: false }],
      },
      {
        key: 2,
        role: "assistant",
        content: "A summary",
        proposalId: "private-proposal-id",
        steps: [{ code: "MODEL_RESPONSE", status: "success", moduleCode: "users", operationCode: "read" }],
      },
    ],
    users: [{ id: "private-user-id", username: "private-user", displayName: "Private User", enabled: true, version: 1 }],
    draft: "unsent draft",
    draftAttachments: [{ id: "draft-file-id", name: "draft.txt", sizeBytes: 1, image: false, truncated: false }],
    archived: false,
    providerId: "private-provider-id",
    modelName: "private-model-name",
    share: { id: "618c71d5-ad36-486f-ac0f-24e78d87adb1", path: `/share/ai/${"a".repeat(43)}` },
    ...overrides,
  };
}

test("share snapshot contains only the title and saved message role/content", () => {
  const snapshot = createAiChatShareSnapshot(session(), "Untitled");
  assert.deepEqual(snapshot, {
    title: "Example conversation",
    messages: [
      { role: "user", content: "Summarize the file" },
      { role: "assistant", content: "A summary" },
    ],
  });
  assert.equal(JSON.stringify(snapshot).includes("private-file-id"), false);
  assert.equal(JSON.stringify(snapshot).includes("private-proposal-id"), false);
  assert.equal(JSON.stringify(snapshot).includes("unsent draft"), false);
  assert.equal(JSON.stringify(snapshot).includes("private-provider-id"), false);
  assert.equal(JSON.stringify(snapshot).includes("/share/ai/"), false);
});

test("share snapshot validation follows backend title, count, and UTF-8 byte bounds", () => {
  assert.equal(validateAiChatShareSnapshot({ title: "😀".repeat(200), messages: [{ role: "user", content: "ok" }] }), null);
  assert.equal(validateAiChatShareSnapshot({ title: "😀".repeat(201), messages: [{ role: "user", content: "ok" }] }), "title-too-long");
  assert.equal(validateAiChatShareSnapshot({ title: "Title", messages: [] }), "empty");
  assert.equal(validateAiChatShareSnapshot({ title: "Title", messages: Array.from({ length: 201 }, () => ({ role: "user" as const, content: "x" })) }), "too-many-messages");
  assert.equal(validateAiChatShareSnapshot({ title: "Title", messages: [{ role: "assistant", content: "😀".repeat(16_385) }] }), "message-too-large");
  assert.equal(validateAiChatShareSnapshot({ title: "😀".repeat(200), messages: Array.from({ length: 8 }, () => ({ role: "assistant" as const, content: "x".repeat(64 * 1024) })) }), null);
  assert.equal(validateAiChatShareSnapshot({ title: "Title", messages: Array.from({ length: 9 }, () => ({ role: "assistant" as const, content: "x".repeat(64 * 1024) })) }), "total-too-large");
});

test("public share decoder keeps only supported message fields and rejects malformed data", () => {
  assert.deepEqual(decodePublicAiChatShare({
    title: "Shared chat",
    createdAt: "2026-10-17T10:00:00Z",
    ownerId: "private-owner-id",
    messages: [{ role: "assistant", content: "Answer", proposalId: "private-proposal-id" }],
  }), {
    title: "Shared chat",
    createdAt: "2026-10-17T10:00:00Z",
    messages: [{ role: "assistant", content: "Answer" }],
  });
  assert.equal(decodePublicAiChatShare({ title: "Broken", createdAt: "not-a-date", messages: [{ role: "user", content: "text" }] }), null);
  assert.equal(decodePublicAiChatShare({ title: "Broken", createdAt: "2026-10-17T10:00:00Z", messages: [{ role: "system", content: "secret" }] }), null);
});

const syncEntry = (text: string): ChatShareSyncEntry => ({
  sessionKey: "chat-a", share: { id: "618c71d5-ad36-486f-ac0f-24e78d87adb1", path: `/share/ai/${"a".repeat(43)}` },
  snapshot: { title: "Chat", messages: [{ role: "user", content: text }] },
});

test("share synchronization serializes writes and sends only the latest pending messages", async () => {
  let release!: () => void;
  const first = new Promise<void>(resolve => { release = resolve; });
  const writes: string[] = [];
  const queue = createChatShareSyncQueue({
    write: async entry => { writes.push(entry.snapshot.messages[0].content); if (writes.length === 1) await first; },
    onFailure: () => assert.fail("unexpected sync failure"), onSuccess: () => {},
  });
  queue.reconcile([syncEntry("first")]);
  queue.reconcile([syncEntry("second")]);
  queue.reconcile([syncEntry("latest")]);
  assert.deepEqual(writes, ["first"]);
  release();
  await setImmediate();
  assert.deepEqual(writes, ["first", "latest"]);
  queue.reconcile([syncEntry("latest")]);
  assert.equal(writes.length, 2);
  queue.dispose();
});

test("a failed synchronization can retry without creating another share", async () => {
  let attempts = 0;
  let failures = 0;
  let successes = 0;
  const queue = createChatShareSyncQueue({
    write: async () => { if (++attempts === 1) throw new Error("offline"); },
    onFailure: () => { failures++; }, onSuccess: () => { successes++; },
  });
  queue.reconcile([syncEntry("new message")]);
  await setImmediate();
  queue.reconcile([syncEntry("new message")]);
  assert.equal(attempts, 1);
  queue.retry();
  await setImmediate();
  assert.deepEqual([attempts, failures, successes], [2, 1, 1]);
  queue.dispose();
});

test("removing a revoked share aborts pending sync and suppresses late callbacks", async () => {
  let release!: () => void;
  let signal!: AbortSignal;
  let callbacks = 0;
  const queue = createChatShareSyncQueue({
    write: async (_, currentSignal) => { signal = currentSignal; await new Promise<void>(resolve => { release = resolve; }); },
    onFailure: () => { callbacks++; }, onSuccess: () => { callbacks++; },
  });
  queue.reconcile([syncEntry("pending")]);
  queue.reconcile([]);
  assert.equal(signal.aborted, true);
  release();
  await setImmediate();
  queue.retry();
  assert.equal(callbacks, 0);
  queue.dispose();
});
