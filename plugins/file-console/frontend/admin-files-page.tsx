"use client";

import { Tabs, Tag } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { lazy, useEffect, useState } from "react";
import { api, mutate } from "@/platform/api/client";
import { DeleteAction } from "@/platform/components/delete-action";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import { FileTrashActions } from "@/platform/files/file-trash-actions";
import { FileDownloadAction } from "@/platform/files/file-download-action";
import { FilePreviewDrawer } from "@/platform/files/file-preview-drawer";
import { FileNameCell } from "@/platform/files/file-name-cell";
import { fileCreatedAtColumn, fileDeletedAtColumn, fileNameColumn, fileSizeColumn } from "@/platform/files/file-table-columns";
import { useFileBatchActions } from "@/platform/files/use-file-batch-actions";
import { useTabAnchor } from "@/platform/routing/use-tab-anchor";

import { EmbeddedFrontendPage } from "@/platform/modules/embedded-page";
import type { FrontendPageContext } from "@/platform/modules/types";
const FileQuotaSettingsPage = lazy(() => import("./file-quota-settings").then(({ FileQuotaSettingsPage }) => ({ default: FileQuotaSettingsPage })));

type ManagedFile = {
  id: string;
  ownerId: string | null;
  ownerUsername: string | null;
  ownerDisplayName: string | null;
  originalName: string;
  state: "UPLOADING" | "READY" | "DELETING" | "TRASHED";
  protectedByReference: boolean;
  sizeBytes: number | null;
  version: number;
  createdAt: string;
  trashedAt: string | null;
};

