import type { ChatAttachment, ChatResponse, ExecutionStep } from "./types";

export type TranscriptItem = { key: number; role: "user" | "assistant"; content: string; attachments?: ChatAttachment[]; steps?: ExecutionStep[]; proposalId?: string; failed?: boolean };
export type ChatSession = {
  key: string;
  label: string;
  transcript: TranscriptItem[];
  users: ChatResponse["users"];
  draft: string;
  archived: boolean;
  providerId?: string;
  modelName?: string;
  draftAttachments?: ChatAttachment[];
};
export type ChatHistory = { sessions: ChatSession[]; activeSessionKey: string };
type HistoryStorage = Pick<Storage, "getItem" | "setItem">;

export type ChatTranscriptLabels = {
  untitledConversation: string;
  user: string;
  assistant: string;
  attachments: string;
};

function attachmentMetadata(files: ChatAttachment[] | undefined) {
  return files?.map(({ id, name, sizeBytes, image, truncated }) => ({ id, name, sizeBytes, image, truncated }));
}

export function createChatSession(): ChatSession {
  return { key: `chat-${crypto.randomUUID()}`, label: "", transcript: [], users: null, draft: "", archived: false };
}

export function createChatHistory(): ChatHistory {
  const session = createChatSession();
  return { sessions: [session], activeSessionKey: session.key };
}

export function chatHistoryStorageKey(owner: string): string {
  return `lumira-next.ai-history.v1.${encodeURIComponent(owner)}`;
}

/** Format only the saved transcript; callers must deliberately invoke sharing or copying. */
export function formatChatTranscript(session: ChatSession, labels: ChatTranscriptLabels): string {
  const title = session.label.trim() || labels.untitledConversation;
  const entries = session.transcript.map((entry) => {
    const attachmentNames = entry.attachments?.map(({ name }) => name).filter(Boolean) ?? [];
    const attachmentLine = attachmentNames.length ? `\n${labels.attachments}: ${attachmentNames.join(", ")}` : "";
    return `${entry.role === "user" ? labels.user : labels.assistant}:\n${entry.content}${attachmentLine}`;
  });
  return [title, ...entries].join("\n\n");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function decodeAttachments(value: unknown): ChatAttachment[] | undefined | null {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length > 4) return null;
  const attachments: ChatAttachment[] = [];
  for (const item of value) {
    if (!isRecord(item) || typeof item.id !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(item.id) ||
      typeof item.name !== "string" || item.name.length > 255 || typeof item.sizeBytes !== "number" ||
      !Number.isSafeInteger(item.sizeBytes) || item.sizeBytes < 0 || item.sizeBytes > 5 * 1024 * 1024 ||
      typeof item.image !== "boolean" || typeof item.truncated !== "boolean" || attachments.some(a => a.id === item.id)) return null;
    attachments.push({ id: item.id, name: item.name, sizeBytes: item.sizeBytes, image: item.image, truncated: item.truncated });
  }
  return attachments;
}

function decodeTranscript(value: unknown): TranscriptItem | null {
  if (!isRecord(value) || !Number.isSafeInteger(value.key) ||
    (value.role !== "user" && value.role !== "assistant") || typeof value.content !== "string" ||
    (value.failed !== undefined && typeof value.failed !== "boolean") ||
    (value.proposalId !== undefined && typeof value.proposalId !== "string")) return null;
  let steps: ExecutionStep[] | undefined;
  const attachments = decodeAttachments(value.attachments);
  if (attachments === null || (value.role === "assistant" && attachments?.length)) return null;
  if (value.steps !== undefined) {
    if (!Array.isArray(value.steps)) return null;
    steps = [];
    for (const step of value.steps) {
      if (!isRecord(step) || !["MODEL_RESPONSE", "USER_SEARCH", "ACTION_PROPOSAL", "ACTION_CONFIRMATION", "TOOL_VALIDATION"].includes(String(step.code)) ||
        !["success", "error", "pending", "abort"].includes(String(step.status))) return null;
      const hasModuleMetadata = !(step.moduleCode === undefined && step.operationCode === undefined) &&
        !(step.moduleCode === null && step.operationCode === null);
      if (hasModuleMetadata && (typeof step.moduleCode !== "string" || !/^[a-z][a-z0-9-]{0,63}$/.test(step.moduleCode) ||
        typeof step.operationCode !== "string" || !/^[a-z][a-z0-9-]{0,63}$/.test(step.operationCode))) return null;
      steps.push({
        code: step.code as ExecutionStep["code"],
        // A historical message must never reopen an actionable confirmation.
        status: step.status === "pending" ? "error" : step.status as ExecutionStep["status"],
        ...(hasModuleMetadata ? { moduleCode: step.moduleCode as string, operationCode: step.operationCode as string } : {}),
      });
    }
  }
  return { key: value.key as number, role: value.role, content: value.content, attachments, steps, proposalId: value.proposalId as string | undefined, failed: value.failed as boolean | undefined };
}

