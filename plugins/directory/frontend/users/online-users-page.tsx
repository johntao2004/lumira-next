"use client";

import { Tag } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, mutate } from "@/platform/api/client";
import { DeleteAction } from "@/platform/components/delete-action";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";

type OnlineSession = {
  id: string;
  userId: string;
  username: string;
  displayName: string;
  enabled: boolean;
  createdAt: string;
  lastAccessAt: string;
  expiresAt: string;
};

/** Global session console. It intentionally reuses the account session contract without exposing session cookies. */
export function OnlineUsersPage({ canTerminateSessions }: { canTerminateSessions: boolean }) {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const query = useQuery({
    queryKey: ["online-users", request],
    queryFn: ({ signal }) => api<PageResult<OnlineSession>>(`/platform/online-users?${listRequestParams(request)}`, { signal }),
    refetchInterval: 15_000,
  });
  const format = (value: string) => <time dateTime={value}>{formatDateTime(value, locale, { dateStyle: "short", timeStyle: "medium" })}</time>;
  return <ResourceTable<OnlineSession> result={query.data}
    loading={query.isFetching}
    error={query.error}
    request={request}
    onChange={setRequest}
    onRetry={() => void query.refetch()}
    columns={[
      { title: t("username"), dataIndex: "username", search: true },
      { title: t("name"), dataIndex: "displayName" },
      { title: t("status"), dataIndex: "enabled", valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
      { title: t("sessionCreated"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_, row) => format(row.createdAt) },
      { title: t("lastAccess"), dataIndex: "lastAccessAt", valueType: "dateTimeRange", render: (_, row) => format(row.lastAccessAt) },
      { title: t("sessionExpires"), dataIndex: "expiresAt", valueType: "dateTimeRange", render: (_, row) => format(row.expiresAt) },
      ...(canTerminateSessions ? [{ title: t("actions"), key: "actions", search: false, render: (_: unknown, row: OnlineSession) => <TableActions><DeleteAction name={row.username} actionLabel={t("revokeOnlineSession")} confirmation={t("revokeOnlineSessionConfirm", { name: row.username })} onDelete={async () => { await mutate(`/platform/online-users/${row.id}`, "DELETE", {}); await client.invalidateQueries({ queryKey: ["online-users"] }); }} /></TableActions> }] : []),
    ]}
  />;
}
