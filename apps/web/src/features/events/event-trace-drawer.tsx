"use client";

import { useState } from "react";
import { Alert, Button, Descriptions, Empty, List, Pagination, Skeleton, Space, Tag, Timeline, Typography } from "antd";
import { useQuery } from "@tanstack/react-query";
import { ApiError, api } from "@/platform/api/client";
import type { PageResult } from "@/platform/api/pagination";
import { formatDateTime } from "@/platform/i18n/format";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { PlatformDrawer } from "@/platform/components/platform-drawer";

const TRACE_PAGE_SIZE = 20;
const TRACE_REFRESH_MS = 5_000;

type HistoryStatus = "AVAILABLE" | "PARTIAL" | "EXPIRED" | "NOT_RECORDED" | "NO_EVENTS";
type DeliveryResult = {
  known: boolean;
  expectedInboxCount: number | null;
  inboxCreated: number | null;
  inboxSkipped: number | null;
  skippedByReason: Record<string, number>;
  completedAt: string | null;
  resultCode: string | null;
};
type TraceOperation = {
  operationId: string;
  operationType: string;
  auditOperationId: string | null;
  businessType: string;
  businessId: string;
  businessVersion: number | null;
  outcomeCode: string | null;
  expectedEventCount: number;
  completedEventCount: number;
  result: DeliveryResult;
  historyStatus: HistoryStatus;
};
type TraceEvent = {
  id: string;
  operationId: string | null;
  sourceType: string;
  eventType: string;
  status: string;
  attempts: number;
  generation: number;
  lastErrorCode: string | null;
  availableAt: string | null;
  deliveredAt: string | null;
  createdAt: string;
  updatedAt: string;
  result: DeliveryResult;
};
type OperationEvent = {
  event: TraceEvent;
};
type OperationTraceResponse = {
  operation: TraceOperation;
  items: OperationEvent[];
  total: number;
  page: number;
  pageSize: number;
  historyStatus: HistoryStatus;
};
type HistoryEntry = {
  sequence: number;
  attempt: number;
  generation: number;
  phase: "CLAIMED" | "SUCCEEDED" | "FAILED" | "NO_CONSUMER" | "LEASE_RECOVERED" | "REPLAY_APPROVED" | string;
  status: string | null;
  occurredAt: string;
  nextRetryAt: string | null;
  errorCode: string | null;
  auditOperationId: string | null;
  result: DeliveryResult | null;
  actor: string | null;
  reason: string | null;
};
type EventTraceResponse = {
  event: TraceEvent;
  operation: TraceOperation | null;
  historyStatus: HistoryStatus;
  history: PageResult<HistoryEntry>;
};

export type EventTraceTarget =
  | { kind: "event"; eventId: string }
  | { kind: "operation"; auditId: string };

const eventStatusKeys: Record<string, MessageKey> = {
  PENDING: "eventPending",
  PROCESSING: "eventProcessing",
  DELIVERED: "eventDelivered",
  FAILED: "eventFailed",
  DEAD_LETTER: "eventDeadLetter",
};
const historyPhaseKeys: Record<string, MessageKey> = {
  CLAIMED: "eventTraceClaimed",
  SUCCEEDED: "eventTraceSucceeded",
  FAILED: "eventTraceFailure",
  NO_CONSUMER: "eventTraceNoConsumer",
  LEASE_RECOVERED: "eventTraceLeaseRecovered",
  REPLAY_APPROVED: "eventTraceReplayApproved",
};

function statusText(status: string, t: (key: MessageKey) => string) {
  const key = eventStatusKeys[status];
  return key ? t(key) : status;
}

function historyText(phase: string, t: (key: MessageKey) => string) {
  const key = historyPhaseKeys[phase];
  return key ? t(key) : phase;
}

function historyStatusKey(status: HistoryStatus): MessageKey {
  switch (status) {
    case "AVAILABLE": return "eventTraceHistoryAvailable";
    case "PARTIAL": return "eventTraceHistoryPartial";
    case "EXPIRED": return "eventTraceHistoryExpired";
    case "NOT_RECORDED": return "eventTraceHistoryNotRecorded";
    case "NO_EVENTS": return "eventTraceNoEvents";
  }
}