export function decodeChatHistory(payload: unknown, owner: string): ChatHistory | null {
  if (!isRecord(payload) || payload.version !== 1 || payload.owner !== owner ||
    !Array.isArray(payload.sessions) || payload.sessions.length === 0 || typeof payload.activeSessionKey !== "string") return null;
  const sessions: ChatSession[] = [];
  const keys = new Set<string>();
  for (const value of payload.sessions) {
    if (!isRecord(value) || typeof value.key !== "string" || !value.key || keys.has(value.key) ||
      typeof value.label !== "string" || typeof value.draft !== "string" || !Array.isArray(value.transcript) ||
      (value.archived !== undefined && typeof value.archived !== "boolean") ||
      (value.providerId !== undefined && typeof value.providerId !== "string") ||
      (value.modelName !== undefined && typeof value.modelName !== "string")) return null;
    const transcript: TranscriptItem[] = [];
    const draftAttachments = decodeAttachments(value.draftAttachments);
    if (draftAttachments === null) return null;
    const messageKeys = new Set<number>();
    for (const entry of value.transcript) {
      const item = decodeTranscript(entry);
      if (!item || messageKeys.has(item.key)) return null;
      transcript.push(item);
      messageKeys.add(item.key);
    }
    sessions.push({ key: value.key, label: value.label, draft: value.draft, archived: value.archived === true, draftAttachments, transcript, users: null, providerId: value.providerId as string | undefined, modelName: value.modelName as string | undefined });
    keys.add(value.key);
  }
  return { sessions, activeSessionKey: keys.has(payload.activeSessionKey) ? payload.activeSessionKey : sessions[0].key };
}

export function loadChatHistory(owner: string, storage: HistoryStorage): ChatHistory | null {
  const saved = storage.getItem(chatHistoryStorageKey(owner));
  if (saved === null) return null;
  const history = decodeChatHistory(JSON.parse(saved), owner);
  if (!history) throw new Error("Invalid chat history");
  return history;
}

export function saveChatHistory(owner: string, history: ChatHistory, storage: HistoryStorage): void {
  function serializeStep({ code, status, moduleCode, operationCode }: ExecutionStep): Record<string, unknown> {
    const serialized: Record<string, unknown> = { code, status };
    const moduleValue: unknown = moduleCode;
    const operationValue: unknown = operationCode;
    if (moduleValue === null && operationValue === null) return serialized;
    if (moduleValue !== undefined || operationValue !== undefined) {
      serialized.moduleCode = moduleValue;
      serialized.operationCode = operationValue;
    }
    return serialized;
  }

  storage.setItem(chatHistoryStorageKey(owner), JSON.stringify({
    version: 1,
    owner,
    activeSessionKey: history.activeSessionKey,
    sessions: history.sessions.map((session) => ({
      key: session.key, label: session.label, draft: session.draft, archived: session.archived, providerId: session.providerId, modelName: session.modelName,
      draftAttachments: attachmentMetadata(session.draftAttachments),
      transcript: session.transcript.map((entry) => ({
        key: entry.key, role: entry.role, content: entry.content, steps: entry.steps?.map(serializeStep),
        proposalId: entry.proposalId, failed: entry.failed,
        attachments: attachmentMetadata(entry.attachments),
      })),
    })),
  }));
}
