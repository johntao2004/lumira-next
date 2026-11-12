"use client";
import { FileUsage } from "./file-usage";
import { useEffect, useRef, useState } from "react";
import { App, Tabs } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { AsyncButton } from "@/platform/components/async-button";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, isRolePreviewReadOnly, mutate, upload } from "@/platform/api/client";
import { errorCode } from "@/platform/api/errors";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { DeleteAction } from "@/platform/components/delete-action";
import { usePreferences } from "@/platform/providers";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import { FileTrashActions } from "@/platform/files/file-trash-actions";
import { FileDownloadAction } from "@/platform/files/file-download-action";
import { FilePreviewDrawer } from "@/platform/files/file-preview-drawer";
import { FileNameCell } from "@/platform/files/file-name-cell";
import { fileCreatedAtColumn, fileDeletedAtColumn, fileIdentityColumns } from "@/platform/files/file-table-columns";
import { useFileCleanupTracker } from "./use-file-cleanup-tracker";
import { useFileBatchActions } from "@/platform/files/use-file-batch-actions";
import { useTabAnchor } from "@/platform/routing/use-tab-anchor";

type FileEntry = { id: string; originalName: string; sizeBytes: number | null; state: "UPLOADING" | "READY" | "DELETING"; version: number; createdAt: string };
type TrashedFile = Omit<FileEntry, "state"> & { state: "TRASHED"; trashedAt: string };