function formatTime(value: string | null | undefined, locale: string) {
  return value ? formatDateTime(value, locale, { dateStyle: "medium", timeStyle: "medium" }) : "—";
}

function traceCodeText(code: string, t: (key: MessageKey) => string) {
  const keys: Record<string, MessageKey> = {
    "notification.broadcast": "eventTraceNotificationBroadcast",
    "notification.broadcast.requested": "eventTraceNotificationBroadcast",
    "feedback.notification.requested": "eventTraceFeedbackNotification",
    "feedback.create": "eventTraceFeedbackCreate",
    "feedback.handle": "eventTraceFeedbackHandle",
    "feedback.created": "eventTraceFeedbackCreate",
    "feedback.handled": "eventTraceFeedbackHandle",
    FEEDBACK: "eventTraceBusinessFeedback",
    BROADCAST: "eventTraceBusinessBroadcast",
    ACCOUNT: "eventTraceBusinessAccount",
    account: "eventTraceBusinessAccount",
    feedback: "eventTraceFeedbackSource",
    notification: "eventTraceNotificationSource",
    notifications: "eventTraceNotificationSource",
    EVENTS_PENDING: "eventTraceEventsPending",
    RETRY_PENDING: "eventTraceRetryPending",
    NO_CONSUMER: "eventTraceNoConsumer",
    DEAD_LETTER: "eventDeadLetter",
    PARTIAL: "eventTracePartialResult",
    DELIVERED: "eventDelivered",
    ALL_SKIPPED: "eventTraceAllSkipped",
    SUPPRESSED_GLOBAL_MANAGER: "eventTraceSuppressedGlobalManager",
    NO_RECIPIENT: "eventTraceNoRecipients",
    EXPIRED: "eventTraceExpired",
    RECIPIENT_INACTIVE: "eventTraceAccountDisabled",
    RECIPIENT_DELETED: "eventTraceAccountDeleted",
    DUPLICATE: "eventTraceAlreadyExists",
    GLOBAL_FEEDBACK_MANAGER: "eventTraceGlobalFeedbackManager",
    RECIPIENT_UNAVAILABLE: "eventTraceRecipientUnavailable",
  };
  const key = keys[code];
  return key ? t(key) : code;
}

function TraceCodeText({ code }: { code: string }) {
  const { t } = usePreferences();
  const label = traceCodeText(code, t);
  return <Space wrap size={[4, 2]}>
    <Typography.Text style={{ overflowWrap: "anywhere" }}>{label}</Typography.Text>
    {label !== code && <Typography.Text type="secondary" code style={{ whiteSpace: "normal", wordBreak: "break-all" }}>{code}</Typography.Text>}
  </Space>;
}

function FailureAlert({ error, pending, onRetry }: { error: unknown; pending: boolean; onRetry: () => void }) {
  const { t } = usePreferences();
  if (!error) return null;
  const message = error instanceof ApiError && error.status === 403 ? t("forbidden") : t("unavailable");
  return <Alert type="error" showIcon message={message} action={<Button size="small" loading={pending} onClick={onRetry}>{t("retry")}</Button>} />;
}

