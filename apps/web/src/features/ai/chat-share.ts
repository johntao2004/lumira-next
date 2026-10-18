import type { ChatSession } from "./chat-history";

export type AiChatShareMessage = { role: "user" | "assistant"; content: string };
export type AiChatShareSnapshot = { title: string; messages: AiChatShareMessage[] };
export type AiChatShareValidationError =
  | "empty"
  | "title-too-long"
  | "too-many-messages"
  | "message-too-large"
  | "total-too-large";

export type PublicAiChatShare = AiChatShareSnapshot & { createdAt: string };

const MAX_TITLE_CHARACTERS = 200;
const MAX_MESSAGES = 200;
const MAX_MESSAGE_BYTES = 64 * 1024;
const MAX_TOTAL_BYTES = 512 * 1024;

/** Build the explicit share payload from message text only; omit drafts and all UI metadata. */
export function createAiChatShareSnapshot(session: ChatSession, untitledConversation: string): AiChatShareSnapshot {
  return {
    title: session.label.trim() || untitledConversation,
    messages: session.transcript.map(({ role, content }) => ({ role, content })),
  };
}

export function validateAiChatShareSnapshot(snapshot: AiChatShareSnapshot): AiChatShareValidationError | null {
  if (snapshot.messages.length === 0) return "empty";
  if (Array.from(snapshot.title).length > MAX_TITLE_CHARACTERS) return "title-too-long";
  if (snapshot.messages.length > MAX_MESSAGES) return "too-many-messages";

  const encoder = new TextEncoder();
  let totalBytes = 0;
  for (const message of snapshot.messages) {
    const bytes = encoder.encode(message.content).byteLength;
    if (bytes > MAX_MESSAGE_BYTES) return "message-too-large";
    totalBytes += bytes;
    if (totalBytes > MAX_TOTAL_BYTES) return "total-too-large";
  }
  return null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

/** Validate the public response before rendering remote content. */
export function decodePublicAiChatShare(value: unknown): PublicAiChatShare | null {
  if (!isRecord(value) || typeof value.title !== "string" || typeof value.createdAt !== "string" || !Array.isArray(value.messages)) return null;
  if (value.messages.length < 1 || value.messages.length > MAX_MESSAGES || Number.isNaN(Date.parse(value.createdAt))) return null;
  const messages: AiChatShareMessage[] = [];
  for (const item of value.messages) {
    if (!isRecord(item) || (item.role !== "user" && item.role !== "assistant") || typeof item.content !== "string") return null;
    messages.push({ role: item.role, content: item.content });
  }
  const snapshot = { title: value.title, messages };
  if (validateAiChatShareSnapshot(snapshot) !== null) return null;
  return { ...snapshot, createdAt: value.createdAt };
}
