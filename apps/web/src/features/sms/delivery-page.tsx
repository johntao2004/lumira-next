"use client";

import { Alert, Tag } from "antd";
import { useQuery } from "@tanstack/react-query";
import { api, ApiError } from "@/platform/api/client";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import type { MessageKey } from "@/platform/i18n/messages";
import { listRequestParams } from "@/platform/api/pagination";

type Entry = {
  id: string;
  state: keyof typeof states;
  attempts: number;
  lastResult: string | null;
  availableAt: string;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
};
const states = {
  PENDING: "smsPending", LEASED: "smsLeased", SENDING: "smsSending",
  ACCEPTED: "smsAccepted", FAILED: "smsFailed", UNCERTAIN: "smsUncertain", EXPIRED: "smsExpired",
} as const satisfies Record<string, MessageKey>;
const results: Record<string, MessageKey> = {
  ACCEPTED: "smsAccepted", AUTHENTICATION_FAILED: "smsAuthenticationFailed",
  CONNECTION_FAILED: "smsConnectionFailed", DELIVERY_UNCERTAIN: "smsDeliveryUncertain",
  CONFIGURATION_UNAVAILABLE: "smsConfigurationUnavailable", LEASE_EXPIRED: "smsLeaseExpired",
};

export function SmsDeliveryPage() {
  const { t, locale } = usePreferences();
  const [request, setRequest] = useListRequest();
  const query = useQuery({
    queryKey: ["sms-deliveries", request],
    queryFn: ({ signal }) => api<PageResult<Entry>>(`/platform/sms-deliveries?${listRequestParams(request,{state:request.filters?.state??""})}`, { signal }),
    refetchInterval: 10_000,
  });
  const formatTime = (value: string) => <time dateTime={value}>
    {formatDateTime(value, locale, { dateStyle: "short", timeStyle: "medium" })}
  </time>;
  const hasUncertainDelivery = query.data?.items.some((entry) => entry.state === "UNCERTAIN");
  return <>
    {hasUncertainDelivery && <Alert type="warning" showIcon title={t("smsDeliveryHint")} className="mb-4" />}
    <ResourceTable<Entry> 
      searchInitialValues={{ state: request.filters?.state }}
      result={query.error instanceof ApiError && query.error.status === 403 ? undefined : query.data}
      loading={query.isFetching} error={query.error} request={request} onChange={setRequest}
      onRetry={() => void query.refetch()}
      columns={[
        { title: t("targetId"), dataIndex: "id", search: true },
        { title: t("status"), dataIndex: "state", search: true, valueEnum: Object.fromEntries(Object.entries(states).map(([value,label])=>[value,{text:t(label)}])), render: (_, entry) => <Tag color={entry.state === "ACCEPTED" ? "green" : entry.state === "FAILED" || entry.state === "UNCERTAIN" ? "orange" : "default"}>{t(states[entry.state])}</Tag> },
        { title: t("smsAttempts"), dataIndex: "attempts", valueType: "digit" },
        { title: t("smsLastResult"), dataIndex: "lastResult", valueEnum: Object.fromEntries(Object.entries(results).map(([value, label]) => [value, { text: t(label) }])), render: (_, entry) => entry.lastResult && results[entry.lastResult] ? t(results[entry.lastResult]) : "—" },
        { title: t("eventTime"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.createdAt) },
        { title: t("settingUpdatedAt"), dataIndex: "updatedAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.updatedAt) },
        { title: t("smsScheduled"), dataIndex: "availableAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.availableAt) },
        { title: t("smsExpires"), dataIndex: "expiresAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.expiresAt) },
      ]}
    />
  </>;
}