function ResultSummary({ result, completedAt, locale, resultCode, unknownState = "pending" }: {
  result: DeliveryResult;
  completedAt: string | null;
  locale: string;
  resultCode?: string | null;
  unknownState?: "pending" | "notRecorded" | "unavailable";
}) {
  const { t } = usePreferences();
  return <div data-testid="event-trace-result">
    <Typography.Title level={5}>{t("eventTraceResult")}</Typography.Title>
    {result.known ? <>
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
        <Descriptions.Item label={t("eventTraceExpectedInbox")}>{result.expectedInboxCount ?? "—"}</Descriptions.Item>
        <Descriptions.Item label={t("eventTraceInboxCreated")}>{result.inboxCreated ?? "—"}</Descriptions.Item>
        <Descriptions.Item label={t("eventTraceInboxSkipped")}>{result.inboxSkipped ?? "—"}</Descriptions.Item>
        <Descriptions.Item label={t("eventTraceCompletedAt")}>{formatTime(completedAt, locale)}</Descriptions.Item>
      </Descriptions>
      {(result.inboxSkipped ?? 0) > 0 && <Alert style={{ marginTop: 12 }} type="warning" showIcon message={t("eventTraceRecipientsSkipped", { count: result.inboxSkipped ?? 0 })} />}
      {Object.keys(result.skippedByReason ?? {}).length > 0 && <div style={{ marginTop: 8 }}>
        <Typography.Text type="secondary">{t("eventTraceSkipReasons")}</Typography.Text>
        <List size="small" dataSource={Object.entries(result.skippedByReason)} renderItem={([reason, count]) => <List.Item><Space wrap><TraceCodeText code={reason} /><Typography.Text>{count}</Typography.Text></Space></List.Item>} />
      </div>}
    </> : <Alert style={{ marginTop: 8 }} type={unknownState === "pending" ? "info" : "warning"} showIcon message={t(
      unknownState === "notRecorded" ? "eventTraceResultNotRecorded" : unknownState === "unavailable" ? "eventTraceResultUnavailable" : "eventTraceResultPending",
    )} />}
    {resultCode && <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
      {t("eventTraceResultCode")}: <TraceCodeText code={resultCode} />
    </Typography.Paragraph>}
  </div>;
}

