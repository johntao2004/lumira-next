"use client";

import { Alert, Button, Image, Pagination, Spin, Typography } from "antd";
import { useEffect, useState, useSyncExternalStore } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, apiBlob, getAuthorizationContext, mutate, subscribeAuthorizationContext } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { currentSessionRevision, subscribeSessionBoundary } from "@/platform/auth/session-lifecycle";
import { FilePreviewMarkdown } from "./file-preview-markdown";
import { FilePreviewPdf } from "./file-preview-pdf";
import "./file-preview-drawer.css";

type PreviewFile = { id: string; originalName: string };
type FilePreview = { text: string; truncated: boolean; imageDataUrl: string | null; page: number; pageCount: number };
type ResourceTicket = { url: string; expiresAt: string };
const previewErrorKeys = ["filePreviewUnsupported", "filePreviewTooLarge", "filePreviewInvalidEncoding", "filePreviewInvalid"] as const satisfies readonly MessageKey[];

function subscribePreviewScope(listener: () => void) {
  const unsubscribeSession = subscribeSessionBoundary(listener);
  const unsubscribeAuthorization = subscribeAuthorizationContext(listener);
  return () => {
    unsubscribeSession();
    unsubscribeAuthorization();
  };
}

function getPreviewScope() {
  const context = getAuthorizationContext();
  return `${currentSessionRevision()}:${context?.revision ?? ""}:${context?.preview?.state ?? "NONE"}`;
}

/** Shared owner/admin preview; PDFs render the original file with PDF.js. */
export function FilePreviewDrawer({ file, endpoint = "/account/files", canReadOriginal = true, onClose }: {
  file: PreviewFile | null;
  endpoint?: "/account/files" | "/platform/files";
  canReadOriginal?: boolean;
  onClose: () => void;
}) {
  const { t } = usePreferences();
  const [page, setPage] = useState(1);
  const [pageCount, setPageCount] = useState(1);
  const scope = useSyncExternalStore(subscribePreviewScope, getPreviewScope, () => "server");
  const context = getAuthorizationContext();
  const rolePreviewActive = context?.preview?.state === "ACTIVE";
  const rolePreviewContext = Boolean(context?.preview);
  const extension = file?.originalName.split(".").pop()?.toLowerCase();
  const isPdf = extension === "pdf";

  const pdfQuery = useQuery({
    queryKey: ["file-preview-pdf", endpoint, file?.id, scope],
    queryFn: ({ signal }) => apiBlob(`${endpoint}/${file!.id}/content`, { signal })
      .then(blob => new Blob([blob], { type: "application/pdf" })),
    enabled: Boolean(file && isPdf && !rolePreviewContext && canReadOriginal),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });

  const query = useQuery({
    queryKey: ["file-preview", endpoint, file?.id, page, scope],
    queryFn: async ({ signal }) => {
      const path = `${endpoint}/${file!.id}/preview?page=${page}`;
      if (endpoint === "/account/files" && rolePreviewActive) {
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
    enabled: Boolean(file && !isPdf),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });

  const markdown = extension === "md" || extension === "markdown";
  const activeError = isPdf ? pdfQuery.error : query.error;
  const error = activeError ? t(messageKeyFor(activeError, previewErrorKeys, "filePreviewFailed")) : null;
  const loading = isPdf ? pdfQuery.isFetching : query.isFetching;
  const preview = isPdf ? undefined : query.data;
  useEffect(() => { setPage(1); setPageCount(1); }, [file?.id]);
  useEffect(() => { if (preview) setPageCount(preview.pageCount); }, [preview]);

  return <PlatformDrawer title={<Typography.Text style={{ minWidth: 0, overflowWrap: "anywhere" }}>{file?.originalName}</Typography.Text>} open={Boolean(file)} placement="right" onClose={onClose}
    size={900} destroyOnHidden footer={!isPdf && pageCount > 1 ? <div style={{ display: "flex", width: "100%", justifyContent: "center" }}><Pagination className="file-preview-pagination" simple
      current={page} pageSize={1} total={pageCount} showSizeChanger={false}
      disabled={query.isFetching} onChange={setPage} /></div> : null}>
    <Spin spinning={loading} description={t("filePreviewLoading")}>
      <div className={isPdf ? "file-preview-scroll file-preview-scroll--pdf" : "file-preview-scroll"}>
        {error && <Alert type="error" showIcon title={error} action={<Button type="link" onClick={() => void (isPdf ? pdfQuery.refetch() : query.refetch())}>{t("retry")}</Button>} />}
        {isPdf && rolePreviewContext && <Alert type="info" showIcon title={t("filePreviewPdfRolePreviewUnavailable")} />}
        {isPdf && !rolePreviewContext && !canReadOriginal && <Alert type="info" showIcon title={t("filePreviewOriginalPermissionRequired")} />}
        {isPdf && pdfQuery.data && !rolePreviewContext && canReadOriginal && <FilePreviewPdf
          key={`${file?.id}:${scope}:${pdfQuery.dataUpdatedAt}`}
          data={pdfQuery.data}
          title={file?.originalName ?? t("templatePdfPreview")}
          t={t}
          onRetry={() => { void pdfQuery.refetch(); }}
        />}
        {preview?.truncated && <Alert type="info" showIcon title={t("filePreviewTruncated")} style={{ marginBlockEnd: 12 }} />}
        {preview && (preview.imageDataUrl
          ? <div className="file-preview-image"><Image src={preview.imageDataUrl} alt={file?.originalName} /></div>
          : markdown ? <FilePreviewMarkdown>{preview.text}</FilePreviewMarkdown>
            : <pre className="file-preview-text">{preview.text}</pre>)}
      </div>
    </Spin>
  </PlatformDrawer>;
}
