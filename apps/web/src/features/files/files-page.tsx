"use client";
import { FileUsage } from "./file-usage";
import { useEffect, useRef, useState } from "react";
import { Alert, App, Button, Modal, Spin, Tabs } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { AsyncButton } from "@/platform/components/async-button";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, mutate, upload } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import type { MessageKey } from "@/platform/i18n/messages";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { DeleteAction } from "@/platform/components/delete-action";
import { usePreferences } from "@/platform/providers";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import { FileTrashActions } from "./file-trash-actions";
import { formatFileSizeMb } from "./file-size";
import { FileDownloadAction } from "./file-download-action";
import { FilePreviewMarkdown } from "./file-preview-markdown";
import { useFileCleanupTracker } from "./use-file-cleanup-tracker";
import { useFileBatchActions } from "./use-file-batch-actions";

type FileEntry = { id: string; originalName: string; sizeBytes: number | null; state: "UPLOADING" | "READY" | "DELETING"; version: number; createdAt: string };
type TrashedFile = Omit<FileEntry, "state"> & { state: "TRASHED"; trashedAt: string };
type FilePreview = { text: string; truncated: boolean };
const previewErrorKeys = ["filePreviewUnsupported", "filePreviewTooLarge", "filePreviewInvalidEncoding", "filePreviewInvalid"] as const satisfies readonly MessageKey[];

