"use client";

import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { history } from "@umijs/max";
import { Tag } from "antd";
import { AccountSettingsLoadingSkeleton } from "./account-settings-layout";
import { TableActions } from "@/platform/components/table-actions";
import { api, mutate } from "@/platform/api/client";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { DeleteAction } from "@/platform/components/delete-action";
import { usePreferences } from "@/platform/providers";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import "./account-form-surface.css";

type Session = { id: string; createdAt: string; lastAccessAt: string; expiresAt: string; current: boolean };

export function SessionsDrawer({ open, onClose, embedded = false }: { open: boolean; onClose: () => void; embedded?: boolean }) {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const query = useQuery({
    queryKey: ["account-sessions", request],
    enabled: open || embedded,
    queryFn: ({ signal }) => api<PageResult<Session>>(`/account/sessions?${listRequestParams(request)}`, { signal }),
    staleTime: 0,
  });
  const format = (value: string) => formatDateTime(value, locale, { dateStyle: "medium", timeStyle: "medium" });

  if (embedded && query.isPending && !query.data && !query.error) {
    return <div className="account-settings-form-surface">
      <AccountSettingsLoadingSkeleton routeId="account-sessions" />
    </div>;
  }

  const content = <ResourceTable<Session>
    result={query.data}
    loading={query.isFetching}
    error={query.error}
    request={request}
    onChange={setRequest}
    onRetry={() => void query.refetch()}
    actions={<DeleteAction name="" actionLabel={t("signOutOthers")} confirmation={t("signOutOthersConfirm")} buttonType="primary" onDelete={async () => {
      await mutate("/account/sessions/others", "DELETE", {});
      setRequest({ ...request, page: 1 });
      await client.invalidateQueries({ queryKey: ["account-sessions"] });
    }} />}
    columns={[
      { title: t("serialNumber"), key: "serialNumber", width: 80, search: false, render: (_, __, index) => (request.page - 1) * request.pageSize + index + 1 },
      { title: t("sessionCreated"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_, session) => format(session.createdAt) },
      { title: t("lastAccess"), dataIndex: "lastAccessAt", valueType: "dateTimeRange", render: (_, session) => format(session.lastAccessAt) },
      { title: t("sessionExpires"), dataIndex: "expiresAt", valueType: "dateTimeRange", render: (_, session) => format(session.expiresAt) },
      { title: t("status"), dataIndex: "current", search: true, valueEnum: { true: { text: t("currentSession") }, false: { text: t("otherSession") } }, render: (_, session) => session.current ? <Tag color="success">{t("currentSession")}</Tag> : t("otherSession") },
      {
        title: t("actions"), key: "actions", search: false, render: (_, session) => <TableActions><DeleteAction
          name=""
          actionLabel={t("logout")}
          confirmation={t(session.current ? "signOutCurrentConfirm" : "signOutSessionConfirm")}
          onDelete={async () => {
            await mutate(`/account/sessions/${session.id}`, "DELETE", {});
            if (session.current) {
              client.clear();
              onClose();
              history.replace("/user/login");
            } else {
              if (query.data?.items.length === 1 && request.page > 1) setRequest({ ...request, page: request.page - 1 });
              await client.invalidateQueries({ queryKey: ["account-sessions"] });
            }
          }}
        /></TableActions>,
      },
    ]}
  />;

  return embedded ? <div className="account-settings-form-surface">{content}</div> : <PlatformDrawer open={open} onClose={onClose} title={t("mySessions")} size={900} destroyOnHidden>{content}</PlatformDrawer>;
}
