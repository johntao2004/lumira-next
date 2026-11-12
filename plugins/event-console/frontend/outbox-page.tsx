"use client";

import { useState } from "react";
import { App, Button, Input, Modal, Tag } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, mutate } from "@/platform/api/client";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { messageKeyFor } from "@/platform/api/errors";
import { listRequestParams } from "@/platform/api/pagination";
import { EventTraceDrawer } from "@/platform/events/event-trace-drawer";
import type { EventTraceTarget } from "@/platform/events/event-trace-drawer";

type Entry = {
  id: string; sourceType: string; eventType: string; eventKey: string; status: keyof typeof statuses;
  attempts: number; lastError: string | null; availableAt: string; deliveredAt: string | null;
  createdAt: string; updatedAt: string;
};
const statuses = {
  PENDING: "eventPending", PROCESSING: "eventProcessing", DELIVERED: "eventDelivered",
  FAILED: "eventFailed", DEAD_LETTER: "eventDeadLetter",
} as const satisfies Record<string, MessageKey>;
const eventErrorKeys = ["eventNotDeadLetter", "invalidEventReplay", "forbidden"] as const satisfies readonly MessageKey[];

export function OutboxPage({ canReplay = false }: { canReplay?: boolean }) {
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [replayTarget, setReplayTarget] = useState<Entry | null>(null);
  const [traceTarget, setTraceTarget] = useState<EventTraceTarget | null>(null);
  const [replayReason, setReplayReason] = useState("");
  const [replayPending, setReplayPending] = useState(false);
  const query = useQuery({
    queryKey: ["event-outbox", request],
    queryFn: ({ signal }) => api<PageResult<Entry>>(`/platform/event-outbox?${listRequestParams(request, { status: request.filters?.status ?? "" })}`, { signal }),
    refetchInterval: 10_000,
  });
  const formatTime = (value: string | null) => formatDateTime(value, locale, { dateStyle: "short", timeStyle: "medium" });
  const closeReplay = () => { if (!replayPending) { setReplayTarget(null); setReplayReason(""); } };
  const submitReplay = async () => {
    if (!replayTarget || replayPending) return;
    if (!replayReason.trim()) { void message.error(t("invalidEventReplay")); return; }
    setReplayPending(true);
    try {
      await mutate(`/platform/event-outbox/${replayTarget.id}/replay`, "POST", { reason: replayReason.trim() });
      closeReplay();
      await client.invalidateQueries({ queryKey: ["event-outbox"] });
      void message.success(t("settingsSaved"));
    } catch (error) {
      void message.error(t(messageKeyFor(error, eventErrorKeys)));
      if (error instanceof ApiError && error.status === 409) await query.refetch();
    } finally { setReplayPending(false); }
  };
  return <>
    <ResourceTable<Entry> 
      searchInitialValues={{ status: request.filters?.status }}
      result={query.error instanceof ApiError && query.error.status === 403 ? undefined : query.data}
      loading={query.isFetching} error={query.error} request={request} onChange={setRequest}
      onRetry={() => void query.refetch()}
      columns={[
      { title: t("eventSource"), dataIndex: "sourceType", search: true },
      { title: t("eventType"), dataIndex: "eventType", search: true },
      { title: t("eventKey"), dataIndex: "eventKey", search: true },
      { title: t("status"), dataIndex: "status", search: true, valueEnum: Object.fromEntries(Object.entries(statuses).map(([value, label]) => [value, { text: t(label) }])), render: (_, entry) => <Tag color={entry.status === "DELIVERED" ? "green" : entry.status === "DEAD_LETTER" || entry.status === "FAILED" ? "orange" : "default"}>{t(statuses[entry.status])}</Tag> },
      { title: t("eventAttempts"), dataIndex: "attempts", valueType: "digit" },
      { title: t("eventLastError"), dataIndex: "lastError", render: (_, entry) => entry.lastError ?? "—" },
      { title: t("eventCreatedAt"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.createdAt) },
      { title: t("eventDeliveredAt"), dataIndex: "deliveredAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.deliveredAt) },
      { title: t("actions"), key: "actions", search: false, render: (_: unknown, entry: Entry) => <TableActions>
        <Button type="link" data-testid={`event-trace-event-${entry.id}`} onClick={() => setTraceTarget({ kind: "event", eventId: entry.id })}>{t("eventTraceOpen")}</Button>
        {canReplay && entry.status === "DEAD_LETTER" && <Button type="link" onClick={() => { setReplayTarget(entry); setReplayReason(""); }}>{t("eventReplay")}</Button>}
      </TableActions> },
      ]}
    />
    <Modal open={!!replayTarget} title={t("eventReplay")} onCancel={closeReplay} onOk={() => void submitReplay()} okText={t("eventReplay")} cancelText={t("cancel")} okButtonProps={{ loading: replayPending, disabled: !replayReason.trim() }} cancelButtonProps={{ disabled: replayPending }} destroyOnHidden>
      <p>{t("eventReplayConfirm")}</p>
      <Input.TextArea aria-label={t("eventReplayReason")} value={replayReason} onChange={event => setReplayReason(event.target.value)} maxLength={1000} rows={4} disabled={replayPending} />
    </Modal>
    {traceTarget && <EventTraceDrawer target={traceTarget} onClose={() => setTraceTarget(null)} />}
  </>;
}