export function AdminFilesPage({
  pageContext,
  canDelete,
  canRestore,
  canPurge,
  canPreview,
  canDownload,
  canReadRetention,
  canReadQuota,
  canUpdateQuota,
}: {
  pageContext: FrontendPageContext;
  canDelete: boolean;
  canRestore: boolean;
  canPurge: boolean;
  canPreview: boolean;
  canDownload: boolean;
  canReadRetention: boolean;
  canReadQuota: boolean;
  canUpdateQuota: boolean;
}) {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const tabKeys = ["files", "trash", ...(canReadRetention ? ["file-retention"] : []), ...(canReadQuota ? ["file-quota"] : [])];
  const [tab, setTab] = useTabAnchor("", tabKeys, "files", true, ["file-retention"]);
  const [previewFile, setPreviewFile] = useState<ManagedFile | null>(null);
  const [request, setRequest] = useListRequest();
  const [trashRequest, setTrashRequest] = useListRequest();
  const query = useQuery({
    queryKey: ["managed-files", request],
    queryFn: ({ signal }) => api<PageResult<ManagedFile>>(`/platform/files?${listRequestParams(request)}`, { signal }),
  });
  const trashQuery = useQuery({
    queryKey: ["managed-files", "trash", trashRequest],
    queryFn: ({ signal }) => api<PageResult<ManagedFile>>(`/platform/files/trash?${listRequestParams(trashRequest)}`, { signal }),
    enabled: tab === "trash",
  });
  const filesBatch = useFileBatchActions(query.data?.items, "files", refresh, { endpoint: "/platform/files", canDelete });
  const trashBatch = useFileBatchActions(trashQuery.data?.items, "trash", refresh, { endpoint: "/platform/files", canDelete: canPurge, canRestore });
  const batchPending = filesBatch.pending || trashBatch.pending;

  function retryFiles() {
    if (batchPending) return;
    filesBatch.reset();
    void query.refetch();
  }

  function retryTrash() {
    if (batchPending) return;
    trashBatch.reset();
    void trashQuery.refetch();
  }

  useEffect(() => { filesBatch.reset(); trashBatch.reset(); }, [tab]);
  useEffect(() => { if (!canPreview) setPreviewFile(null); }, [canPreview]);

  async function refresh() {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["managed-files"] }),
      client.invalidateQueries({ queryKey: ["files"] }),
    ]);
  }
  function preview(file: ManagedFile) {
    if (!canPreview || file.state !== "READY") return;
    setPreviewFile(file);
  }
  const filesTable = <>{filesBatch.feedback}<ResourceTable<ManagedFile>
    result={query.data} loading={query.isFetching || batchPending} error={query.error} request={request} onChange={next => { if (batchPending) return; filesBatch.reset(); setRequest(next); }}
    onRetry={retryFiles}
    rowSelection={canDelete ? filesBatch.rowSelection : undefined}
    actions={canDelete ? filesBatch.actions : null}
    columns={[
      fileNameColumn<ManagedFile>({ t, renderName: file => <FileNameCell
        name={file.originalName}
        previewable={canPreview && file.state === "READY"}
        onPreview={canPreview && file.state === "READY" ? () => preview(file) : undefined}
        suffix={file.protectedByReference ? <Tag>{t("fileProtectedByReference")}</Tag> : undefined}
      /> }),
      { title: t("fileOwner"), dataIndex: "ownerUsername", search: true, render: (_: unknown, file: ManagedFile) => file.ownerUsername ? `${file.ownerDisplayName || file.ownerUsername} (${file.ownerUsername})` : "—" },
      fileSizeColumn<ManagedFile>(t, locale),
      { title: t("status"), dataIndex: "state", search: true, valueEnum: { UPLOADING: { text: t("fileUploading") }, READY: { text: t("fileReady") }, DELETING: { text: t("fileDeleting") } }, render: (_: unknown, file: ManagedFile) => <Tag color={file.state === "READY" ? "success" : "processing"}>{t(file.state === "READY" ? "fileReady" : file.state === "UPLOADING" ? "fileUploading" : "fileDeleting")}</Tag> },
      fileCreatedAtColumn<ManagedFile>(t, locale),
      { title: t("actions"), key: "actions", search: false, render: (_: unknown, file: ManagedFile) => <TableActions>
        {file.state === "READY" && <FileDownloadAction fileName={file.originalName} href={`/api/platform/files/${file.id}/content`} label={t("downloadFile")} confirmation={t("fileDownloadConfirm", { name: file.originalName })} confirmText={t("downloadFile")} cancelText={t("cancel")} disabled={!canDownload} />}
        {canDelete && file.state === "READY" && <DeleteAction disabled={batchPending || file.protectedByReference} conflictErrorKey="fileMutationConflict" name={file.originalName} actionLabel={t("moveToTrash")} confirmation={t("moveToTrashConfirm", { name: file.originalName })} onDelete={async () => { try { await mutate(`/platform/files/${file.id}?version=${file.version}`, "DELETE"); } finally { await refresh(); } }} />}
      </TableActions> },
    ]}
  /></>;
  const trashTable = <>{trashBatch.feedback}<ResourceTable<ManagedFile>
    result={trashQuery.data} loading={trashQuery.isFetching || batchPending} error={trashQuery.error} request={trashRequest} onChange={next => { if (batchPending) return; trashBatch.reset(); setTrashRequest(next); }}
    onRetry={retryTrash}
    rowSelection={canPurge || canRestore ? trashBatch.rowSelection : undefined}
    actions={canPurge || canRestore ? trashBatch.actions : null}
    columns={[
      fileNameColumn<ManagedFile>({ t, renderName: file => <FileNameCell name={file.originalName} /> }),
      { title: t("fileOwner"), dataIndex: "ownerUsername", search: true, render: (_: unknown, file: ManagedFile) => file.ownerUsername ? `${file.ownerDisplayName || file.ownerUsername} (${file.ownerUsername})` : "—" },
      fileSizeColumn<ManagedFile>(t, locale),
      fileDeletedAtColumn<ManagedFile>(t, locale),
      { title: t("actions"), key: "actions", search: false, render: (_: unknown, file: ManagedFile) => <TableActions><FileTrashActions disabled={batchPending} canDelete={canPurge} canRestore={canRestore} file={file} endpoint="/platform/files" onChanged={refresh} /></TableActions> },
    ]}
  /></>;
  return <><Tabs activeKey={tab} onChange={next => { if (batchPending) return; filesBatch.reset(); trashBatch.reset(); setTab(next); }} items={[
    { key: "files", label: t("filesTab"), disabled: batchPending, children: filesTable },
    { key: "trash", label: t("fileTrash"), disabled: batchPending, children: trashTable },
    ...(canReadRetention ? [{ key: "file-retention", label: t("fileRetention"), disabled: batchPending, children: <EmbeddedFrontendPage routeId="file-retention" context={pageContext} /> }] : []),
    ...(canReadQuota ? [{ key: "file-quota", label: t("fileQuotaDefault"), disabled: batchPending, children: <FileQuotaSettingsPage canWrite={canUpdateQuota} /> }] : []),
  ]} /><FilePreviewDrawer file={canPreview ? previewFile : null} endpoint="/platform/files" canReadOriginal={canDownload} onClose={() => setPreviewFile(null)} /></>;
}