function EventDetail({ eventId, onBack }: { eventId: string; onBack?: () => void }) {
  const { t, locale } = usePreferences();
  const [historyPage, setHistoryPage] = useState(1);
  const query = useQuery({
    queryKey: ["event-trace", eventId, historyPage],
    queryFn: ({ signal }) => api<EventTraceResponse>(`/platform/event-outbox/${encodeURIComponent(eventId)}/trace?page=${historyPage}&pageSize=${TRACE_PAGE_SIZE}`, { signal }),
    retry: false,
    refetchInterval: (current) => current.state.error ? false : TRACE_REFRESH_MS,
    refetchIntervalInBackground: false,
  });
  const trace = query.error instanceof ApiError && query.error.status === 403 ? undefined : query.data;
  const history = trace?.history;

  return <Space direction="vertical" size="middle" style={{ width: "100%" }}>
    {onBack && <Button type="link" style={{ paddingInline: 0, alignSelf: "flex-start" }} onClick={onBack}>{t("eventTraceBackToOperation")}</Button>}
    <FailureAlert error={query.error} pending={query.isFetching} onRetry={() => void query.refetch()} />
    {query.isPending && <Skeleton active paragraph={{ rows: 6 }} />}
    {trace && <>
      <Alert type="info" showIcon message={t(historyStatusKey(trace.historyStatus))} />
      <section>
        <Typography.Title level={5}>{t("eventTraceEvent")}</Typography.Title>
        <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
          <Descriptions.Item label={t("eventTraceEventId")}><Typography.Text code copyable style={{ wordBreak: "break-all" }}>{trace.event.id}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label={t("eventSource")}><TraceCodeText code={trace.event.sourceType} /></Descriptions.Item>
          <Descriptions.Item label={t("eventType")}><TraceCodeText code={trace.event.eventType} /></Descriptions.Item>
          <Descriptions.Item label={t("status")}><Tag color={trace.event.status === "DELIVERED" ? "success" : trace.event.status === "FAILED" || trace.event.status === "DEAD_LETTER" ? "error" : "processing"}>{statusText(trace.event.status, t)}</Tag></Descriptions.Item>
          <Descriptions.Item label={t("eventAttempts")}>{trace.event.attempts}</Descriptions.Item>
          <Descriptions.Item label={t("eventCreatedAt")}>{formatTime(trace.event.createdAt, locale)}</Descriptions.Item>
          <Descriptions.Item label={t("eventDeliveredAt")}>{formatTime(trace.event.deliveredAt, locale)}</Descriptions.Item>
          <Descriptions.Item label={t("eventTraceNextRetry")}>{trace.event.status === "PENDING" || trace.event.status === "FAILED" ? formatTime(trace.event.availableAt, locale) : "—"}</Descriptions.Item>
          {trace.event.lastErrorCode && <Descriptions.Item label={t("eventTraceErrorCode")}><TraceCodeText code={trace.event.lastErrorCode} /></Descriptions.Item>}
        </Descriptions>
      </section>

      {trace.operation && <section>
        <Typography.Title level={5}>{t("eventTraceBusinessOperation")}</Typography.Title>
        <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
          <Descriptions.Item label={t("eventTraceOperationType")}><Typography.Text style={{ wordBreak: "break-word" }}>{traceCodeText(trace.operation.operationType, t)}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label={t("eventTraceBusinessType")}>{traceCodeText(trace.operation.businessType, t)}</Descriptions.Item>
          <Descriptions.Item label={t("eventTraceBusinessId")}><Typography.Text code copyable style={{ wordBreak: "break-all" }}>{trace.operation.businessId}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label={t("eventTraceBusinessVersion")}>{trace.operation.businessVersion ?? "—"}</Descriptions.Item>
          <Descriptions.Item label={t("eventTraceOperationId")}><Typography.Text code copyable style={{ wordBreak: "break-all" }}>{trace.operation.operationId}</Typography.Text></Descriptions.Item>
        </Descriptions>
      </section>}

      <ResultSummary
        result={trace.event.result}
        completedAt={trace.event.result.completedAt}
        resultCode={trace.event.result.resultCode}
        locale={locale}
        unknownState={trace.event.status === "DELIVERED" ? "notRecorded" : trace.event.status === "DEAD_LETTER" ? "unavailable" : "pending"}
      />

      <section data-testid="event-trace-history">
        <Typography.Title level={5}>{t("eventTraceHistory")}</Typography.Title>
        {trace.historyStatus === "NOT_RECORDED" || trace.historyStatus === "EXPIRED"
          ? <Alert type="warning" showIcon message={t(historyStatusKey(trace.historyStatus))} />
          : (history?.items.length ?? 0) === 0
            ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("eventTraceNoHistory")} />
            : <Timeline items={(history?.items ?? []).map(entry => ({
              key: entry.sequence,
              children: <div style={{ minWidth: 0 }}>
                <Space wrap size={[8, 4]}>
                  <Typography.Text strong>{historyText(entry.phase, t)}</Typography.Text>
                  <Tag>{t("eventTraceAttempt", { count: entry.attempt })}</Tag>
                  <Tag>{t("eventTraceGeneration", { count: entry.generation })}</Tag>
                  {entry.status && <Tag>{statusText(entry.status, t)}</Tag>}
                  <Typography.Text type="secondary">{formatTime(entry.occurredAt, locale)}</Typography.Text>
                </Space>
                {entry.nextRetryAt && <Typography.Paragraph type="secondary" style={{ marginBlock: 4 }}>{t("eventTraceNextRetry")}: {formatTime(entry.nextRetryAt, locale)}</Typography.Paragraph>}
                {entry.errorCode && <Typography.Paragraph type="danger" style={{ marginBlock: 4, overflowWrap: "anywhere" }}>{t("eventTraceErrorCode")}: <Typography.Text code style={{ whiteSpace: "normal", wordBreak: "break-all" }}>{entry.errorCode}</Typography.Text></Typography.Paragraph>}
                {entry.result?.known && <Typography.Paragraph type="secondary" style={{ marginBlock: 4 }}>{t("eventTraceInboxCreated")}: {entry.result.inboxCreated} · {t("eventTraceInboxSkipped")}: {entry.result.inboxSkipped}</Typography.Paragraph>}
                {entry.result?.known && entry.result.resultCode && <Typography.Paragraph type="secondary" style={{ marginBlock: 4, overflowWrap: "anywhere" }}>{t("eventTraceResultCode")}: <TraceCodeText code={entry.result.resultCode} /></Typography.Paragraph>}
                {entry.result?.known && Object.keys(entry.result.skippedByReason ?? {}).length > 0 && <Space wrap size={[4, 2]} style={{ marginBlock: 4 }}>
                  {Object.entries(entry.result.skippedByReason).map(([reason, count]) => <Tag key={reason}><TraceCodeText code={reason} /> · {count}</Tag>)}
                </Space>}
                {entry.actor && <Typography.Paragraph type="secondary" style={{ marginBlock: 4, overflowWrap: "anywhere" }}>{t("eventTraceActor")}: {entry.actor}</Typography.Paragraph>}
                {entry.reason && <Typography.Paragraph type="secondary" style={{ marginBlock: 4, wordBreak: "break-word", overflowWrap: "anywhere" }}>{t("eventTraceReason")}: {entry.reason}</Typography.Paragraph>}
              </div>,
            }))} />}
        {history && history.total > history.pageSize && <Pagination size="small" showSizeChanger={false} style={{ marginTop: 12 }} current={history.page} pageSize={history.pageSize} total={history.total} onChange={page => setHistoryPage(page)} />}
      </section>
    </>}
  </Space>;
}

