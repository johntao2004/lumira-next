"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Actions, Bubble, Conversations, Prompts, Sender, ThoughtChain, Welcome } from "@ant-design/x";
import { XMarkdown } from "@ant-design/x-markdown";
import { BulbOutlined, CommentOutlined, CopyOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, SettingOutlined } from "@ant-design/icons";
import { Alert, App, Button, Descriptions, Input, Select, Space, Spin, Table, Tag, Typography } from "antd";
import { api, mutate } from "@/platform/api/client";
import { usePreferences } from "@/platform/providers";
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZE_OPTIONS } from "@/platform/api/pagination";
import type { ActionProposal, ChatMessage, ChatResponse, ConnectionResult, ExecutionStep, Provider } from "./types";

type TranscriptItem = { key: number; role: "user" | "assistant"; content: string; steps?: ExecutionStep[]; proposalId?: string; failed?: boolean };
type ChatSession = { key: string; label: string; transcript: TranscriptItem[]; users: ChatResponse["users"] };
const path = "/platform/ai";
const initialSession: ChatSession = { key: "current", label: "", transcript: [], users: null };

export function AiConsolePage({ canManageModels, canReadUsers, canWriteUsers }: { canManageModels: boolean; canReadUsers: boolean; canWriteUsers: boolean }) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [providerId, setProviderId] = useState<string>();
  const [modelName, setModelName] = useState<string>();
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [sessionsOpen, setSessionsOpen] = useState(false);
  const [sessions, setSessions] = useState<ChatSession[]>([initialSession]);
  const [activeSessionKey, setActiveSessionKey] = useState(initialSession.key);
  const [proposal, setProposal] = useState<ActionProposal | null>(null);
  const [password, setPassword] = useState("");
  const [confirmBusy, setConfirmBusy] = useState(false);
  const confirming = useRef(false);
  const activeChatRequest = useRef<{
    sessionKey: string;
    content: string;
    previousTranscript: TranscriptItem[];
    previousLabel: string;
    previousUsers: ChatSession["users"];
    controller: AbortController;
  } | null>(null);
  const providers = useQuery({ queryKey: ["ai-providers"], queryFn: ({ signal }) => api<Provider[]>(`${path}/providers`, { signal }) });
  const enabledProviders = (providers.data ?? []).filter((provider) => provider.enabled && (
    provider.providerKind === "TYPESAFE_JEV" || provider.capabilityCodes.includes("CHAT")
  ));
  const chatProviders = useMemo(() => (providers.data ?? []).filter((provider) => provider.enabled && provider.providerKind !== "TYPESAFE_JEV" && provider.capabilityCodes.includes("CHAT")), [providers.data]);
  const selected = chatProviders.find((provider) => provider.id === providerId);
  const modelCatalog = useQuery({
    queryKey: ["ai-provider-models", providerId],
    queryFn: ({ signal }) => api<ConnectionResult>(`${path}/providers/${providerId}/models`, { signal }),
    enabled: !!providerId,
    staleTime: 60_000,
    retry: false,
  });
  const availableModels = modelCatalog.data?.models ?? [];
  const activeSession = sessions.find((session) => session.key === activeSessionKey) ?? sessions[0];
  const transcript = activeSession?.transcript ?? [];
  const matchedUsers = activeSession?.users ?? null;
  const supportsTools = availableModels.find((model) => model.id === modelName)?.capabilityCodes?.includes("TOOL_CALLING")
    ?? (selected?.modelName === modelName && selected?.capabilityCodes.includes("TOOL_CALLING"));

  useEffect(() => () => { activeChatRequest.current?.controller.abort(); }, []);

  useEffect(() => {
    if (!modelCatalog.data || !modelName || availableModels.some((model) => model.id === modelName)) return;
    setModelName(availableModels[0]?.id);
  }, [availableModels, modelCatalog.data, modelName]);

  const updateSession = (sessionKey: string, update: (session: ChatSession) => ChatSession) => {
    setSessions((current) => current.map((session) => session.key === sessionKey ? update(session) : session));
  };

  const selectProvider = (id: string) => {
    if (!chatProviders.some((provider) => provider.id === id)) return;
    setProviderId(id);
    setModelName(chatProviders.find((provider) => provider.id === id)?.modelName);
  };

  const newConversation = () => {
    if (busy || proposal) return;
    const key = `chat-${Date.now()}`;
    setSessions((current) => [{ key, label: "", transcript: [], users: null }, ...current]);
    setActiveSessionKey(key);
    setDraft("");
    setSessionsOpen(false);
  };

  const submit = async (text: string, history = transcript) => {
    const content = text.trim();
    if (!content || !providerId || !modelName || busy || activeChatRequest.current || proposal || !activeSession) return;
    const sessionKey = activeSession.key;
    const next = [...history, { key: Date.now(), role: "user" as const, content }];
    const request = {
      sessionKey,
      content,
      previousTranscript: transcript,
      previousLabel: activeSession.label,
      previousUsers: activeSession.users,
      controller: new AbortController(),
    };
    activeChatRequest.current = request;
    updateSession(sessionKey, (session) => ({
      ...session,
      label: session.label || (content.length > 28 ? `${content.slice(0, 28)}…` : content),
      transcript: next,
      users: null,
    }));
    setDraft("");
    setBusy(true);
    try {
      const messages: ChatMessage[] = next.slice(-20).map(({ role, content: messageContent }) => ({ role: role === "user" ? "user" : "assistant", content: messageContent }));
      const result = await mutate<ChatResponse>(`${path}/chat`, "POST", { providerId, modelName, messages }, request.controller.signal, 65_000);
      if (activeChatRequest.current !== request || request.controller.signal.aborted) return;
      updateSession(sessionKey, (session) => ({
        ...session,
        transcript: [...session.transcript, { key: Date.now() + 1, role: "assistant", content: result.message, steps: result.steps, proposalId: result.proposal?.id }],
        users: result.users,
      }));
      setProposal(result.proposal);
    } catch (error) {
      if (!request.controller.signal.aborted) {
        updateSession(sessionKey, (session) => ({
          ...session,
          transcript: [...session.transcript, {
            key: Date.now() + 1,
            role: "assistant",
            failed: true,
            content: error instanceof Error && error.message === "aiProviderCredentialRequired" ? t("aiProviderCredentialRequired") : t("aiProviderUnavailable"),
          }],
          users: null,
        }));
      }
    } finally {
      if (activeChatRequest.current === request) {
        activeChatRequest.current = null;
        setBusy(false);
      }
    }
  };

  const cancelChat = () => {
    const request = activeChatRequest.current;
    if (!request) return;
    activeChatRequest.current = null;
    request.controller.abort();
    updateSession(request.sessionKey, (session) => ({
      ...session,
      label: request.previousLabel,
      transcript: request.previousTranscript,
      users: request.previousUsers,
    }));
    setDraft(request.content);
    setBusy(false);
  };

  const updateConfirmation = (sessionKey: string, id: string, status: ExecutionStep["status"]) => {
    updateSession(sessionKey, (session) => ({ ...session, transcript: session.transcript.map((entry) => entry.proposalId === id
      ? { ...entry, steps: entry.steps?.map((step) => step.code === "ACTION_CONFIRMATION" ? { ...step, status } : step) }
      : entry) }));
  };
  const confirm = async () => {
    if (!proposal || !activeSession || confirming.current) return;
    if (proposal.kind === "CREATE_USER" && password.length === 0) { message.error(t("aiCreatePassword")); return; }
    confirming.current = true;
    setConfirmBusy(true);
    const sessionKey = activeSession.key;
    const id = proposal.id;
    try {
      await mutate(`${path}/actions/${proposal.id}/confirm`, "POST", proposal.kind === "CREATE_USER" ? { password } : {});
      message.success(t("aiActionCompleted"));
      updateConfirmation(sessionKey, id, "success");
      updateSession(sessionKey, (session) => ({ ...session, transcript: [...session.transcript, { key: Date.now(), role: "assistant", content: t("aiActionCompleted") }] }));
      setProposal(null); setPassword("");
      await Promise.all([client.invalidateQueries({ queryKey: ["users"] }), client.invalidateQueries({ queryKey: ["online-users"] })]);
    } catch { updateConfirmation(sessionKey, id, "error"); message.error(t("aiActionExpired")); setProposal(null); setPassword(""); }
    finally { confirming.current = false; setConfirmBusy(false); }
  };
  const cancelProposal = async () => {
    if (confirming.current || !proposal || !activeSession) return;
    confirming.current = true;
    setConfirmBusy(true);
    try {
      await mutate(`${path}/actions/${proposal.id}`, "DELETE");
      updateConfirmation(activeSession.key, proposal.id, "abort");
      setProposal(null); setPassword("");
    } catch { message.error(t("unavailable")); }
    finally { confirming.current = false; setConfirmBusy(false); }
  };
  const copyReply = async (content: string) => {
    try { await navigator.clipboard.writeText(content); message.success(t("aiReplyCopied")); }
    catch { message.error(t("unavailable")); }
  };
  const retryReply = (entry: TranscriptItem) => {
    const previous = transcript.at(-2);
    if (!entry.failed || transcript.at(-1)?.key !== entry.key || previous?.role !== "user") return;
    void submit(previous.content, transcript.slice(0, -2));
  };
  const stepTitle = (step: ExecutionStep) => {
    switch (step.code) {
      case "MODEL_RESPONSE": return t("aiStepModelResponse");
      case "USER_SEARCH": return t("aiStepUserSearch");
      case "ACTION_PROPOSAL": return t("aiStepProposal");
      case "TOOL_VALIDATION": return t("aiStepValidationFailed");
      case "ACTION_CONFIRMATION": return step.status === "pending" ? t("aiStepWaiting")
        : step.status === "success" ? t("aiActionCompleted") : step.status === "abort" ? t("aiStepCancelled") : t("aiActionExpired");
    }
  };
  const proposalName = proposal?.kind === "CREATE_USER" ? t("createUser") : proposal?.kind === "UPDATE_USER" ? t("edit") : t("deleteRecord");
  const promptItems = [
    { key: "search", icon: <SearchOutlined />, label: t("aiPromptSearchTitle"), description: t("aiPromptSearchDescription"), disabled: !supportsTools || !canReadUsers },
    { key: "change", icon: <EditOutlined />, label: t("aiPromptChangeTitle"), description: t("aiPromptChangeDescription"), disabled: !supportsTools || !canWriteUsers },
    { key: "capabilities", icon: <BulbOutlined />, label: t("aiPromptCapabilitiesTitle"), description: t("aiPromptCapabilitiesDescription"), disabled: !selected },
  ];

  return <div className="w-full ai-console-page">
    {!canReadUsers && <Typography.Text type="secondary" className="ai-console-permission">{t("forbidden")}</Typography.Text>}

    <div className="ai-chat-workspace">
      <aside className="ai-chat-sidebar" aria-label={t("aiSessions")} data-sessions-open={sessionsOpen}>
        <div className="ai-chat-sidebar-heading">
          <Typography.Text strong>{t("aiSessions")}</Typography.Text>
          <Button
            className="ai-chat-sessions-toggle"
            type="text"
            size="small"
            aria-expanded={sessionsOpen}
            aria-controls="ai-chat-sessions"
            onClick={() => setSessionsOpen((open) => !open)}
          >
            {t("aiSessions")}
          </Button>
        </div>
        <Conversations
          id="ai-chat-sessions"
          className="ai-chat-conversations"
          items={sessions.map((session) => ({
            key: session.key,
            label: session.label || t(session.key === "current" ? "aiCurrentConversation" : "aiUntitledConversation"),
            disabled: busy || !!proposal,
          }))}
          activeKey={activeSessionKey}
          onActiveChange={(key) => {
            if (busy || proposal) return;
            setActiveSessionKey(key);
            setDraft("");
            setSessionsOpen(false);
          }}
          creation={{ label: t("aiNewConversation"), icon: <PlusOutlined />, onClick: newConversation, disabled: busy || !!proposal }}
        />
        <Typography.Text className="ai-chat-session-note" type="secondary">{t("aiSessionLocalNote")}</Typography.Text>
      </aside>

      <section className="ai-chat-panel" aria-label={t("aiChatStartTitle")}>
        <header className="ai-chat-header">
          <div className="ai-chat-heading">
            <Typography.Text className="ai-chat-session-title" strong ellipsis>{activeSession?.label || t(activeSession?.key === "current" ? "aiCurrentConversation" : "aiUntitledConversation")}</Typography.Text>
            <Typography.Text className="ai-chat-model-caption" type="secondary">
              {selected && modelName ? `${selected.name} · ${modelName}` : t("aiChooseModelHint")}
            </Typography.Text>
          </div>
          <div className="ai-chat-model-controls">
            <label className="ai-chat-model-control">
              <Typography.Text type="secondary">{t("aiSelectProvider")}</Typography.Text>
              <Select
                aria-label={t("aiSelectProvider")}
                value={providerId}
                onChange={selectProvider}
                placeholder={t("aiSelectProvider")}
                disabled={providers.isPending || !!providers.error || enabledProviders.length === 0 || busy || !!proposal}
                options={enabledProviders.map((provider) => ({ value: provider.id, label: provider.name, disabled: provider.providerKind === "TYPESAFE_JEV" }))}
              />
            </label>
            <label className="ai-chat-model-control">
              <Typography.Text type="secondary">{t("aiSelectModel")}</Typography.Text>
              <Select
                aria-label={t("aiSelectModel")}
                value={modelName}
                onChange={setModelName}
                placeholder={t("aiSelectModel")}
                disabled={!selected || modelCatalog.isPending || !!modelCatalog.error || availableModels.length === 0 || busy || !!proposal}
                options={availableModels.map((model) => ({ value: model.id, label: model.name === model.id ? model.id : `${model.name} (${model.id})` }))}
                loading={!!providerId && modelCatalog.isPending}
              />
            </label>
            {canManageModels && <Button className="ai-chat-model-link" icon={<SettingOutlined />} href="/dashboard/ai-models" aria-label={t("aiModels")} title={t("aiModels")} />}
          </div>
        </header>

        <div className="ai-chat-divider" />

        <div className="ai-chat-log" aria-live="polite" role="log">
          {providers.isPending ? <div className="ai-chat-centered"><Spin /></div>
            : providers.error ? <div className="ai-chat-centered"><Typography.Text type="danger">{t("unavailable")}</Typography.Text></div>
            : transcript.length === 0 ? <div className="ai-chat-empty">
              <Welcome
                className="ai-chat-welcome"
                variant="borderless"
                icon={<CommentOutlined />}
                title={t("aiChatStartTitle")}
                description={<Space orientation="vertical" size={4}>
                  <Typography.Text type="secondary">{t("aiChatWelcome")}</Typography.Text>
                  {chatProviders.length === 0 && <Typography.Text type="secondary">{t("aiChatNoModel")}</Typography.Text>}
                </Space>}
              />
              <Prompts
                className="ai-chat-prompts"
                title={t("aiPromptSuggestions")}
                items={promptItems}
                onItemClick={({ data }) => {
                  const drafts: Record<string, string> = {
                    search: t("aiPromptSearchDraft"),
                    change: t("aiPromptChangeDraft"),
                    capabilities: t("aiPromptCapabilitiesDraft"),
                  };
                  setDraft(drafts[data.key] ?? "");
                }}
              />
            </div>
            : <Bubble.List
              className="ai-chat-bubbles"
              items={transcript.map((entry) => ({
                key: entry.key, role: entry.role, content: entry.content,
                classNames: { content: "ai-chat-bubble-content" },
                contentRender: () => entry.role === "user" ? entry.content : <>
                  <XMarkdown className="ai-chat-markdown" content={entry.content} escapeRawHtml openLinksInNewTab
                    dompurifyConfig={{ FORBID_TAGS: ["img", "iframe", "form", "input", "button", "video", "audio", "style"], FORBID_ATTR: ["style"] }} />
                  {(entry.steps?.length ?? 0) > 1 && <ThoughtChain className="ai-chat-steps" items={entry.steps?.map((step, index) => ({
                    key: `${step.code}-${index}`, title: stepTitle(step), status: step.status === "pending" ? undefined : step.status,
                  }))} />}
                </>,
                footer: entry.role === "assistant" ? <Actions className="ai-chat-actions" items={[
                  { key: "copy", actionRender: () => <Button type="text" size="small" icon={<CopyOutlined />} onClick={() => void copyReply(entry.content)}>{t("aiCopyReply")}</Button> },
                  ...(entry.failed && transcript.at(-1)?.key === entry.key ? [{ key: "retry", actionRender: () => <Button type="text" size="small" icon={<ReloadOutlined />} disabled={busy || !!proposal} onClick={() => retryReply(entry)}>{t("retry")}</Button> }] : []),
                ]} /> : undefined,
              }))}
              autoScroll
              role={{ user: { placement: "end" }, assistant: { placement: "start" } }}
            />}
          {busy && <div className="ai-chat-loading"><Bubble loading content="" /></div>}
        </div>

        <footer className="ai-chat-composer">
          {modelCatalog.error && <Space className="ai-chat-model-error" size={8}>
            <Typography.Text type="danger">{t("aiProviderUnavailable")}</Typography.Text>
            <Button type="link" size="small" onClick={() => void modelCatalog.refetch()} disabled={modelCatalog.isFetching}>{t("retry")}</Button>
          </Space>}
          <Sender
            className="ai-chat-sender"
            value={draft}
            onChange={setDraft}
            onSubmit={(text) => void submit(text)}
            onCancel={cancelChat}
            loading={busy}
            disabled={!selected || !modelName || !canReadUsers || providers.isPending || !!providers.error || modelCatalog.isPending || !!modelCatalog.error || !!proposal}
            placeholder={t("aiChatPlaceholder")}
          />
          <div className="ai-chat-composer-caption">
            <Typography.Text type="secondary">
              {supportsTools
                ? (canWriteUsers ? t("aiActionManualConfirm") : t("aiCapabilityUnavailable"))
                : t("aiChatComposerHint")}
            </Typography.Text>
          </div>
        </footer>
      </section>
    </div>

    {matchedUsers && <div className="ai-chat-result"><Typography.Title level={5}>{t("userList")}</Typography.Title><Table rowKey="id" size="small" pagination={{ defaultPageSize: DEFAULT_TABLE_PAGE_SIZE, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS] }} dataSource={matchedUsers} scroll={{ x: 520 }} columns={[
      { title: t("username"), dataIndex: "username" }, { title: t("name"), dataIndex: "displayName" },
      { title: t("status"), render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
      { title: t("version"), dataIndex: "version" },
    ]} /></div>}

    {proposal && <Alert className="ai-chat-result" type="warning" showIcon title={t("aiProposal")} description={<Space orientation="vertical" className="w-full">
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: "action", label: t("actions"), children: proposalName },
        ...(proposal.username ? [{ key: "username", label: t("username"), children: proposal.username }] : []),
        ...(proposal.displayName ? [{ key: "name", label: t("name"), children: proposal.displayName }] : []),
        ...(proposal.enabled === null ? [] : [{ key: "status", label: t("status"), children: <Tag color={proposal.enabled ? "success" : "default"}>{t(proposal.enabled ? "enabled" : "disabled")}</Tag> }]),
      ]} />
      {proposal.kind === "CREATE_USER" && <div><Typography.Text>{t("aiCreatePassword")}</Typography.Text><Input.Password value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="new-password" className="mt-2" /><Typography.Paragraph type="secondary" className="mt-2">{t("aiCreatePasswordHint")}</Typography.Paragraph></div>}
      <Typography.Text type="secondary">{t("aiActionManualConfirm")}</Typography.Text>
      <Space><Button type="primary" loading={confirmBusy} onClick={() => void confirm()}>{t("aiProposalConfirm")}</Button><Button disabled={confirmBusy} onClick={() => void cancelProposal()}>{t("aiProposalCancel")}</Button></Space>
    </Space>} />}
  </div>;
}