export function FilesPage() {
  const { t, locale } = usePreferences(), { message } = App.useApp(), client = useQueryClient();
  const previewActive = isRolePreviewReadOnly();
  const trackFileCleanup = useFileCleanupTracker();
  const input = useRef<HTMLInputElement>(null), busy = useRef(false);
  const [pending, setPending] = useState(false);
  const [tab, setTab] = useTabAnchor("", ["files", "trash"], "files");
  const [previewFile, setPreviewFile] = useState<FileEntry | null>(null);
  const [request, setRequest] = useListRequest();
  const [trashRequest, setTrashRequest] = useListRequest();
  const query = useQuery({ queryKey: ["files", request], queryFn: ({ signal }) => api<PageResult<FileEntry>>(`/account/files?${listRequestParams(request)}`, { signal }) });
  const trashQuery = useQuery({ queryKey: ["files", "trash", trashRequest], queryFn: ({ signal }) => api<PageResult<TrashedFile>>(`/account/files/trash?${listRequestParams(trashRequest)}`, { signal }), enabled: tab === "trash" && !previewActive });
  const filesBatch = useFileBatchActions(query.data?.items, "files", refresh, { onAccepted: refreshUsage });
  const trashBatch = useFileBatchActions(trashQuery.data?.items, "trash", refresh, { onCleanupStarted: trackFileCleanup, onAccepted: refreshUsage });
  const batchPending = filesBatch.pending || trashBatch.pending;

  useEffect(() => { filesBatch.reset(); trashBatch.reset(); }, [tab]);
  useEffect(() => {
    if (previewActive) setPreviewFile(null);
  }, [previewActive]);


  useEffect(() => {
    for (const file of query.data?.items ?? []) {
      if (file.state === "DELETING") trackFileCleanup(file.id);
    }
  }, [query.data, trackFileCleanup]);

  async function refresh() {
    await client.invalidateQueries({ queryKey: ["files"] });
  }

  async function refreshUsage() {
    await client.invalidateQueries({ queryKey: ["files", "usage"] });
  }

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

  const filesIdentityColumns = fileIdentityColumns<FileEntry>({
    t,
    locale,
    renderName: file => <FileNameCell name={file.originalName} previewable={file.state === "READY"} onPreview={() => setPreviewFile(file)} />,
  });
  const trashIdentityColumns = fileIdentityColumns<TrashedFile>({
    t,
    locale,
    renderName: file => <FileNameCell name={file.originalName} />,
  });

  async function send(file: File) {
    if (busy.current) return;
    if (file.size > 20 * 1024 * 1024) { void message.error(t("fileTooLarge")); return; }
    busy.current = true; setPending(true);
    try { await upload<FileEntry>(`/account/files?${new URLSearchParams({ filename: file.name })}`, file); setRequest({ page: 1, pageSize: request.pageSize, search: "" }); }
    catch (error) {
      const key = errorCode(error) === "sensitiveContent" ? "sensitiveContent" : error instanceof ApiError && error.status === 429 ? "uploadRateLimited" : error instanceof ApiError && error.status === 408 ? "fileUploadExpired" : error instanceof ApiError && error.status === 413 ? "fileTooLarge" : error instanceof ApiError && error.message === "fileQuotaExceeded" ? "fileQuotaExceeded" : "fileUploadUncertain";
      if (key === "fileUploadUncertain") setRequest({ page: 1, pageSize: request.pageSize, search: "" });
      void message.error(t(key));
    } finally { busy.current = false; setPending(false); await refresh(); }
  }

  const filesTable = <>{filesBatch.feedback}<ResourceTable<FileEntry> result={query.data} loading={query.isFetching || batchPending} error={query.error} request={request} onChange={next => { if (batchPending) return; filesBatch.reset(); setRequest(next); }} onRetry={retryFiles}
    rowSelection={previewActive ? undefined : filesBatch.rowSelection}
    actions={previewActive ? undefined : <>{filesBatch.actions}<input ref={input} type="file" hidden onChange={event => { const file = event.target.files?.[0]; event.target.value = ""; if (file) void send(file); }} /><AsyncButton type="primary" pending={pending} disabled={batchPending} onClick={() => input.current?.click()} label={t("uploadFile")} /></>}
    columns={[
      ...filesIdentityColumns,
      fileCreatedAtColumn<FileEntry>(t, locale),
      { title: t("actions"), key: "actions", search: false, render: (_, file) => <TableActions>{!previewActive && file.state === "READY" && <FileDownloadAction fileName={file.originalName} href={`/api/account/files/${file.id}/content`} label={t("downloadFile")} confirmation={t("fileDownloadConfirm", { name: file.originalName })} confirmText={t("downloadFile")} cancelText={t("cancel")} />}{!previewActive && file.state === "READY" && <DeleteAction disabled={batchPending} name={file.originalName} actionLabel={t("moveToTrash")} confirmation={t("moveToTrashConfirm", { name: file.originalName })} onDelete={async () => { try { await mutate(`/account/files/${file.id}?version=${file.version}`, "DELETE"); } finally { await refresh(); } }} />}</TableActions> },
    ]} /></>;

  const trashTable = <>{trashBatch.feedback}<ResourceTable<TrashedFile> result={trashQuery.data} loading={trashQuery.isFetching || batchPending} error={trashQuery.error} request={trashRequest} onChange={next => { if (batchPending) return; trashBatch.reset(); setTrashRequest(next); }} onRetry={retryTrash}
    rowSelection={trashBatch.rowSelection}
    actions={trashBatch.actions}
    columns={[
      ...trashIdentityColumns,
      fileDeletedAtColumn<TrashedFile>(t, locale),
      { title: t("actions"), key: "actions", search: false, render: (_, file) => <TableActions><FileTrashActions disabled={batchPending} file={file} endpoint="/account/files" onChanged={refresh} onCleanupStarted={trackFileCleanup} /></TableActions> },
    ]} /></>;

  return <><FileUsage /><Tabs activeKey={previewActive ? "files" : tab} onChange={next => { if (previewActive || batchPending) return; filesBatch.reset(); trashBatch.reset(); setTab(next); }} items={[
    { key: "files", label: t("filesTab"), disabled: batchPending, children: filesTable },
    ...(!previewActive ? [{ key: "trash", label: t("fileTrash"), disabled: batchPending, children: trashTable }] : []),
  ]} />
    <FilePreviewDrawer file={previewFile} onClose={() => setPreviewFile(null)} />
  </>;
}
