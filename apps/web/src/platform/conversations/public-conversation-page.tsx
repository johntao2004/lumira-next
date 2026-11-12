import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { UserOutlined } from "@ant-design/icons";
import { Bubble } from "@ant-design/x";
import { Alert, Avatar, Button, Result, Spin, Typography } from "antd";
import { ApiError, api } from "@/platform/api/client";
import { usePreferences } from "@/platform/providers";
import { formatDateTime } from "@/platform/i18n/format";
import { AiChatMarkdown } from "./conversation-markdown";
import { AiAssistantAvatar } from "./assistant-avatar";
import { decodePublicAiChatShare } from "./share-contract";

export function PublicConversationPage({ token, endpoint }: { token: string; endpoint: string }) {
  const { t, dark, locale } = usePreferences();
  const share = useQuery({
    queryKey: ["public-conversation", endpoint, token],
    queryFn: async ({ signal }) => {
      const result = await api<unknown>(`${endpoint}/${encodeURIComponent(token)}`, { signal });
      const decoded = decodePublicAiChatShare(result);
      if (!decoded) throw new Error("Invalid shared conversation response");
      return decoded;
    },
    enabled: token.length > 0,
    staleTime: 0,
    gcTime: 0,
    refetchOnMount: "always",
    refetchOnWindowFocus: true,
    refetchInterval: 3000,
    retry: false,
  });

  useEffect(() => {
    const previousTitle = document.title;
    document.title = `${t("aiShareConversation")} · Lumira`;

    const appendMeta = (name: string, content: string) => {
      const meta = document.createElement("meta");
      meta.name = name;
      meta.content = content;
      document.head.append(meta);
      return () => meta.remove();
    };
    const removeRobots = appendMeta("robots", "noindex,nofollow,noarchive");
    const removeReferrer = appendMeta("referrer", "no-referrer");

    return () => {
      document.title = previousTitle;
      removeRobots();
      removeReferrer();
    };
  }, [t]);

  if (!token) return <NotFound />;
  if (share.isPending || (share.isFetching && !share.data)) {
    return <main className="ai-public-share-page"><div className="ai-public-share-status"><Spin size="large" aria-label={t("loading")} /></div></main>;
  }
  if (share.error) {
    if (share.error instanceof ApiError && share.error.status === 404) return <NotFound />;
    return <main className="ai-public-share-page"><div className="ai-public-share-status">
      <Alert
        type="error"
        showIcon
        title={t("aiShareLoadFailed")}
        action={<Button size="small" onClick={() => void share.refetch()}>{t("retry")}</Button>}
      />
    </div></main>;
  }

  const conversation = share.data;
  return <main className="ai-public-share-page">
    <article className="ai-public-share-card" aria-labelledby="ai-public-share-title">
      <header className="ai-public-share-header">
        <div>
          <Typography.Text className="ai-public-share-kicker" type="secondary">{t("aiShareConversation")}</Typography.Text>
          <Typography.Title id="ai-public-share-title" level={2} className="ai-public-share-title">
            {conversation.title.trim() || t("aiUntitledConversation")}
          </Typography.Title>
        </div>
        <time className="ai-public-share-date" dateTime={conversation.createdAt}>
          {formatDateTime(conversation.createdAt, locale)}
        </time>
      </header>

      <section className="ai-public-share-messages" aria-label={conversation.title}>
        <Bubble.List
          className="ai-public-share-bubbles"
          autoScroll={false}
          items={conversation.messages.map((item, index) => ({
            key: `${item.role}-${index}`,
            role: item.role,
            content: item.content,
            classNames: { content: "ai-chat-bubble-content" },
            header: <Typography.Text type="secondary">
              {t(item.role === "user" ? "aiTranscriptUser" : "aiTranscriptAssistant")}
            </Typography.Text>,
            contentRender: () => item.role === "assistant"
              ? <AiChatMarkdown content={item.content} dark={dark} />
              : item.content,
          }))}
          role={{
            user: { placement: "end", avatar: <Avatar size={32} icon={<UserOutlined />} /> },
            assistant: { placement: "start", avatar: <AiAssistantAvatar /> },
          }}
        />
      </section>
    </article>
  </main>;
}

function NotFound() {
  const { t } = usePreferences();
  return <main className="ai-public-share-page">
    <div className="ai-public-share-status">
      <Result status="404" title="404" subTitle={t("aiShareNotFound")} />
    </div>
  </main>;
}
