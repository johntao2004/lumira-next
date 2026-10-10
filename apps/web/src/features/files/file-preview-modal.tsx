"use client";

import { Alert, Button, Image, Modal, Pagination, Spin } from "antd";
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, getAuthorizationContext, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { FilePreviewMarkdown } from "./file-preview-markdown";

type PreviewFile = { id: string; originalName: string };
type FilePreview = { text: string; truncated: boolean; imageDataUrl: string | null; page: number; pageCount: number };
type ResourceTicket = { url: string; expiresAt: string };
const previewErrorKeys = ["filePreviewUnsupported", "filePreviewTooLarge", "filePreviewInvalidEncoding", "filePreviewInvalid"] as const satisfies readonly MessageKey[];

/** Shared owner/admin preview; binary files arrive as locally decoded raster pixels. */
export function FilePreviewModal({ file, endpoint = "/account/files", onClose }: {
  file: PreviewFile | null;
  endpoint?: "/account/files" | "/platform/files";
  onClose: () => void;
}) {
  const { t } = usePreferences();
  const [page, setPage] = useState(1);
  const [pageCount, setPageCount] = useState(1);
  const query = useQuery({
    queryKey: ["file-preview", endpoint, file?.id, page],
    queryFn: async ({ signal }) => {
      const path = `${endpoint}/${file!.id}/preview?page=${page}`;
      const context = getAuthorizationContext();
      if (endpoint === "/account/files" && context?.preview?.state === "ACTIVE") {
        if (!context.revision) throw new Error("authorizationContextChanged");
        const revision = context.revision;
        const ticket = await mutate<ResourceTicket>(
          "/account/role-preview/resource-tickets",
          "POST",
          { path: `/api${path}`, authorizationContextRevision: revision },
          signal,
        );
        return api<FilePreview>(ticket.url, {
          signal,
          headers: { "X-Lumira-Authorization-Context-Revision": revision },
        });
      }
      return api<FilePreview>(path, { signal });
    },
    enabled: Boolean(file), retry: false, staleTime: 0, gcTime: 0,
  });
  const extension = file?.originalName.split(".").pop()?.toLowerCase();
  const markdown = extension === "md" || extension === "markdown";
  const error = query.error ? t(messageKeyFor(query.error, previewErrorKeys, "filePreviewFailed")) : null;
  const preview = query.data;
  useEffect(() => { if (preview) setPageCount(preview.pageCount); }, [preview]);

  return <Modal title={file?.originalName} open={Boolean(file)} onCancel={onClose} footer={null}
    width={900} className="file-preview-modal" destroyOnHidden
    styles={{ body: { maxHeight: "calc(100dvh - 190px)", overflow: "auto" } }}>
    <Spin spinning={query.isFetching} description={t("filePreviewLoading")}>
      <div className="file-preview-scroll">
        {error && <Alert type="error" showIcon title={error} action={<Button type="link" onClick={() => void query.refetch()}>{t("retry")}</Button>} />}
        {preview?.truncated && <Alert type="info" showIcon title={t("filePreviewTruncated")} style={{ marginBlockEnd: 12 }} />}
        {preview && (preview.imageDataUrl
          ? <div className="file-preview-image"><Image src={preview.imageDataUrl} alt={file?.originalName} /></div>
          : markdown ? <FilePreviewMarkdown>{preview.text}</FilePreviewMarkdown>
            : <pre className="file-preview-text">{preview.text}</pre>)}
      </div>
    </Spin>
    {pageCount > 1 && <Pagination className="file-preview-pagination" simple
      current={page} pageSize={1} total={pageCount} showSizeChanger={false}
      disabled={query.isFetching} onChange={setPage} />}
  </Modal>;
}
