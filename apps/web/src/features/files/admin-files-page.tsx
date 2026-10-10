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
import { FileTrashActions } from "./file-trash-actions";
import { FileDownloadAction } from "./file-download-action";
import { FilePreviewModal } from "./file-preview-modal";
import { FileNameCell } from "./file-name-cell";
import { fileCreatedAtColumn, fileDeletedAtColumn, fileNameColumn, fileSizeColumn } from "./file-table-columns";
import { useFileBatchActions } from "./use-file-batch-actions";
import { useTabAnchor } from "@/platform/routing/use-tab-anchor";

const FileRetentionPage = lazy(() => import("@/features/settings/file-retention-page").then(({ FileRetentionPage }) => ({ default: FileRetentionPage })));

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
  canWrite,
  canReadRetention,
  canWriteRetention,
}: {
  canWrite: boolean;
  canReadRetention: boolean;
  canWriteRetention: boolean;
}) {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const tabKeys = canReadRetention ? ["files", "trash", "file-retention"] as const : ["files", "trash"] as const;
  const [tab, setTab] = useTabAnchor("", tabKeys, "files");
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
  const filesBatch = useFileBatchActions(query.data?.items, "files", refresh, { endpoint: "/platform/files" });
  const trashBatch = useFileBatchActions(trashQuery.data?.items, "trash", refresh, { endpoint: "/platform/files" });
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

  async function refresh() {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["managed-files"] }),
      client.invalidateQueries({ queryKey: ["files"] }),
    ]);
  }
  const filesTable = <>{filesBatch.feedback}<ResourceTable<ManagedFile>
    result={query.data} loading={query.isFetching || batchPending} error={query.error} request={request} onChange={next => { if (batchPending) return; filesBatch.reset(); setRequest(next); }}
    onRetry={retryFiles}
    rowSelection={canWrite ? filesBatch.rowSelection : undefined}
    actions={canWrite ? filesBatch.actions : null}
    columns={[
      fileNameColumn<ManagedFile>({ t, renderName: file => <FileNameCell
        name={file.originalName}
        previewable={file.state === "READY"}
        onPreview={() => setPreviewFile(file)}
        suffix={file.protectedByReference ? <Tag>{t("fileProtectedByReference")}</Tag> : undefined}
      /> }),
      { title: t("fileOwner"), dataIndex: "ownerUsername", search: true, render: (_: unknown, file: ManagedFile) => file.ownerUsername ? `${file.ownerDisplayName || file.ownerUsername} (${file.ownerUsername})` : "—" },
      fileSizeColumn<ManagedFile>(t, locale),
      { title: t("status"), dataIndex: "state", search: true, valueEnum: { UPLOADING: { text: t("fileUploading") }, READY: { text: t("fileReady") }, DELETING: { text: t("fileDeleting") } }, render: (_: unknown, file: ManagedFile) => <Tag color={file.state === "READY" ? "success" : "processing"}>{t(file.state === "READY" ? "fileReady" : file.state === "UPLOADING" ? "fileUploading" : "fileDeleting")}</Tag> },
      fileCreatedAtColumn<ManagedFile>(t, locale),
      { title: t("actions"), key: "actions", search: false, render: (_: unknown, file: ManagedFile) => <TableActions>
        {file.state === "READY" && <FileDownloadAction fileName={file.originalName} href={`/api/platform/files/${file.id}/content`} label={t("downloadFile")} confirmation={t("fileDownloadConfirm", { name: file.originalName })} confirmText={t("downloadFile")} cancelText={t("cancel")} />}
        {canWrite && file.state === "READY" && <DeleteAction disabled={batchPending || file.protectedByReference} conflictErrorKey="fileMutationConflict" name={file.originalName} actionLabel={t("moveToTrash")} confirmation={t("moveToTrashConfirm", { name: file.originalName })} onDelete={async () => { try { await mutate(`/platform/files/${file.id}?version=${file.version}`, "DELETE"); } finally { await refresh(); } }} />}
      </TableActions> },
    ]}
  /></>;
  const trashTable = <>{trashBatch.feedback}<ResourceTable<ManagedFile>
    result={trashQuery.data} loading={trashQuery.isFetching || batchPending} error={trashQuery.error} request={trashRequest} onChange={next => { if (batchPending) return; trashBatch.reset(); setTrashRequest(next); }}
    onRetry={retryTrash}
    rowSelection={canWrite ? trashBatch.rowSelection : undefined}
    actions={canWrite ? trashBatch.actions : null}
    columns={[
      fileNameColumn<ManagedFile>({ t, renderName: file => <FileNameCell name={file.originalName} /> }),
      { title: t("fileOwner"), dataIndex: "ownerUsername", search: true, render: (_: unknown, file: ManagedFile) => file.ownerUsername ? `${file.ownerDisplayName || file.ownerUsername} (${file.ownerUsername})` : "—" },
      fileSizeColumn<ManagedFile>(t, locale),
      fileDeletedAtColumn<ManagedFile>(t, locale),
      { title: t("actions"), key: "actions", search: false, render: (_: unknown, file: ManagedFile) => <TableActions>{canWrite && <FileTrashActions disabled={batchPending} file={file} endpoint="/platform/files" onChanged={refresh} />}</TableActions> },
    ]}
  /></>;
  return <><Tabs activeKey={tab} onChange={next => { if (batchPending) return; filesBatch.reset(); trashBatch.reset(); setTab(next); }} items={[
    { key: "files", label: t("filesTab"), disabled: batchPending, children: filesTable },
    { key: "trash", label: t("fileTrash"), disabled: batchPending, children: trashTable },
    ...(canReadRetention ? [{ key: "file-retention", label: t("fileRetention"), disabled: batchPending, children: <FileRetentionPage canWrite={canWriteRetention} /> }] : []),
  ]} /><FilePreviewModal key={previewFile?.id ?? "closed"} file={previewFile} endpoint="/platform/files" onClose={() => setPreviewFile(null)} /></>;
}
