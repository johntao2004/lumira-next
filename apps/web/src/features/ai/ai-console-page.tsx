"use client";
import { messageKeyFor } from "@/platform/api/errors";

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Actions, Bubble, Conversations, Prompts, Think, ThoughtChain, type PromptsProps } from "@ant-design/x";
import { BulbOutlined, CopyOutlined, DeleteOutlined, DownOutlined, EditOutlined, EllipsisOutlined, HistoryOutlined, InboxOutlined, PlusOutlined, ReloadOutlined, RollbackOutlined, SearchOutlined, ShareAltOutlined } from "@ant-design/icons";
import { Alert, App, Avatar, Button, Descriptions, Input, Modal, Popover, Space, Spin, Table, Tag, Tooltip, Typography, type MenuProps } from "antd";
import { api, isRolePreviewReadOnly, mutate } from "@/platform/api/client";
import { usePreferences } from "@/platform/providers";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZE_OPTIONS } from "@/platform/api/pagination";
import { AccountAvatar } from "@/platform/components/account-avatar";
import type { CurrentUser } from "@/platform/auth/api-types";
import { createChatHistory, createChatSession, formatChatTranscript, loadChatHistory, saveChatHistory, type ChatSession, type TranscriptItem } from "./chat-history";
import type { ActionProposal, ChatAttachment, ChatMessage, ChatResponse, ConnectionResult, ExecutionStep, Provider } from "./types";
import { AiChatComposer, type AiChatComposerRef } from "./ai-chat-composer";
import { AiChatMarkdown } from "./ai-chat-markdown";
import { FileDownloadAction } from "@/features/files/file-download-action";

type AiConsoleProps = { canReadUsers: boolean; canCreateUsers: boolean; canUpdateUsers: boolean; canDeleteUsers: boolean };
type ActiveChatRequest = {
  sessionKey: string;
  content: string;
  attachments: ChatAttachment[];
  previousTranscript: TranscriptItem[];
  previousLabel: string;
  previousUsers: ChatSession["users"];
  controller: AbortController;
  signal: AbortSignal;
};
const path = "/platform/ai";
type ConsoleProvider = Pick<Provider, "id" | "name" | "providerKind" | "modelName" | "capabilityCodes" | "enabled">;

export function AiConsolePage(props: AiConsoleProps) {
  const { t } = usePreferences();
  const account = useQuery({ queryKey: ["session"], queryFn: ({ signal }) => api<CurrentUser>("/auth/me", { signal }) });
  if (!account.data) return account.error ? <Alert type="error" title={t("unavailable")} /> : <Spin />;
  return <AiChatWorkspace key={account.data.userId} owner={account.data.userId} {...props} />;
}

