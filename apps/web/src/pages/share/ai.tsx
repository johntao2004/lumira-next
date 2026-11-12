import { useParams } from "@umijs/max";
import { PublicConversationPage } from "@/platform/conversations/public-conversation-page";

/** Compatibility route; content and availability are provided by the optional AI plugin. */
export default function AiShareRoute() {
  const { token = "" } = useParams<{ token: string }>();
  return <PublicConversationPage token={token} endpoint="/public/ai/shares" />;
}
