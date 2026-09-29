"use client";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Tag, Space, Tooltip, Typography } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api, mutate } from "@/platform/api/client";
import type { CurrentUser } from "@/platform/auth/api-types";
import type { UserSummary } from "@/platform/directory/api-types";
import { ResourceTable } from "@/platform/components/resource-table";
import { DeleteAction } from "@/platform/components/delete-action";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";

import { UserEditor } from "./user-editor";
export function UsersPage({ canCreate, canWrite, canAssignRoles, canAssignOrganizations }: { canCreate:boolean; canWrite: boolean; canAssignRoles: boolean; canAssignOrganizations:boolean }) {
  const [editing, setEditing] = useState<UserSummary | null | undefined>(undefined);
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const currentUser = client.getQueryData<CurrentUser>(["session"]);
  const [request, setRequest] = useListRequest();
  const query = useQuery({
    queryKey: ["users", request],
    queryFn: ({ signal }) => api<PageResult<UserSummary>>(`/platform/users?${listRequestParams(request)}`, { signal }),
  });
  return <>
    <UserEditor key={editing?.id ?? (editing === null ? "create" : "closed")} user={editing} canWrite={canWrite} canAssignRoles={canAssignRoles} canAssignOrganizations={canAssignOrganizations}
      onClose={() => setEditing(undefined)} onSaved={async () => {
        await Promise.all([
          query.refetch({ throwOnError: true }),
          client.invalidateQueries({ queryKey: ["online-users"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["session"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["user-assignments"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["user-file-quota-preview"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["files", "usage"] }, { throwOnError: true }),
        ]);
      }} />
    {query.data?.items.some(row => row.fileQuotaBytesOverride !== null || row.fileQuotaFilesOverride !== null)
      && <Alert className="mb-4" type="warning" showIcon message={t("userLegacyQuotaWarning")} />}
      <ResourceTable<UserSummary> actions={<Space wrap>{canCreate && <Button type="primary" onClick={() => setEditing(null)}>{t("createUser")}</Button>}<Button href={`/api/platform/users/export?${listRequestParams(request)}`} download>{t("exportUsers")}</Button></Space>} result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={() => { void query.refetch(); }} columns={[
    { title: t("username"), dataIndex: "username", search: true },
    { title: t("name"), dataIndex: "displayName", search: true },
    { title: t("status"), dataIndex: "enabled", search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
    { title: t("legacyQuotaRoleLabel"), search: false, render: (_, row) => row.fileQuotaBytesOverride !== null || row.fileQuotaFilesOverride !== null
      ? <Tooltip title={`${row.fileQuotaBytesOverride === null ? "—" : formatBytes(row.fileQuotaBytesOverride, locale)} · ${row.fileQuotaFilesOverride === null ? "—" : new Intl.NumberFormat(locale).format(row.fileQuotaFilesOverride)}`}><Tag color="warning">{t("legacyQuotaRoleLabel")}</Tag></Tooltip>
      : <Typography.Text type="secondary">—</Typography.Text> },
    ...(canWrite || canAssignRoles || canAssignOrganizations ? [{ title: t("actions"), search: false, render: (_: unknown, row: UserSummary) => <TableActions>{<Button type="link" onClick={() => setEditing(row)}>{t("edit")}</Button>}{canWrite && row.username !== currentUser?.username && <DeleteAction name={row.displayName} actionLabel={t("deleteRecord")} confirmation={t("deleteUserConfirm", { name: row.displayName })} onDelete={async () => { await mutate(`/platform/users/${row.id}?version=${row.version}`, "DELETE", {}); await Promise.all([client.invalidateQueries({ queryKey: ["users"] }), client.invalidateQueries({ queryKey: ["online-users"] })]); }} />}</TableActions> }] : []),
  ]} /></>;
}

function formatBytes(bytes:number,locale:string){const unit=bytes>=1024**3?3:bytes>=1024**2?2:bytes>=1024?1:0;return `${new Intl.NumberFormat(locale,{maximumFractionDigits:2}).format(bytes/1024**unit)} ${["B","KiB","MiB","GiB"][unit]}`;}
