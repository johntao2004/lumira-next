"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Alert, Button, Radio, Select, Space, Spin, Typography } from "antd";
import { AsyncButton } from "@/platform/components/async-button";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { downloadBlob } from "@/platform/files/download-blob";
import { usePreferences } from "@/platform/providers";
import { listTemplates, printUsers } from "@/platform/templates/api";
import type { TemplateSummary } from "@/platform/templates/types";
import "./user-print-drawer.css";

const printErrorKeys = ["templateNotPublished", "forbidden", "batchLimitExceeded", "fieldNotReadable", "unavailable"] as const;
const USER_PRINT_BATCH_LIMIT = 10;

export function UserPrintDrawer({ open, userIds, canReadTemplate, onClose }: {
  open: boolean;
  userIds: string[];
  canReadTemplate: boolean;
  onClose: () => void;
}) {
  const { t } = usePreferences();
  const operationSignal = useSessionScope();
  const [templateId, setTemplateId] = useState<string>();
  const [selectedTemplate, setSelectedTemplate] = useState<TemplateSummary | null>(null);
  const [templateSearch, setTemplateSearch] = useState("");
  const [format, setFormat] = useState<"pdf" | "zip">("pdf");
  const [file, setFile] = useState<Blob | null>(null);
  const [fileName, setFileName] = useState("users.pdf");
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);
  const iframe = useRef<HTMLIFrameElement>(null);
  const selectionKey = JSON.stringify([templateId, selectedTemplate?.publishedVersion, userIds, format]);
  const exceedsBatchLimit = userIds.length > USER_PRINT_BATCH_LIMIT;
  const selectionKeyRef = useRef(selectionKey);
  selectionKeyRef.current = selectionKey;
  const { pending, error, setError, run } = useSubmitAction({ errorKeys: printErrorKeys, fallback: "unavailable" });
  const clearError = useRef(setError);
  clearError.current = setError;
  const templatesQuery = useInfiniteQuery({
    queryKey: ["user-print-templates", templateSearch],
    enabled: open && canReadTemplate,
    initialPageParam: 1,
    queryFn: ({ signal, pageParam }) => listTemplates({ page: pageParam, pageSize: 30, search: templateSearch, status: "published" }, signal),
    getNextPageParam: (page) => page.page * page.pageSize < page.total ? page.page + 1 : undefined,
    retry: false,
  });
  const pageTemplates = useMemo(
    () => (templatesQuery.data?.pages.flatMap((page) => page.items) ?? []).filter((template) => template.status === "published" && template.publishedVersion !== null),
    [templatesQuery.data],
  );
  const templateOptions = useMemo(() => {
    const options = new Map<string, { value: string; label: string }>();
    if (selectedTemplate) options.set(selectedTemplate.id, { value: selectedTemplate.id, label: `${selectedTemplate.name} · ${t("templateVersion")} ${selectedTemplate.publishedVersion}` });
    for (const template of pageTemplates) options.set(template.id, { value: template.id, label: `${template.name} · ${t("templateVersion")} ${template.publishedVersion}` });
    return [...options.values()];
  }, [pageTemplates, selectedTemplate, t]);

  useEffect(() => {
    if (!file || format !== "pdf") {
      setPdfUrl(null);
      return;
    }
    const url = URL.createObjectURL(file);
    setPdfUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file, format]);

  useEffect(() => {
    if (open) return;
    setTemplateId(undefined);
    setSelectedTemplate(null);
    setTemplateSearch("");
    setFormat("pdf");
    setFile(null);
    clearError.current(null);
  }, [open]);

  const userIdsKey = userIds.join("\u0000");
  useEffect(() => {
    setFile(null);
  }, [templateId, userIdsKey]);

  const generate = async () => {
    if (!selectedTemplate || selectedTemplate.publishedVersion === null || userIds.length === 0 || exceedsBatchLimit || pending) return;
    const signal = operationSignal();
    const source = selectedTemplate;
    const targetIds = [...userIds];
    const requestSelectionKey = selectionKey;
    await run(async () => {
      const generated = await printUsers({
        templateId: source.id,
        templateVersion: source.publishedVersion!,
        userIds: targetIds,
        format: targetIds.length === 1 ? "pdf" : format,
        merge: targetIds.length > 1 && format === "pdf",
      }, signal);
      if (signal.aborted || requestSelectionKey !== selectionKeyRef.current) return false;
      const resolvedFormat = targetIds.length === 1 ? "pdf" : format;
      setFormat(resolvedFormat);
      setFileName(`${source.name}.${resolvedFormat === "zip" ? "zip" : "pdf"}`);
      setFile(generated);
      return true;
    });
  };

  const download = () => {
    if (file) downloadBlob(file, fileName);
  };

  return <PlatformDrawer
    open={open}
    title={t("userPrintTitle")}
    placement="right"
    width={960}
    onClose={() => { if (!pending) onClose(); }}
    closable={{ disabled: pending }}
    keyboard={!pending}
    destroyOnHidden
    footer={<Space wrap>
      <Button disabled={pending} onClick={onClose}>{t("cancel")}</Button>
      {file && <Button onClick={download}>{t("downloadFile")}</Button>}
      {file && format === "pdf" && <Button onClick={() => iframe.current?.contentWindow?.print()}>{t("templatePrintPdf")}</Button>}
      <AsyncButton type="primary" pending={pending} disabled={!selectedTemplate?.publishedVersion || !userIds.length || exceedsBatchLimit || !canReadTemplate} label={t("userGenerateDocuments")} onClick={() => void generate()} />
    </Space>}
  >
    <div className="user-print-drawer">
      <Alert type="info" showIcon title={t("userPrintSelectionHint", { count: userIds.length })} />
      {exceedsBatchLimit && <Alert type="warning" showIcon title={t("userPrintBatchLimit")} />}
      {!canReadTemplate && <Alert type="warning" showIcon title={t("templateUsePermissionRequired")} />}
      {templatesQuery.error && <Alert type="error" showIcon title={t("unavailable")} action={<Button type="link" onClick={() => void templatesQuery.refetch()}>{t("retry")}</Button>} />}
      {pending && <Alert type="info" showIcon title={t("userDocumentGenerationPending")} />}
      <div className="user-print-drawer__field">
        <Typography.Text strong>{t("printTemplates")}</Typography.Text>
        <Select
          showSearch
          filterOption={false}
          value={templateId}
          loading={templatesQuery.isFetching || templatesQuery.isFetchingNextPage}
          disabled={!canReadTemplate || pending}
          placeholder={t("templateSelectPublished")}
          options={templateOptions}
          onSearch={setTemplateSearch}
          onChange={(value) => {
            setTemplateId(value);
            setSelectedTemplate(pageTemplates.find((template) => template.id === value) ?? (selectedTemplate?.id === value ? selectedTemplate : null));
            setFile(null);
            setError(null);
          }}
          popupRender={(menu) => <>
            {menu}
            {templatesQuery.hasNextPage && <AsyncButton
              block
              pending={templatesQuery.isFetchingNextPage}
              disabled={templatesQuery.isFetchingNextPage}
              label={t("loadMore")}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => void templatesQuery.fetchNextPage()}
            />}
          </>}
          style={{ width: "100%" }}
        />
      </div>
      {userIds.length > 1 && <div className="user-print-drawer__field">
        <Typography.Text strong>{t("userDocumentFormat")}</Typography.Text>
        <Radio.Group value={format} disabled={pending} onChange={(event) => { setFormat(event.target.value); setFile(null); }}>
          <Radio value="pdf">{t("userMergedPdf")}</Radio>
          <Radio value="zip">{t("userSeparatePdfZip")}</Radio>
        </Radio.Group>
      </div>}
      {error && <Alert type="error" showIcon title={t(error)} action={<Button type="link" onClick={() => void generate()}>{t("retry")}</Button>} />}
      {file && format === "pdf" && pdfUrl ? <iframe className="user-print-drawer__pdf" ref={iframe} src={pdfUrl} title={t("templatePdfPreview")} />
        : file && format === "zip" ? <Alert type="success" showIcon title={t("userZipReady")} description={fileName} />
          : <div className="user-print-drawer__empty"><Spin spinning={templatesQuery.isFetching || pending} description={t("templatePreviewLoading")}><div>{t("templatePdfPreview")}</div></Spin></div>}
    </div>
  </PlatformDrawer>;
}
