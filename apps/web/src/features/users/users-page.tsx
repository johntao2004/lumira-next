"use client";
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Tag, Space, Spin, Tooltip, Typography } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api, isRolePreviewReadOnly, mutate } from "@/platform/api/client";
import type { CurrentUser } from "@/platform/auth/api-types";
import type { UserSummary } from "@/platform/directory/api-types";
import { ResourceTable } from "@/platform/components/resource-table";
import { DeleteAction } from "@/platform/components/delete-action";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import { useFragmentParams } from "@/platform/routing/use-tab-anchor";
import { QueryFailure } from "@/platform/components/query-failure";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { formatQuotaLimitBytes, formatQuotaLimitCount } from "@/platform/i18n/format";
import { UserExportDrawer, UserImportDrawer } from "./user-xlsx-drawers";
import { UserPrintDrawer } from "./user-print-drawer";

import { UserEditor } from "./user-editor";
import { UserDetails } from "./user-details";
import { ResetPasswordAction } from "./reset-password-action";
export function UsersPage({ canCreate, canUpdate, canDelete, canResetPassword, canAssignRoles, canReadRoles, canAssignOrganizations, canExport, canImport, canPrint, canUseTemplates, readOnlyPreview = false }: { canCreate:boolean; canUpdate: boolean; canDelete: boolean; canResetPassword: boolean; canAssignRoles: boolean; canReadRoles: boolean; canAssignOrganizations:boolean; canExport: boolean; canImport: boolean; canPrint: boolean; canUseTemplates: boolean; readOnlyPreview?: boolean }) {
  const [fragment, updateFragment] = useFragmentParams();
  const editorTarget = fragment.get("user");
  const previewReadOnly = readOnlyPreview || isRolePreviewReadOnly();
  const effectiveCanCreate = canCreate && !previewReadOnly;
  const effectiveCanUpdate = canUpdate && !previewReadOnly;
  const effectiveCanDelete = canDelete && !previewReadOnly;
  const effectiveCanResetPassword = canResetPassword && !previewReadOnly;
  const effectiveCanExport = canExport && !previewReadOnly;
  const effectiveCanImport = canImport && !previewReadOnly;
  const effectiveCanPrint = canPrint && !previewReadOnly;
  const effectiveCanUseTemplates = canUseTemplates && !previewReadOnly;
  const effectiveCanAssignRoles = canAssignRoles && !previewReadOnly;
  const effectiveCanReadRoles = (canReadRoles || effectiveCanAssignRoles) && !previewReadOnly;
  const effectiveCanAssignOrganizations = canAssignOrganizations && !previewReadOnly;
  const canEdit = effectiveCanUpdate || effectiveCanAssignRoles || effectiveCanReadRoles || effectiveCanAssignOrganizations;
  const canViewRolesOnly =
    effectiveCanReadRoles && !effectiveCanUpdate && !effectiveCanAssignRoles && !effectiveCanAssignOrganizations;
  const existingTarget = canEdit && editorTarget && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(editorTarget) ? editorTarget : null;
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const currentUser = client.getQueryData<CurrentUser>(["session"]);
  const [detailUser, setDetailUser] = useState<UserSummary | null>(null);
  const [selectedUserIds, setSelectedUserIds] = useState<string[]>([]);
  const [exportOpen, setExportOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [printOpen, setPrintOpen] = useState(false);
  const [printTargetUserIds, setPrintTargetUserIds] = useState<string[] | null>(null);
  const editorQuery = useQuery({
    queryKey: ["user", existingTarget],
    enabled: existingTarget !== null,
    queryFn: ({ signal }) => api<UserSummary>(`/platform/users/${existingTarget}`, { signal }),
  });
  const editing = editorTarget === "create" && effectiveCanCreate ? null : existingTarget ? editorQuery.data : undefined;
  const setEditing = (next: UserSummary | null | undefined) => updateFragment({ user: next === null ? "create" : next?.id ?? null });
  const [request, setRequest] = useListRequest();
  const changeRequest = (next: typeof request) => {
    setDetailUser(null);
    const criteriaChanged = JSON.stringify(request.filters ?? {}) !== JSON.stringify(next.filters ?? {})
      || JSON.stringify(request.filterModes ?? {}) !== JSON.stringify(next.filterModes ?? {})
      || request.search !== next.search;
    if (criteriaChanged) setSelectedUserIds([]);
    setRequest(next);
  };
  const query = useQuery({
    queryKey: ["users", request],
    queryFn: ({ signal }) => api<PageResult<UserSummary>>(`/platform/users?${listRequestParams(request)}`, { signal }),
  });
  useEffect(() => {
    if (previewReadOnly && editorTarget) updateFragment({ user: null });
  }, [previewReadOnly, editorTarget, updateFragment]);
  const currentPageIds = query.data?.items.map((user) => user.id) ?? [];
  return <>
    {existingTarget && !editorQuery.data && (editorQuery.error
      ? <QueryFailure title={t("unavailable")} pending={editorQuery.isFetching} onRetry={() => void editorQuery.refetch()} />
      : <Spin />)}
    <UserEditor key={editing?.id ?? (editing === null ? "create" : "closed")} user={editing} canCreate={effectiveCanCreate} canUpdate={effectiveCanUpdate} canAssignRoles={effectiveCanAssignRoles} canReadRoles={effectiveCanReadRoles} canAssignOrganizations={effectiveCanAssignOrganizations} canViewRolesOnly={canViewRolesOnly}
      onClose={() => setEditing(undefined)} onSaved={async () => {
        await Promise.all([
          query.refetch({ throwOnError: true }),
          client.invalidateQueries({ queryKey: ["online-users"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["session"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["user-assignments"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["user-assignment-role-options"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["user-file-quota-preview"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["files", "usage"] }, { throwOnError: true }),
        ]);
      }} />
    {query.data?.items.some(row => row.fileQuotaBytesOverride !== null || row.fileQuotaFilesOverride !== null)
      && <Alert className="mb-4" type="warning" showIcon message={t("userLegacyQuotaWarning")} />}
      <ResourceTable<UserSummary>
        actions={previewReadOnly || (!effectiveCanCreate && !effectiveCanExport && !effectiveCanImport && !effectiveCanPrint) ? null : <Space wrap>
          {effectiveCanCreate && <Button type="primary" onClick={() => setEditing(null)}>{t("createUser")}</Button>}
          {effectiveCanExport && <Button onClick={() => setExportOpen(true)}>{t("userExportXlsx")}</Button>}
          {effectiveCanImport && <Button onClick={() => setImportOpen(true)}>{t("userImportTitle")}</Button>}
          {effectiveCanPrint && <Button disabled={!selectedUserIds.length || !effectiveCanUseTemplates} onClick={() => { setPrintTargetUserIds([...selectedUserIds]); setPrintOpen(true); }}>{t("userGenerateDocuments")}</Button>}
        </Space>}
        rowSelection={effectiveCanPrint || effectiveCanExport ? {
          selectedRowKeys: selectedUserIds,
          preserveSelectedRowKeys: true,
          onChange: (keys) => setSelectedUserIds(keys.map(String)),
        } : undefined}
        result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={changeRequest} onRetry={() => { void query.refetch(); }} columns={[
    { title: t("serialNumber"), key: "serialNumber", width: 80, search: false, render: (_, __, index) => (request.page - 1) * request.pageSize + index + 1 },
    { title: t("username"), dataIndex: "username", search: true },
    { title: t("name"), dataIndex: "displayName", search: true },
    { title: t("status"), dataIndex: "enabled", search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
    { title: t("legacyQuotaRoleLabel"), search: false, render: (_, row) => row.fileQuotaBytesOverride !== null || row.fileQuotaFilesOverride !== null
      ? <Tooltip title={`${formatQuotaLimitBytes(row.fileQuotaBytesOverride,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})} · ${formatQuotaLimitCount(row.fileQuotaFilesOverride,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})}`}><Tag color="warning">{t("legacyQuotaRoleLabel")}</Tag></Tooltip>
      : <Typography.Text type="secondary">—</Typography.Text> },
    { title: t("actions"), key: "actions", search: false, render: (_: unknown, row: UserSummary) => <TableActions wrap><Button type="link" onClick={() => setDetailUser(row)}>{t("details")}</Button>{canEdit && <Button type="link" onClick={() => setEditing(row)}>{t(canViewRolesOnly ? "permissionReadRoles" : "edit")}</Button>}{effectiveCanResetPassword && row.username !== currentUser?.username && <ResetPasswordAction user={row} onStale={async () => { await query.refetch(); }} onReset={async () => { await Promise.all([query.refetch(), client.invalidateQueries({ queryKey: ["online-users"] })]); }} />}{effectiveCanDelete && row.username !== currentUser?.username && <DeleteAction name={row.displayName} actionLabel={t("deleteRecord")} confirmation={t("deleteUserConfirm", { name: row.displayName })} onDelete={async () => { await mutate(`/platform/users/${row.id}?version=${row.version}`, "DELETE", {}); setDetailUser(current => current?.id === row.id ? null : current); await Promise.all([client.invalidateQueries({ queryKey: ["users"] }), client.invalidateQueries({ queryKey: ["online-users"] })]); }} />}</TableActions> },
  ]} />
    <UserExportDrawer
      open={exportOpen && effectiveCanExport}
      onClose={() => setExportOpen(false)}
      request={request}
      currentPageIds={currentPageIds}
      selectedIds={selectedUserIds}
    />
    <UserImportDrawer
      open={importOpen && effectiveCanImport}
      onClose={() => setImportOpen(false)}
      onImported={async () => {
        await Promise.all([
          client.invalidateQueries({ queryKey: ["users"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["online-users"] }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["session"] }, { throwOnError: true }),
        ]);
      }}
    />
    <UserPrintDrawer
      open={printOpen && effectiveCanPrint}
      userIds={printTargetUserIds ?? selectedUserIds}
      canReadTemplate={effectiveCanUseTemplates}
      onClose={() => { setPrintOpen(false); setPrintTargetUserIds(null); }}
    />
    <PlatformDrawer
      open={detailUser !== null}
      placement="right"
      width={640}
      title={detailUser?.displayName || detailUser?.username || t("details")}
      onClose={() => setDetailUser(null)}
      footer={<Space wrap>
        <Button onClick={() => setDetailUser(null)}>{t("close")}</Button>
        {effectiveCanPrint && <Button type="primary" disabled={!effectiveCanUseTemplates || !detailUser} onClick={() => {
          if (!detailUser) return;
          setPrintTargetUserIds([detailUser.id]);
          setPrintOpen(true);
        }}>{t("userGenerateDocuments")}</Button>}
      </Space>}
    >
      {detailUser && <UserDetails key={detailUser.id} user={detailUser} />}
    </PlatformDrawer>
  </>;
}
