"use client";
import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, App, Button, Tag, Space, Spin, Tooltip, Typography } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api, apiBlob, isRolePreviewReadOnly, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import type { CurrentUser } from "@/platform/auth/api-types";
import type { UserSummary } from "@/platform/directory/api-types";
import { ResourceTable } from "@/platform/components/resource-table";
import { DeleteAction } from "@/platform/components/delete-action";
import { AsyncButton } from "@/platform/components/async-button";
import type { PageResult } from "@/platform/api/pagination";
import { currentSessionRevision } from "@/platform/auth/session-lifecycle";
import { downloadBlob } from "@/platform/files/download-blob";
import { usePreferences } from "@/platform/providers";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import { useFragmentParams } from "@/platform/routing/use-tab-anchor";
import { QueryFailure } from "@/platform/components/query-failure";
import { formatQuotaBytes } from "@/platform/i18n/format";

import { UserEditor } from "./user-editor";
export function UsersPage({ canCreate, canWrite, canAssignRoles, canAssignOrganizations, readOnlyPreview = false }: { canCreate:boolean; canWrite: boolean; canAssignRoles: boolean; canAssignOrganizations:boolean; readOnlyPreview?: boolean }) {
  const [fragment, updateFragment] = useFragmentParams();
  const editorTarget = fragment.get("user");
  const previewReadOnly = readOnlyPreview || isRolePreviewReadOnly();
  const effectiveCanCreate = canCreate && !previewReadOnly;
  const effectiveCanWrite = canWrite && !previewReadOnly;
  const effectiveCanAssignRoles = canAssignRoles && !previewReadOnly;
  const effectiveCanAssignOrganizations = canAssignOrganizations && !previewReadOnly;
  const canEdit = effectiveCanWrite || effectiveCanAssignRoles || effectiveCanAssignOrganizations;
  const existingTarget = canEdit && editorTarget && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(editorTarget) ? editorTarget : null;
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const [exportPending, setExportPending] = useState(false);
  const exportPendingRef = useRef(false);
  const exportController = useRef<AbortController | null>(null);
  const client = useQueryClient();
  const currentUser = client.getQueryData<CurrentUser>(["session"]);
  const editorQuery = useQuery({
    queryKey: ["user", existingTarget],
    enabled: existingTarget !== null,
    queryFn: ({ signal }) => api<UserSummary>(`/platform/users/${existingTarget}`, { signal }),
  });
  const editing = editorTarget === "create" && effectiveCanCreate ? null : existingTarget ? editorQuery.data : undefined;
  const setEditing = (next: UserSummary | null | undefined) => updateFragment({ user: next === null ? "create" : next?.id ?? null });
  const [request, setRequest] = useListRequest();
  const query = useQuery({
    queryKey: ["users", request],
    queryFn: ({ signal }) => api<PageResult<UserSummary>>(`/platform/users?${listRequestParams(request)}`, { signal }),
  });
  useEffect(() => {
    if (previewReadOnly && editorTarget) updateFragment({ user: null });
  }, [previewReadOnly, editorTarget, updateFragment]);
  useEffect(() => () => {
    exportController.current?.abort();
    exportController.current = null;
  }, []);
  async function exportUsers() {
    if (previewReadOnly || isRolePreviewReadOnly() || exportPendingRef.current) return;
    exportPendingRef.current = true;
    setExportPending(true);
    const controller = new AbortController();
    const sessionRevision = currentSessionRevision();
    exportController.current = controller;
    try {
      const blob = await apiBlob(`/platform/users/export?${listRequestParams(request)}`, { signal: controller.signal });
      controller.signal.throwIfAborted();
      if (sessionRevision !== currentSessionRevision()) return;
      downloadBlob(blob, "users.csv");
    } catch (error) {
      if (controller.signal.aborted || sessionRevision !== currentSessionRevision()) return;
      void message.error(t(messageKeyFor(error, ["forbidden"])));
    } finally {
      if (exportController.current === controller) {
        exportController.current = null;
        exportPendingRef.current = false;
        setExportPending(false);
      }
    }
  }
  return <>
    {existingTarget && !editorQuery.data && (editorQuery.error
      ? <QueryFailure title={t("unavailable")} pending={editorQuery.isFetching} onRetry={() => void editorQuery.refetch()} />
      : <Spin />)}
    <UserEditor key={editing?.id ?? (editing === null ? "create" : "closed")} user={editing} canWrite={effectiveCanWrite} canAssignRoles={effectiveCanAssignRoles} canAssignOrganizations={effectiveCanAssignOrganizations}
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
      <ResourceTable<UserSummary> actions={previewReadOnly ? null : <Space wrap>{effectiveCanCreate && <Button type="primary" onClick={() => setEditing(null)}>{t("createUser")}</Button>}<AsyncButton type="link" label={t("exportUsers")} pending={exportPending} onClick={() => void exportUsers()} /></Space>} result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={() => { void query.refetch(); }} columns={[
    { title: t("username"), dataIndex: "username", search: true },
    { title: t("name"), dataIndex: "displayName", search: true },
    { title: t("status"), dataIndex: "enabled", search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
    { title: t("legacyQuotaRoleLabel"), search: false, render: (_, row) => row.fileQuotaBytesOverride !== null || row.fileQuotaFilesOverride !== null
      ? <Tooltip title={`${row.fileQuotaBytesOverride === null ? "—" : formatQuotaBytes(row.fileQuotaBytesOverride, locale)} · ${row.fileQuotaFilesOverride === null ? "—" : new Intl.NumberFormat(locale).format(row.fileQuotaFilesOverride)}`}><Tag color="warning">{t("legacyQuotaRoleLabel")}</Tag></Tooltip>
      : <Typography.Text type="secondary">—</Typography.Text> },
    ...(canEdit ? [{ title: t("actions"), key: "actions", search: false, render: (_: unknown, row: UserSummary) => <TableActions><Button type="link" onClick={() => setEditing(row)}>{t("edit")}</Button>{effectiveCanWrite && row.username !== currentUser?.username && <DeleteAction name={row.displayName} actionLabel={t("deleteRecord")} confirmation={t("deleteUserConfirm", { name: row.displayName })} onDelete={async () => { await mutate(`/platform/users/${row.id}?version=${row.version}`, "DELETE", {}); await Promise.all([client.invalidateQueries({ queryKey: ["users"] }), client.invalidateQueries({ queryKey: ["online-users"] })]); }} />}</TableActions> }] : []),
  ]} /></>;
}
