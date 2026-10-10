"use client";

import { Alert, Tag } from "antd";
import { useQuery } from "@tanstack/react-query";
import { api, ApiError } from "@/platform/api/client";
import { listRequestParams, type PageResult } from "@/platform/api/pagination";
import { ResourceTable, type ProColumns } from "@/platform/components/resource-table";
import { formatDateTime } from "@/platform/i18n/format";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { useListRequest } from "@/platform/routing/use-list-request";

export type DeliveryEntry<State extends string = string> = {
  id: string;
  state: State;
  attempts: number;
  lastResult: string | null;
  availableAt: string;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
};

export type DeliveryTableProps<State extends string> = {
  endpoint: string;
  queryKey: string;
  states: Record<State, MessageKey>;
  results: Record<string, MessageKey>;
  labels: {
    hint: MessageKey;
    attempts: MessageKey;
    lastResult: MessageKey;
    scheduled: MessageKey;
    expires: MessageKey;
  };
};

/** Shared presentation and polling behavior for durable mail and SMS delivery queues. */
export function DeliveryTable<State extends string>({ endpoint, queryKey, states, results, labels }: DeliveryTableProps<State>) {
  const { t, locale } = usePreferences();
  const [request, setRequest] = useListRequest();
  const query = useQuery({
    queryKey: [queryKey, request],
    queryFn: ({ signal }) => api<PageResult<DeliveryEntry<State>>>(`${endpoint}?${listRequestParams(request, { state: request.filters?.state ?? "" })}`, { signal }),
    refetchInterval: 10_000,
  });
  const hasUncertainDelivery = query.data?.items.some(entry => entry.state === "UNCERTAIN");
  const valueEnum = (values: Record<string, MessageKey>) => Object.fromEntries(Object.entries(values).map(([value, label]) => [value, { text: t(label) }]));
  const formatTime = (value: string) => <time dateTime={value}>{formatDateTime(value, locale, { dateStyle: "short", timeStyle: "medium" })}</time>;
  const columns: ProColumns<DeliveryEntry<State>>[] = [
    { title: t("targetId"), dataIndex: "id", search: true },
    {
      title: t("status"), dataIndex: "state", search: true, valueEnum: valueEnum(states),
      render: (_, entry) => <Tag color={entry.state === "ACCEPTED" ? "green" : entry.state === "FAILED" || entry.state === "UNCERTAIN" ? "orange" : "default"}>{t(states[entry.state])}</Tag>,
    },
    { title: t(labels.attempts), dataIndex: "attempts", valueType: "digit" },
    {
      title: t(labels.lastResult), dataIndex: "lastResult", valueEnum: valueEnum(results),
      render: (_, entry) => entry.lastResult && results[entry.lastResult] ? t(results[entry.lastResult]) : "—",
    },
    { title: t("eventTime"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.createdAt) },
    { title: t("settingUpdatedAt"), dataIndex: "updatedAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.updatedAt) },
    { title: t(labels.scheduled), dataIndex: "availableAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.availableAt) },
    { title: t(labels.expires), dataIndex: "expiresAt", valueType: "dateTimeRange", render: (_, entry) => formatTime(entry.expiresAt) },
  ];

  return <>
    {hasUncertainDelivery && <Alert type="warning" showIcon title={t(labels.hint)} className="mb-4" />}
    <ResourceTable<DeliveryEntry<State>>
      searchInitialValues={{ state: request.filters?.state }}
      result={query.error instanceof ApiError && query.error.status === 403 ? undefined : query.data}
      loading={query.isFetching}
      error={query.error}
      request={request}
      onChange={setRequest}
      onRetry={() => void query.refetch()}
      columns={columns}
    />
  </>;
}
