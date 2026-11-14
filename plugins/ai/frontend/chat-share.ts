import type { ChatSession } from "./chat-history";
import type { AiChatShareSnapshot } from "../../sdk/frontend/ai-chat-share-contract.ts";
export * from "../../sdk/frontend/ai-chat-share-contract.ts";

export function createAiChatShareSnapshot(session: ChatSession, untitledConversation: string): AiChatShareSnapshot {
  return {
    title: session.label.trim() || untitledConversation,
    messages: session.transcript.map(({ role, content }) => ({ role, content })),
  };
}
