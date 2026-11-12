import type { ChatSession } from "./chat-history";
import type { AiChatShareSnapshot } from "../../../apps/web/src/platform/conversations/share-contract.ts";
export * from "../../../apps/web/src/platform/conversations/share-contract.ts";

export function createAiChatShareSnapshot(session: ChatSession, untitledConversation: string): AiChatShareSnapshot {
  return {
    title: session.label.trim() || untitledConversation,
    messages: session.transcript.map(({ role, content }) => ({ role, content })),
  };
}