export function FilesPage() {
  const { t, locale } = usePreferences(), { message } = App.useApp(), client = useQueryClient();
  const trackFileCleanup = useFileCleanupTracker();
  const input = useRef<HTMLInputElement>(null), busy = useRef(false);
  const [pending, setPending] = useState(false), [tab, setTab] = useState("files");
  const [previewFile, setPreviewFile] = useState<FileEntry | null>(null);
  const [request, setRequest] = useListRequest();
  const [trashRequest, setTrashRequest] = useListRequest();
  const query = useQuery({ queryKey: ["files", request], queryFn: ({ signal }) => api<PageResult<FileEntry>>(`/account/files?${listRequestParams(request)}`, { signal }) });
  const trashQuery = useQuery({ queryKey: ["files", "trash", trashRequest], queryFn: ({ signal }) => api<PageResult<TrashedFile>>(`/account/files/trash?${listRequestParams(trashRequest)}`, { signal }), enabled: tab === "trash" });
  const filesBatch = useFileBatchActions(query.data?.items, "trash", refresh);
  const trashBatch = useFileBatchActions(trashQuery.data?.items, "restore", refresh);
  const batchPending = filesBatch.pending || trashBatch.pending;
  const previewQuery = useQuery({
    queryKey: ["file-preview", previewFile?.id],
    queryFn: ({ signal }) => api<FilePreview>(`/account/files/${previewFile!.id}/preview`, { signal }),
    enabled: Boolean(previewFile),
    retry: false,
    staleTime: 0,
  });

  useEffect(() => {
    for (const file of query.data?.items ?? []) {
      if (file.state === "DELETING") trackFileCleanup(file.id);
    }
  }, [query.data, trackFileCleanup]);

  async function refresh() {
    await client.invalidateQueries({ queryKey: ["files"] });
  }

  async function send(file: File) {
    if (busy.current) return;
    if (file.size > 20 * 1024 * 1024) { void message.error(t("fileTooLarge")); return; }
    busy.current = true; setPending(true);
    try { await upload<FileEntry>(`/account/files?${new URLSearchParams({ filename: file.name })}`, file); setRequest({ page: 1, pageSize: request.pageSize, search: "" }); }
    catch (error) {
      const key = error instanceof ApiError && error.status === 429 ? "uploadRateLimited" : error instanceof ApiError && error.status === 408 ? "fileUploadExpired" : error instanceof ApiError && error.status === 413 ? "fileTooLarge" : error instanceof ApiError && error.message === "fileQuotaExceeded" ? "fileQuotaExceeded" : "fileUploadUncertain";
      if (key === "fileUploadUncertain") setRequest({ page: 1, pageSize: request.pageSize, search: "" });
      void message.error(t(key));
    } finally { busy.current = false; setPending(false); await refresh(); }
  }

  const filesTable = <>{filesBatch.feedback}<ResourceTable<FileEntry> result={query.data} loading={query.isFetching || batchPending} error={query.error} request={request} onChange={next => { if (batchPending) return; filesBatch.reset(); setRequest(next); }} onRetry={() => void query.refetch()}
    rowSelection={filesBatch.rowSelection}
    actions={<>{filesBatch.actions}<input ref={input} type="file" hidden onChange={event => { const file = event.target.files?.[0]; event.target.value = ""; if (file) void send(file); }} /><AsyncButton type="primary" pending={pending} disabled={batchPending} onClick={() => input.current?.click()} label={t("uploadFile")} /></>}
    columns={[
      { title: t("fileName"), dataIndex: "originalName", search: true, render: (name, file) => file.state === "READY"
        ? <Button type="link" className="file-preview-link" style={{ whiteSpace: "normal", height: "auto", paddingInline: 0, textAlign: "left", overflowWrap: "anywhere" }} onClick={() => setPreviewFile(file)}>{name}</Button>
        : <span style={{ overflowWrap: "anywhere" }}>{name}</span> },
      { title: t("fileSize"), dataIndex: "sizeBytes", valueType: "digit", render: (_, file) => formatFileSizeMb(file.sizeBytes, locale) },
      { title: t("eventTime"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_, file) => formatDateTime(file.createdAt, locale, { dateStyle: "medium", timeStyle: "short" }) },
      { title: t("actions"), search: false, render: (_, file) => <TableActions>{file.state === "READY" && <FileDownloadAction href={`/api/account/files/${file.id}/content`} label={t("downloadFile")} confirmation={t("fileDownloadConfirm", { name: file.originalName })} confirmText={t("downloadFile")} cancelText={t("cancel")} />}{file.state === "READY" && <DeleteAction disabled={batchPending} name={file.originalName} actionLabel={t("moveToTrash")} confirmation={t("moveToTrashConfirm", { name: file.originalName })} onDelete={async () => { try { await mutate(`/account/files/${file.id}?version=${file.version}`, "DELETE"); } finally { await refresh(); } }} />}</TableActions> },
    ]} /></>;

  const trashTable = <>{trashBatch.feedback}<ResourceTable<TrashedFile> result={trashQuery.data} loading={trashQuery.isFetching || batchPending} error={trashQuery.error} request={trashRequest} onChange={next => { if (batchPending) return; trashBatch.reset(); setTrashRequest(next); }} onRetry={() => void trashQuery.refetch()}
    rowSelection={trashBatch.rowSelection}
    actions={trashBatch.actions}
    columns={[
      { title: t("fileName"), dataIndex: "originalName", search: true, render: name => <span style={{ overflowWrap: "anywhere" }}>{name}</span> },
      { title: t("fileSize"), dataIndex: "sizeBytes", valueType: "digit", render: (_, file) => formatFileSizeMb(file.sizeBytes, locale) },
      { title: t("fileDeletedAt"), dataIndex: "trashedAt", valueType: "dateTimeRange", render: (_, file) => formatDateTime(file.trashedAt, locale, { dateStyle: "medium", timeStyle: "short" }) },
      { title: t("actions"), search: false, render: (_, file) => <TableActions><FileTrashActions disabled={batchPending} file={file} endpoint="/account/files" onChanged={refresh} onCleanupStarted={trackFileCleanup} /></TableActions> },
    ]} /></>;

  const extension = previewFile?.originalName.split(".").pop()?.toLowerCase();
  const isMarkdown = extension === "md" || extension === "markdown";
  const previewError = previewQuery.error ? t(messageKeyFor(previewQuery.error, previewErrorKeys, "filePreviewFailed")) : null;

  return <><FileUsage /><Tabs activeKey={tab} onChange={next => { if (batchPending) return; filesBatch.reset(); trashBatch.reset(); setTab(next); }} items={[
    { key: "files", label: t("filesTab"), disabled: batchPending, children: filesTable },
    { key: "trash", label: t("fileTrash"), disabled: batchPending, children: trashTable },
  ]} />
    <Modal
      title={previewFile?.originalName}
      open={Boolean(previewFile)}
      onCancel={() => setPreviewFile(null)}
      footer={null}
      width={900}
      className="file-preview-modal"
      destroyOnHidden
      styles={{ body: { maxHeight: "calc(100vh - 190px)", overflow: "auto" } }}
    >
      <Spin spinning={previewQuery.isFetching} tip={t("filePreviewLoading")}>
        <div className="file-preview-scroll">
          {previewError && <Alert type="error" showIcon message={previewError} action={<Button type="link" onClick={() => void previewQuery.refetch()}>{t("retry")}</Button>} />}
          {previewQuery.data?.truncated && <Alert type="info" showIcon message={t("filePreviewTruncated")} style={{ marginBlockEnd: 12 }} />}
          {previewQuery.data && (isMarkdown
            ? <FilePreviewMarkdown>{previewQuery.data.text}</FilePreviewMarkdown>
            : <pre className="file-preview-text">{previewQuery.data.text}</pre>)}
        </div>
      </Spin>
    </Modal>
  </>;
}