function OperationDetail({ auditId, onSelectEvent }: { auditId: string; onSelectEvent: (eventId: string) => void }) {
  const { t, locale } = usePreferences();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(TRACE_PAGE_SIZE);
  const query = useQuery({
    queryKey: ["event-operation-trace", auditId, page, pageSize],
    queryFn: ({ signal }) => api<OperationTraceResponse>(`/platform/event-outbox/operations/${encodeURIComponent(auditId)}/trace?page=${page}&pageSize=${pageSize}`, { signal }),
    retry: false,
    refetchInterval: (current) => current.state.error ? false : TRACE_REFRESH_MS,
    refetchIntervalInBackground: false,
  });
  const trace = query.error instanceof ApiError && query.error.status === 403 ? undefined : query.data;
  const operation = trace?.operation;
  const operationResult = operation?.result.known ? operation.result : null;
  const suppressed = operation?.outcomeCode === "SUPPRESSED_GLOBAL_MANAGER";
  const operationPending = operation?.outcomeCode === "EVENTS_PENDING" || operation?.outcomeCode === "RETRY_PENDING"
    || Boolean(operation && operation.expectedEventCount > operation.completedEventCount && !operation.outcomeCode);
  const unavailableOperationResultKey: MessageKey = operation?.historyStatus === "NOT_RECORDED"
    ? "eventTraceResultUnavailableHistory"
    : operationPending ? "eventTraceEventProgress" : "eventTraceOperationResultUnavailable";

  return <Space direction="vertical" size="middle" style={{ width: "100%" }}>
    <FailureAlert error={query.error} pending={query.isFetching} onRetry={() => void query.refetch()} />
    {query.isPending && <Skeleton active paragraph={{ rows: 5 }} />}
    {trace && <>
      {operation && <section>
        <Typography.Title level={5}>{t("eventTraceBusinessOperation")}</Typography.Title>
        <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
          <Descriptions.Item label={t("eventTraceOperationType")}><TraceCodeText code={operation.operationType} /></Descriptions.Item>
          <Descriptions.Item label={t("eventTraceBusinessType")}><TraceCodeText code={operation.businessType} /></Descriptions.Item>
          <Descriptions.Item label={t("eventTraceBusinessId")}><Typography.Text code copyable style={{ wordBreak: "break-all" }}>{operation.businessId}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label={t("eventTraceBusinessVersion")}>{operation.businessVersion ?? "—"}</Descriptions.Item>
          <Descriptions.Item label={t("eventTraceOperationId")}><Typography.Text code copyable style={{ wordBreak: "break-all" }}>{operation.operationId}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label={t("eventTraceAuditId")}><Typography.Text code copyable style={{ wordBreak: "break-all" }}>{auditId}</Typography.Text></Descriptions.Item>
        </Descriptions>
      </section>}
      {trace.historyStatus !== "AVAILABLE" && trace.historyStatus !== "NO_EVENTS" && <Alert type={trace.historyStatus === "PARTIAL" || trace.historyStatus === "EXPIRED" ? "warning" : "info"} showIcon message={t(historyStatusKey(trace.historyStatus))} />}
      {suppressed && <Alert type="warning" showIcon message={t("eventTraceSuppressedGlobalManager")} />}
      {operationResult && <ResultSummary result={operationResult} completedAt={operationResult.completedAt} resultCode={operation?.outcomeCode ?? operationResult.resultCode} locale={locale} />}
      {!operationResult && operation && <Alert type={operationPending ? "info" : "warning"} showIcon message={<Space wrap>
        <Typography.Text>{unavailableOperationResultKey === "eventTraceEventProgress"
          ? t("eventTraceEventProgress", { completed: operation.completedEventCount, total: operation.expectedEventCount })
          : t(unavailableOperationResultKey)}</Typography.Text>
        {operation.outcomeCode && <TraceCodeText code={operation.outcomeCode} />}
      </Space>} />}
      <section data-testid="event-trace-operation-events">
        <Typography.Title level={5}>{t("eventTraceAssociatedEvents")}</Typography.Title>
        {trace.items.length === 0
          ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t(suppressed ? "eventTraceSuppressedGlobalManager" : "eventTraceNoEvents")} />
          : <List size="small" dataSource={trace.items} renderItem={({ event }) => <List.Item data-testid={`event-trace-event-${event.id}`} actions={[<Button key="open" type="link" size="small" data-testid={`event-trace-open-event-${event.id}`} onClick={() => onSelectEvent(event.id)}>{t("eventTraceViewEvent")}</Button>]}>
            <List.Item.Meta
              title={<Space wrap size={[8, 4]}><Typography.Text strong><TraceCodeText code={event.eventType} /></Typography.Text><Tag><TraceCodeText code={event.sourceType} /></Tag><Tag color={event.status === "DELIVERED" ? "success" : event.status === "FAILED" || event.status === "DEAD_LETTER" ? "error" : "processing"}>{statusText(event.status, t)}</Tag></Space>}
              description={<Space wrap size={[8, 4]}><Typography.Text type="secondary">{t("eventTraceEventId")}: <Typography.Text code style={{ overflowWrap: "anywhere" }}>{event.id}</Typography.Text></Typography.Text><Typography.Text type="secondary">{t("eventAttempts")}: {event.attempts}</Typography.Text><Typography.Text type="secondary">{formatTime(event.createdAt, locale)}</Typography.Text>{event.result.known && <Typography.Text type="secondary">{t("eventTraceInboxCreated")}: {event.result.inboxCreated} · {t("eventTraceInboxSkipped")}: {event.result.inboxSkipped}</Typography.Text>}</Space>}
            />
          </List.Item>} />}
        {trace.total > trace.pageSize && <Pagination size="small" showSizeChanger current={trace.page} pageSize={trace.pageSize} pageSizeOptions={["10", "20", "50"]} total={trace.total} onChange={(nextPage, nextPageSize) => { setPage(nextPage); setPageSize(nextPageSize); }} />}
      </section>
    </>}
  </Space>;
}

/** Shared trace view for one exact audit operation or a single Outbox event. */
export function EventTraceDrawer({ target, onClose, limitedPermission = false }: {
  target: EventTraceTarget;
  onClose: () => void;
  limitedPermission?: boolean;
}) {
  const { t } = usePreferences();
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const eventId = selectedEventId ?? (target.kind === "event" ? target.eventId : null);
  const canReturn = target.kind === "operation" && selectedEventId !== null;

  return <PlatformDrawer open placement="right" size={760} title={t("eventTraceTitle")} onClose={onClose} destroyOnHidden>
    <Space direction="vertical" size="middle" style={{ width: "100%" }} data-testid="event-trace-drawer">
      {limitedPermission && <Alert type="info" showIcon message={t("eventTraceLimitedPermission")} />}
      {target.kind === "operation" && !eventId
        ? <OperationDetail key={target.auditId} auditId={target.auditId} onSelectEvent={setSelectedEventId} />
        : eventId && <EventDetail key={eventId} eventId={eventId} onBack={canReturn ? () => setSelectedEventId(null) : undefined} />}
    </Space>
  </PlatformDrawer>;
}