function AiChatWorkspace({ owner, canReadUsers, canCreateUsers, canUpdateUsers, canDeleteUsers }: AiConsoleProps & { owner: string }) {
  const { t, dark } = usePreferences();
  const sessionScope = useSessionScope();
  const { message, modal } = App.useApp();
  const client = useQueryClient();
  const [initialHistory] = useState(() => {
    try { return { history: loadChatHistory(owner, localStorage) ?? createChatHistory(), error: false }; }
    catch { return { history: createChatHistory(), error: true }; }
  });
  const [history, setHistory] = useState(initialHistory.history);
  const [historySaveFailed, setHistorySaveFailed] = useState(initialHistory.error);
  const savedHistory = useRef(history);
  const consolePage = useRef<HTMLDivElement>(null);
  const sessionsTrigger = useRef<HTMLButtonElement>(null);
  const [consoleWidth, setConsoleWidth] = useState<number | null>(null);
  const compactSessions = consoleWidth !== null && consoleWidth <= 960;
  const narrowSessions = consoleWidth !== null && consoleWidth <= 600;
  const { sessions, activeSessionKey } = history;
  const activeSession = sessions.find((session) => session.key === activeSessionKey) ?? sessions[0];
  const { providerId, modelName, draft } = activeSession;
  const [busy, setBusy] = useState(false);
  const [attachmentBusy, setAttachmentBusy] = useState(false);
  const [sessionsOpen, setSessionsOpen] = useState(false);
  const [showArchived, setShowArchived] = useState(false);
  const [renameSessionKey, setRenameSessionKey] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [proposal, setProposal] = useState<ActionProposal | null>(null);
  const [password, setPassword] = useState("");
  const [confirmBusy, setConfirmBusy] = useState(false);
  const composer = useRef<AiChatComposerRef>(null);
  const confirming = useRef(false);
  const activeChatRequest = useRef<ActiveChatRequest | null>(null);
  const conversationActionsDisabled = busy || attachmentBusy || !!proposal;

  useLayoutEffect(() => {
    const element = consolePage.current;
    if (!element) return;
    const updateConsoleWidth = (width: number) => setConsoleWidth(width);
    updateConsoleWidth(element.getBoundingClientRect().width);
    const observer = new ResizeObserver(([entry]) => {
      if (entry) updateConsoleWidth(entry.contentRect.width);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!compactSessions) {
      setSessionsOpen(false);
      return;
    }
    if (!sessionsOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setSessionsOpen(false);
      requestAnimationFrame(() => sessionsTrigger.current?.focus());
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [compactSessions, sessionsOpen]);
  const providers = useQuery({ queryKey: ["ai-providers", "console"], queryFn: ({ signal }) => api<ConsoleProvider[]>(`${path}/console/providers`, { signal }) });
  const enabledProviders = (providers.data ?? []).filter((provider) => provider.enabled && (
    provider.providerKind === "TYPESAFE_JEV" || provider.capabilityCodes.includes("CHAT")
  ));
  // AiChatComposer reads only these public fields; never pass the admin ProviderView DTO.
  const composerProviders = enabledProviders as Provider[];
  const chatProviders = useMemo(() => (providers.data ?? []).filter((provider) => provider.enabled && provider.providerKind !== "TYPESAFE_JEV" && provider.capabilityCodes.includes("CHAT")), [providers.data]);
  const selected = chatProviders.find((provider) => provider.id === providerId);
  const modelCatalog = useQuery({
    queryKey: ["ai-provider-models", providerId],
    queryFn: ({ signal }) => api<ConnectionResult>(`${path}/console/providers/${providerId}/models`, { signal }),
    enabled: !!providerId,
    staleTime: 60_000,
    retry: false,
  });
  const availableModels = modelCatalog.data?.models ?? [];
  const transcript = activeSession?.transcript ?? [];
  const matchedUsers = activeSession?.users ?? null;
  const supportsTools = availableModels.find((model) => model.id === modelName)?.capabilityCodes?.includes("TOOL_CALLING")
    ?? (selected?.modelName === modelName && selected?.capabilityCodes.includes("TOOL_CALLING"));
  const supportsVision = availableModels.find(model => model.id === modelName)?.inputModalities.includes("image") ?? false;
  const canChangeUsers = canCreateUsers || canUpdateUsers || canDeleteUsers;
  const canUseChat = canReadUsers || canChangeUsers;
  const canConfirmProposal = proposal?.kind === "CREATE_USER" ? canCreateUsers
    : proposal?.kind === "UPDATE_USER" ? canUpdateUsers
      : proposal?.kind === "DELETE_USER" ? canDeleteUsers : false;

  useEffect(() => () => { activeChatRequest.current?.controller.abort(); }, []);

  useEffect(() => {
    if (savedHistory.current === history) return;
    try {
      saveChatHistory(owner, history, localStorage);
      savedHistory.current = history;
      setHistorySaveFailed(false);
    } catch { setHistorySaveFailed(true); }
  }, [history, owner]);

  useEffect(() => {
    if (!modelCatalog.data || !modelName || availableModels.some((model) => model.id === modelName)) return;
    setModelName(availableModels[0]?.id);
  }, [availableModels, modelCatalog.data, modelName]);

  const updateSession = (sessionKey: string, update: (session: ChatSession) => ChatSession) => {
    setHistory((current) => ({ ...current, sessions: current.sessions.map((session) => session.key === sessionKey ? update(session) : session) }));
  };
  const setDraft = (draft: string) => updateSession(activeSessionKey, (session) => ({ ...session, draft }));
  const setModelName = (modelName: string | undefined) => updateSession(activeSessionKey, (session) => ({ ...session, modelName }));

  const selectProvider = (id: string) => {
    const provider = chatProviders.find((provider) => provider.id === id);
    if (!provider) return;
    updateSession(activeSessionKey, (session) => ({ ...session, providerId: id, modelName: provider.modelName }));
  };

  const newConversation = () => {
    if (busy || attachmentBusy || proposal) return;
    const session = { ...createChatSession(), providerId, modelName };
    setHistory((current) => ({ sessions: [session, ...current.sessions], activeSessionKey: session.key }));
    setShowArchived(false);
    setSessionsOpen(false);
  };

  const switchConversation = (key: string) => {
    if (busy || attachmentBusy || proposal || !sessions.some((session) => session.key === key)) return;
    setHistory((current) => ({ ...current, activeSessionKey: key }));
    setSessionsOpen(false);
  };

  const toggleHistoryView = () => {
    if (conversationActionsDisabled) return;
    const nextArchived = !showArchived;
    const nextSession = sessions.find((session) => session.archived === nextArchived);
    if (nextSession) setHistory((current) => ({ ...current, activeSessionKey: nextSession.key }));
    setShowArchived(nextArchived);
  };

  const startRenameConversation = (session: ChatSession) => {
    if (conversationActionsDisabled) return;
    setRenameValue(session.label || "");
    setRenameSessionKey(session.key);
  };

  const saveConversationName = () => {
    if (conversationActionsDisabled || !renameSessionKey) return;
    const label = renameValue.trim();
    if (!label) return;
    updateSession(renameSessionKey, (session) => ({ ...session, label }));
    setRenameSessionKey(null);
    setRenameValue("");
    void message.success(t("aiConversationRenamed"));
  };

  const shareConversation = async (session: ChatSession) => {
    if (conversationActionsDisabled) return;
    const title = session.label.trim() || t("aiUntitledConversation");
    const text = formatChatTranscript(session, {
      untitledConversation: t("aiUntitledConversation"),
      user: t("aiTranscriptUser"),
      assistant: t("aiTranscriptAssistant"),
      attachments: t("aiTranscriptAttachments"),
    });
    if (typeof navigator.share === "function") {
      try {
        await navigator.share({ title, text });
        void message.success(t("aiConversationShared"));
        return;
      } catch (error) {
        if (error instanceof Error && error.name === "AbortError") return;
      }
    }
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
      await navigator.clipboard.writeText(text);
      void message.success(t("aiTranscriptCopied"));
    } catch {
      void message.error(t("aiTranscriptCopyFailed"));
    }
  };

  const archiveConversation = (sessionKey: string) => {
    if (conversationActionsDisabled || !sessions.some((session) => session.key === sessionKey)) return;
    updateSession(sessionKey, (session) => ({ ...session, archived: true }));
    if (sessionKey === activeSessionKey) setShowArchived(true);
    void message.success(t("aiConversationArchived"));
  };

  const restoreConversation = (sessionKey: string) => {
    if (conversationActionsDisabled || !sessions.some((session) => session.key === sessionKey)) return;
    updateSession(sessionKey, (session) => ({ ...session, archived: false }));
    if (sessionKey === activeSessionKey) setShowArchived(false);
    void message.success(t("aiConversationRestored"));
  };

  const deleteConversation = (sessionKey: string) => {
    if (conversationActionsDisabled) return;
    const remaining = sessions.filter((session) => session.key !== sessionKey);
    let nextActiveSessionKey = activeSessionKey;
    if (nextActiveSessionKey === sessionKey || !remaining.some((session) => session.key === nextActiveSessionKey)) {
      const nextSession = remaining.find((session) => session.archived === showArchived)
        ?? remaining.find((session) => !session.archived)
        ?? remaining[0];
      if (nextSession) {
        nextActiveSessionKey = nextSession.key;
        setShowArchived(nextSession.archived);
      } else {
        const replacement = { ...createChatSession(), providerId, modelName };
        remaining.push(replacement);
        nextActiveSessionKey = replacement.key;
        setShowArchived(false);
      }
    }
    setHistory({ sessions: remaining, activeSessionKey: nextActiveSessionKey });
    setSessionsOpen(false);
    void message.success(t("aiConversationDeleted"));
  };

  const confirmDeleteConversation = (session: ChatSession) => {
    if (conversationActionsDisabled) return;
    const name = session.label || t("aiUntitledConversation");
    modal.confirm({
      title: t("aiDeleteConversation"),
      content: t("aiDeleteConversationConfirm", { name }),
      okText: t("aiDeleteConversation"),
      cancelText: t("cancel"),
      okButtonProps: { danger: true },
      onOk: () => deleteConversation(session.key),
    });
  };

  const submit = async (text: string, history = transcript, attachments = activeSession.draftAttachments ?? []) => {
    const content = text.trim() || (attachments.length ? t("aiAttachmentPrompt") : "");
    if (!content || !providerId || !modelName || busy || attachmentBusy || activeChatRequest.current || proposal || !activeSession) return;
    if (attachments.some(file => file.image) && !supportsVision) { void message.error(t("aiAttachmentVisionRequired")); return; }
    const sessionKey = activeSession.key;
    const next = [...history, { key: Date.now(), role: "user" as const, content, attachments }];
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, sessionScope()]);
    const request = {
      sessionKey,
      content,
      attachments,
      previousTranscript: transcript,
      previousLabel: activeSession.label,
      previousUsers: activeSession.users,
      controller,
      signal,
    };
    activeChatRequest.current = request;
    updateSession(sessionKey, (session) => ({
      ...session,
      label: session.label || (content.length > 28 ? `${content.slice(0, 28)}…` : content),
      transcript: next,
      users: null,
      draft: "",
      draftAttachments: [],
    }));
    setBusy(true);
    try {
      const messages: ChatMessage[] = next.slice(-20).map(({ role, content: messageContent, attachments }) => ({ role: role === "user" ? "user" : "assistant", content: messageContent, attachmentIds: attachments?.map(file => file.id) }));
      const result = await mutate<ChatResponse>(`${path}/chat`, "POST", { providerId, modelName, messages }, request.signal, 65_000);
      if (activeChatRequest.current !== request || request.signal.aborted) return;
      updateSession(sessionKey, (session) => ({
        ...session,
        transcript: [...session.transcript, { key: Date.now() + 1, role: "assistant", content: result.message, steps: result.steps, proposalId: result.proposal?.id }],
        users: result.users,
      }));
      setProposal(result.proposal);
    } catch (error) {
      if (!request.signal.aborted) {
        updateSession(sessionKey, (session) => ({
          ...session,
          transcript: [...session.transcript, {
            key: Date.now() + 1,
            role: "assistant",
            failed: true,
            content: t(messageKeyFor(error,["aiProviderCredentialRequired", "aiAttachmentVisionRequired", "aiAttachmentContextTooLarge", "aiAttachmentInvalid", "aiAttachmentEmpty", "aiAttachmentTooLarge"],"aiProviderUnavailable")),
          }],
          users: null,
        }));
      }
    } finally {
      if (activeChatRequest.current === request) {
        activeChatRequest.current = null;
        if (!request.signal.aborted) setBusy(false);
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
      draftAttachments: request.attachments,
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
    if (!proposal || !canConfirmProposal || !activeSession || confirming.current || (!proposal.target && proposal.kind !== "CREATE_USER")) return;
    if (proposal.kind === "CREATE_USER" && password.length === 0) { message.error(t("aiCreatePassword")); return; }
    const signal = sessionScope();
    confirming.current = true;
    setConfirmBusy(true);
    const sessionKey = activeSession.key;
    const id = proposal.id;
    try {
      await mutate(`${path}/actions/${proposal.id}/confirm`, "POST", proposal.kind === "CREATE_USER" ? { password } : {}, signal);
      signal.throwIfAborted();
      message.success(t("aiActionCompleted"));
      updateConfirmation(sessionKey, id, "success");
      updateSession(sessionKey, (session) => ({ ...session, transcript: [...session.transcript, { key: Date.now(), role: "assistant", content: t("aiActionCompleted") }] }));
      setProposal(null); setPassword("");
      await Promise.all([client.invalidateQueries({ queryKey: ["users"] }), client.invalidateQueries({ queryKey: ["online-users"] })]);
    } catch { if (!signal.aborted) { updateConfirmation(sessionKey, id, "error"); message.error(t("aiActionExpired")); setProposal(null); setPassword(""); } }
    finally { confirming.current = false; if (!signal.aborted) setConfirmBusy(false); }
  };
  const cancelProposal = async () => {
    if (confirming.current || !proposal || !activeSession) return;
    const signal = sessionScope();
    confirming.current = true;
    setConfirmBusy(true);
    try {
      await mutate(`${path}/actions/${proposal.id}`, "DELETE", undefined, signal);
      signal.throwIfAborted();
      updateConfirmation(activeSession.key, proposal.id, "abort");
      setProposal(null); setPassword("");
    } catch { if (!signal.aborted) message.error(t("unavailable")); }
    finally { confirming.current = false; if (!signal.aborted) setConfirmBusy(false); }
  };
  const copyReply = async (content: string) => {
    try { await navigator.clipboard.writeText(content); message.success(t("aiReplyCopied")); }
    catch { message.error(t("unavailable")); }
  };
  const retryReply = (entry: TranscriptItem) => {
    const previous = transcript.at(-2);
    if (transcript.at(-1)?.key !== entry.key || previous?.role !== "user" || entry.proposalId) return;
    void submit(previous.content, transcript.slice(0, -2), previous.attachments ?? []);
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
  const stepModuleOperation = (step: ExecutionStep) => {
    if (!step.moduleCode || !step.operationCode) return undefined;
    const moduleName = step.moduleCode === "user-management" ? t("aiModuleUserManagement") : step.moduleCode.replaceAll("-", " ");
    const operationName = step.operationCode === "query" ? t("aiOperationQuery")
      : step.operationCode === "create" ? t("aiOperationCreate")
      : step.operationCode === "update" ? t("aiOperationUpdate")
      : step.operationCode === "delete" ? t("aiOperationDelete")
      : step.operationCode.replaceAll("-", " ");
    return `${moduleName} · ${operationName}`;
  };
  const proposalName = proposal?.kind === "CREATE_USER" ? t("createUser") : proposal?.kind === "UPDATE_USER" ? t("edit") : t("deleteRecord");
  const promptDrafts: Record<string, string> = {
    search: t("aiPromptSearchDraft"),
    change: t("aiPromptChangeDraft"),
    capabilities: t("aiPromptCapabilitiesDraft"),
  };
  const promptItems: PromptsProps["items"] = [
    {
      key: "search",
      icon: <SearchOutlined className="ai-chat-prompt-search-icon" />,
      label: t("aiPromptSearchTitle"),
      description: t("aiPromptSearchDescription"),
      disabled: !supportsTools || !canReadUsers,
    },
    {
      key: "change",
      icon: <EditOutlined className="ai-chat-prompt-change-icon" />,
      label: t("aiPromptChangeTitle"),
      description: t("aiPromptChangeDescription"),
      disabled: !supportsTools || !canChangeUsers,
    },
    {
      key: "capabilities",
      icon: <BulbOutlined className="ai-chat-prompt-capabilities-icon" />,
      label: t("aiPromptCapabilitiesTitle"),
      description: t("aiPromptCapabilitiesDescription"),
      disabled: !selected,
    },
  ];

  const visibleSessions = sessions.filter((session) => session.archived === showArchived);
  const activeSessionCount = sessions.length - sessions.filter((session) => session.archived).length;
  const archivedSessionCount = sessions.length - activeSessionCount;
  const historyViewButton = <Button
    className={`ai-chat-history-filter${showArchived ? " ai-chat-history-filter-active" : ""}`}
    type="text"
    size="small"
    icon={showArchived ? <RollbackOutlined /> : <InboxOutlined />}
    aria-pressed={showArchived}
    aria-label={`${t(showArchived ? "aiActiveConversations" : "aiArchivedConversations")} (${showArchived ? activeSessionCount : archivedSessionCount})`}
    disabled={conversationActionsDisabled}
    onClick={toggleHistoryView}
  >
    {t(showArchived ? "aiActiveConversations" : "aiArchivedConversations")} ({showArchived ? activeSessionCount : archivedSessionCount})
  </Button>;
  const newConversationButton = <Button
    className="ai-chat-new-conversation-button"
    size="small"
    icon={<PlusOutlined />}
    aria-label={t("aiNewConversation")}
    onClick={newConversation}
    disabled={busy || attachmentBusy || !!proposal}
  >{t("aiNewConversation")}</Button>;
  const historyToolbar = <div className="ai-chat-history-toolbar">{historyViewButton}</div>;
  const desktopHistoryToolbar = <div className="ai-chat-history-toolbar">
    {newConversationButton}
    {historyViewButton}
  </div>;
  const sessionList = visibleSessions.length ? <Conversations
    id="ai-chat-sessions"
    className="ai-chat-conversations"
    styles={{
      root: { padding: "2px 8px", gap: 2 },
      item: { height: 32, minHeight: 32, paddingInline: 8 },
    }}
    items={visibleSessions.map((session) => ({
      key: session.key,
      label: <button
        type="button"
        className="ai-chat-history-item"
        aria-pressed={session.key === activeSessionKey}
        disabled={conversationActionsDisabled}
        onClick={(event) => { event.stopPropagation(); switchConversation(session.key); }}
      >{session.label || t("aiUntitledConversation")}</button>,
      title: session.label || t("aiUntitledConversation"),
      disabled: conversationActionsDisabled,
    }))}
    activeKey={activeSessionKey}
    onActiveChange={switchConversation}
    menu={(conversation) => {
      const session = visibleSessions.find((candidate) => candidate.key === conversation.key);
      if (!session) return {};
      const actionItems: NonNullable<MenuProps["items"]> = [
        { key: "rename", icon: <EditOutlined />, label: t("aiRenameConversation") },
        { key: "share", icon: <ShareAltOutlined />, label: t("aiShareConversation") },
        { type: "divider" },
        session.archived
          ? { key: "restore", icon: <RollbackOutlined />, label: t("aiUnarchiveConversation") }
          : { key: "archive", icon: <InboxOutlined />, label: t("aiArchiveConversation") },
        { type: "divider" },
        { key: "delete", icon: <DeleteOutlined />, label: t("aiDeleteConversation"), danger: true },
      ];
      return {
        trigger: () => <Button
          className="ai-chat-history-menu-trigger"
          type="text"
          size="small"
          icon={<EllipsisOutlined />}
          aria-haspopup="menu"
          aria-label={`${t("aiConversationActions")}: ${session.label || t("aiUntitledConversation")}`}
          title={t("aiConversationActions")}
          disabled={conversationActionsDisabled}
        />,
        items: actionItems,
        onClick: ({ key }) => {
          switch (key) {
            case "rename": startRenameConversation(session); break;
            case "share": void shareConversation(session); break;
            case "archive": archiveConversation(session.key); break;
            case "restore": restoreConversation(session.key); break;
            case "delete": confirmDeleteConversation(session); break;
          }
        },
      };
    }}
  /> : <Typography.Text id="ai-chat-sessions" className="ai-chat-empty-history" type="secondary">{t("aiNoConversations")}</Typography.Text>;

  const sessionsPicker = <Popover
    open={sessionsOpen}
    onOpenChange={setSessionsOpen}
    trigger="click"
    placement={narrowSessions ? "bottom" : "bottomLeft"}
    autoAdjustOverflow
    arrow={false}
    classNames={{ root: "ai-chat-history-popover" }}
    content={<div className="ai-chat-history-popover-content">{historyToolbar}{sessionList}</div>}
  >
    <Button
      ref={sessionsTrigger}
      className="ai-chat-sessions-toggle"
      type="text"
      size="small"
      icon={<HistoryOutlined />}
      aria-expanded={sessionsOpen}
      aria-controls="ai-chat-sessions"
      aria-haspopup="dialog"
    >
      {t("aiSessions")} <DownOutlined className="ai-chat-sessions-chevron" />
    </Button>
  </Popover>;

  return <div ref={consolePage} className="w-full ai-console-page">
    {!canReadUsers && <Typography.Text type="secondary" className="ai-console-permission">{t("forbidden")}</Typography.Text>}
    {historySaveFailed && <Alert type="warning" showIcon title={t("aiHistorySaveFailed")} />}

    <div className="ai-chat-workspace">
      <aside className="ai-chat-sidebar" aria-label={t("aiSessions")} data-sessions-open={sessionsOpen}>
        <div className="ai-chat-sidebar-heading">
          <Typography.Text strong role="heading" aria-level={2}>{t("aiSessions")}</Typography.Text>
          {compactSessions && sessionsPicker}
          {compactSessions && newConversationButton}
        </div>
        {compactSessions ? null : desktopHistoryToolbar}
        {compactSessions ? null : sessionList}
      </aside>

      <section className="ai-chat-panel" aria-label={t("aiChatStartTitle")}>
        <header className="ai-chat-header">
          <div className="ai-chat-heading">
            <Typography.Text className="ai-chat-session-title" strong ellipsis>{activeSession.label || t("aiUntitledConversation")}</Typography.Text>
            <Typography.Text className="ai-chat-model-caption" type="secondary">
              {selected && modelName ? `${selected.name} · ${modelName}` : t("aiChooseModelHint")}
            </Typography.Text>
          </div>
        </header>

        <div className="ai-chat-divider" />

        <div className="ai-chat-log" aria-live="polite" aria-busy={busy} role="log">
          {transcript.length === 0 && providers.isPending ? <div className="ai-chat-centered"><Spin /></div>
            : transcript.length === 0 && providers.error ? <div className="ai-chat-centered"><Typography.Text type="danger">{t("unavailable")}</Typography.Text></div>
            : transcript.length === 0 && chatProviders.length === 0 ? <div className="ai-chat-centered">
              <Typography.Text type="secondary">{t("aiChatNoModel")}</Typography.Text>
            </div>
            : transcript.length === 0 ? null
            : <Bubble.List
              key={activeSessionKey}
              className="ai-chat-bubbles"
              items={transcript.map((entry) => ({
                key: entry.key, role: entry.role, content: entry.content,
                classNames: { content: "ai-chat-bubble-content" },
                contentRender: () => entry.role === "user" ? <>
                  {entry.content}
                  {!!entry.attachments?.length && <div className="ai-chat-message-attachments">{entry.attachments.map(file => <FileDownloadAction
                    key={file.id} fileName={file.name} href={`/api/account/files/${file.id}/content`} label={file.name}
                    confirmation={t("fileDownloadConfirm", { name: file.name })} confirmText={t("downloadFile")} cancelText={t("cancel")}
                    disabled={isRolePreviewReadOnly()} />)}</div>}
                </> : <>
                  <AiChatMarkdown content={entry.content} dark={dark} />
                  {!!entry.steps?.length && <ThoughtChain className="ai-chat-steps" items={entry.steps.map((step, index) => ({
                    key: `${step.code}-${index}`, title: stepModuleOperation(step) ?? stepTitle(step),
                    description: stepModuleOperation(step) ? stepTitle(step) : undefined,
                    status: step.status === "pending" ? "loading" : step.status,
                  }))} />}
                </>,
                footer: entry.role === "assistant" ? <Actions className="ai-chat-actions" items={[
                  ...(transcript.at(-1)?.key === entry.key && transcript.at(-2)?.role === "user" && !entry.proposalId ? [{ key: "retry", actionRender: () => <Tooltip title={t(entry.failed ? "retry" : "aiRegenerateReply")}><Button type="text" size="small" aria-label={t(entry.failed ? "retry" : "aiRegenerateReply")} icon={<ReloadOutlined />} disabled={busy || !!proposal} onClick={() => retryReply(entry)} /></Tooltip> }] : []),
                  { key: "copy", actionRender: () => <Tooltip title={t("aiCopyReply")}><Button type="text" size="small" aria-label={t("aiCopyReply")} icon={<CopyOutlined />} onClick={() => void copyReply(entry.content)} /></Tooltip> },
                ]} /> : undefined,
              }))}
              autoScroll
              role={{
                user: { placement: "end", avatar: <AccountAvatar /> },
                assistant: { placement: "start", avatar: <Avatar className="ai-chat-assistant-avatar" icon={<svg className="ai-chat-assistant-mark" viewBox="0 0 24 24" role="img" aria-label="AI" focusable="false"><path d="M12 2.5 14.55 9.45 21.5 12 14.55 14.55 12 21.5 9.45 14.55 2.5 12 9.45 9.45 12 2.5Z" fill="currentColor" /></svg>} />, footerPlacement: "outer-end" },
              }}
            />}
          {busy && <Think className="ai-chat-loading" title={t("aiThinking")} loading blink aria-live="polite" />}
        </div>

        <footer className="ai-chat-composer">
          {modelCatalog.error && <Space className="ai-chat-model-error" size={8}>
            <Typography.Text type="danger">{t("aiProviderUnavailable")}</Typography.Text>
            <Button type="link" size="small" onClick={() => void modelCatalog.refetch()} disabled={modelCatalog.isFetching}>{t("retry")}</Button>
          </Space>}
          {transcript.length === 0 && draft.length === 0 && !providers.isPending && !providers.error && <Prompts
            className="ai-chat-prompts"
            title={<><span aria-hidden="true">✨ </span>{t("aiPromptSuggestions")}</>}
            items={promptItems}
            styles={{ item: { background: "transparent" } }}
            onItemClick={({ data }) => {
              const nextDraft = promptDrafts[data.key];
              if (!nextDraft) return;
              setDraft(nextDraft);
              requestAnimationFrame(() => composer.current?.focus());
            }}
          />}
          <AiChatComposer
            ref={composer}
            key={activeSessionKey}
            draft={draft}
            onChange={setDraft}
            onSubmit={text => void submit(text)}
            onCancel={cancelChat}
            files={activeSession.draftAttachments ?? []}
            onAttach={file => updateSession(activeSessionKey, session => ({ ...session, draftAttachments: [...(session.draftAttachments ?? []), file] }))}
            onRemove={id => updateSession(activeSessionKey, session => ({ ...session, draftAttachments: session.draftAttachments?.filter(file => file.id !== id) }))}
            onUploadingChange={setAttachmentBusy}
            providers={composerProviders}
            models={availableModels}
            providerId={providerId}
            modelName={modelName}
            onProviderChange={selectProvider}
            onModelChange={setModelName}
            providerDisabled={providers.isPending || !!providers.error || enabledProviders.length === 0 || busy || !!proposal}
            modelDisabled={!selected || modelCatalog.isPending || !!modelCatalog.error || availableModels.length === 0 || busy || !!proposal}
            modelLoading={!!providerId && modelCatalog.isPending}
            busy={busy}
            disabled={!selected || !modelName || !canUseChat || providers.isPending || !!providers.error || modelCatalog.isPending || !!modelCatalog.error || !!proposal}
            uploadDisabled={busy || !!proposal || !canReadUsers}
            supportsVision={supportsVision}
          />
        </footer>
      </section>
    </div>

    {matchedUsers && <div className="ai-chat-result"><Typography.Title level={5}>{t("userList")}</Typography.Title><Table rowKey="id" size="medium" pagination={{ defaultPageSize: DEFAULT_TABLE_PAGE_SIZE, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS] }} dataSource={matchedUsers} scroll={{ x: 520 }} columns={[
      { title: t("username"), dataIndex: "username" }, { title: t("name"), dataIndex: "displayName" },
      { title: t("status"), render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
      { title: t("version"), dataIndex: "version" },
    ]} /></div>}

    {proposal && <Alert className="ai-chat-result" type="warning" showIcon title={t("aiProposal")} description={<Space orientation="vertical" className="w-full">
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: "action", label: t("actions"), children: proposalName },
      ]} />
      {proposal.target && <>
        <Typography.Text strong>{t("proposalCurrentValues")}</Typography.Text>
        <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
          { key: "target-id", label: t("targetId"), children: proposal.target.id },
          { key: "target-username", label: t("username"), children: proposal.target.username },
          { key: "target-name", label: t("name"), children: proposal.target.displayName },
          { key: "target-enabled", label: t("status"), children: <Tag color={proposal.target.enabled ? "success" : "default"}>{t(proposal.target.enabled ? "enabled" : "disabled")}</Tag> },
          { key: "target-version", label: t("version"), children: proposal.target.version },
        ]} />
      </>}
      {proposal.kind !== "DELETE_USER" && <>
        <Typography.Text strong>{t("proposalProposedValues")}</Typography.Text>
        <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
          ...(proposal.username !== null ? [{ key: "proposed-username", label: t("username"), children: proposal.username }] : []),
          ...(proposal.displayName !== null ? [{ key: "proposed-name", label: t("name"), children: proposal.displayName }] : []),
          ...(proposal.enabled === null ? [] : [{ key: "proposed-status", label: t("status"), children: <Tag color={proposal.enabled ? "success" : "default"}>{t(proposal.enabled ? "enabled" : "disabled")}</Tag> }]),
        ]} />
      </>}
      {!proposal.target && proposal.kind !== "CREATE_USER" && <Typography.Text type="danger">{t("staleRecord")}</Typography.Text>}
      {proposal.kind === "CREATE_USER" && <div><Typography.Text>{t("aiCreatePassword")}</Typography.Text><Input.Password value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="new-password" className="mt-2" /><Typography.Paragraph type="secondary" className="mt-2">{t("aiCreatePasswordHint")}</Typography.Paragraph></div>}
      <Typography.Text type="secondary">{t("aiActionManualConfirm")}</Typography.Text>
      <Space><Button type="primary" loading={confirmBusy} disabled={!canConfirmProposal || (!proposal.target && proposal.kind !== "CREATE_USER")} onClick={() => void confirm()}>{t("aiProposalConfirm")}</Button><Button disabled={confirmBusy} onClick={() => void cancelProposal()}>{t("aiProposalCancel")}</Button></Space>
    </Space>} />}

    <Modal
      open={renameSessionKey !== null}
      title={t("aiRenameConversation")}
      okText={t("save")}
      cancelText={t("cancel")}
      okButtonProps={{ disabled: !renameValue.trim() || conversationActionsDisabled }}
      onOk={saveConversationName}
      onCancel={() => { setRenameSessionKey(null); setRenameValue(""); }}
      destroyOnHidden
    >
      <Input
        autoFocus
        maxLength={120}
        aria-label={t("aiConversationTitle")}
        placeholder={t("aiConversationTitle")}
        value={renameValue}
        onChange={(event) => setRenameValue(event.target.value)}
        onPressEnter={saveConversationName}
      />
    </Modal>
  </div>;
}
