"use client";

import { Tabs, Tag } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, mutate } from "@/platform/api/client";
import { DeleteAction } from "@/platform/components/delete-action";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import { FileTrashActions } from "./file-trash-actions";
import { formatFileSizeMb } from "./file-size";
import { FileDownloadAction } from "./file-download-action";

type ManagedFile = {
  id: string;
  ownerId: string | null;
  ownerUsername: string | null;
  ownerDisplayName: string | null;
  originalName: string;
  state: "UPLOADING" | "READY" | "DELETING" | "TRASHED";
  sizeBytes: number | null;
  version: number;
  createdAt: string;
  trashedAt: string | null;
};

export function AdminFilesPage({ canWrite }: { canWrite: boolean }) {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const [tab, setTab] = useState("files");
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
  async function refresh() {
    await client.invalidateQueries({ queryKey: ["managed-files"] });
  }
  const filesTable = <ResourceTable<ManagedFile>
    result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest}
    onRetry={() => void query.refetch()}
    columns={[
      { title: t("fileName"), dataIndex: "originalName", search: true, render: (_: unknown, file: ManagedFile) => <span style={{ overflowWrap: "anywhere" }}>{file.originalName}</span> },
      { title: t("fileOwner"), dataIndex: "ownerUsername", search: true, render: (_: unknown, file: ManagedFile) => file.ownerUsername ? `${file.ownerDisplayName || file.ownerUsername} (${file.ownerUsername})` : "—" },
      { title: t("fileSize"), dataIndex: "sizeBytes", valueType: "digit", render: (_: unknown, file: ManagedFile) => formatFileSizeMb(file.sizeBytes, locale) },
      { title: t("status"), dataIndex: "state", search: true, valueEnum: { UPLOADING: { text: t("fileUploading") }, READY: { text: t("fileReady") }, DELETING: { text: t("fileDeleting") } }, render: (_: unknown, file: ManagedFile) => <Tag color={file.state === "READY" ? "success" : "processing"}>{t(file.state === "READY" ? "fileReady" : file.state === "UPLOADING" ? "fileUploading" : "fileDeleting")}</Tag> },
      { title: t("eventTime"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_: unknown, file: ManagedFile) => formatDateTime(file.createdAt, locale, { dateStyle: "medium", timeStyle: "short" }) },
      { title: t("actions"), search: false, render: (_: unknown, file: ManagedFile) => <TableActions>
        {file.state === "READY" && <FileDownloadAction href={`/api/platform/files/${file.id}/content`} label={t("downloadFile")} confirmation={t("fileDownloadConfirm", { name: file.originalName })} confirmText={t("downloadFile")} cancelText={t("cancel")} />}
        {canWrite && file.state === "READY" && <DeleteAction name={file.originalName} actionLabel={t("moveToTrash")} confirmation={t("moveToTrashConfirm", { name: file.originalName })} onDelete={async () => { try { await mutate(`/platform/files/${file.id}?version=${file.version}`, "DELETE"); } finally { await refresh(); } }} />}
      </TableActions> },
    ]}
  />;
  const trashTable = <ResourceTable<ManagedFile>
    result={trashQuery.data} loading={trashQuery.isFetching} error={trashQuery.error} request={trashRequest} onChange={setTrashRequest}
    onRetry={() => void trashQuery.refetch()}
    columns={[
      { title: t("fileName"), dataIndex: "originalName", search: true, render: (_: unknown, file: ManagedFile) => <span style={{ overflowWrap: "anywhere" }}>{file.originalName}</span> },
      { title: t("fileOwner"), dataIndex: "ownerUsername", search: true, render: (_: unknown, file: ManagedFile) => file.ownerUsername ? `${file.ownerDisplayName || file.ownerUsername} (${file.ownerUsername})` : "—" },
      { title: t("fileSize"), dataIndex: "sizeBytes", valueType: "digit", render: (_: unknown, file: ManagedFile) => formatFileSizeMb(file.sizeBytes, locale) },
      { title: t("fileDeletedAt"), dataIndex: "trashedAt", valueType: "dateTimeRange", render: (_: unknown, file: ManagedFile) => file.trashedAt ? formatDateTime(file.trashedAt, locale, { dateStyle: "medium", timeStyle: "short" }) : "—" },
      { title: t("actions"), search: false, render: (_: unknown, file: ManagedFile) => <TableActions>{canWrite && <FileTrashActions file={file} endpoint="/platform/files" onChanged={refresh} />}</TableActions> },
    ]}
  />;
  return <Tabs activeKey={tab} onChange={setTab} items={[
    { key: "files", label: t("filesTab"), children: filesTable },
    { key: "trash", label: t("fileTrash"), children: trashTable },
  ]} />;
}
