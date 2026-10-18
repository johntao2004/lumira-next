import { Avatar } from "antd";

/** Shared identity and fixed dimensions for chat and public conversation messages. */
export function AiAssistantAvatar() {
  return <Avatar size={32} className="ai-chat-assistant-avatar" icon={
    <svg className="ai-chat-assistant-mark" viewBox="0 0 24 24" role="img" aria-label="AI" focusable="false">
      <path d="M12 2.5 14.55 9.45 21.5 12 14.55 14.55 12 21.5 9.45 14.55 2.5 12 9.45 9.45 12 2.5Z" fill="currentColor" />
    </svg>
  } />;
}
