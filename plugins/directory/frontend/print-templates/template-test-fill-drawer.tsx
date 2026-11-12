"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Alert, App, Button, Input, Select, Space, Spin, Typography } from "antd";
import type { UserSummary } from "@/platform/directory/api-types";
import { api } from "@/platform/api/client";
import type { PageResult } from "@/platform/api/pagination";
import { AsyncButton } from "@/platform/components/async-button";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { downloadBlob } from "@/platform/files/download-blob";
import { messageKeyFor } from "@/platform/api/errors";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";
import { templatePreview } from "@/platform/templates/api";
import { userFieldLabel } from "@/platform/templates/field-labels";
import type { TemplateDetail, TemplateField, TemplateVersion } from "@/platform/templates/types";
import "./template-test-fill-drawer.css";

const previewErrorKeys = ["templateNotPublished", "forbidden", "fieldNotReadable", "unavailable"] as const;

export function TemplateTestFillDrawer({ template, versions, fields, open, canReadExampleUsers, canUpdate, onClose }: {
  template: TemplateDetail | null;
  versions: TemplateVersion[];
  fields: TemplateField[];
  open: boolean;
  canReadExampleUsers: boolean;
  canUpdate: boolean;
  onClose: () => void;
}) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const operationSignal = useSessionScope();
  const [userId, setUserId] = useState<string>();
  const [previewVersion, setPreviewVersion] = useState<number>();
  const [search, setSearch] = useState("");
  const [values, setValues] = useState<Record<string, string>>({});
  const [pdf, setPdf] = useState<Blob | null>(null);
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);
  const iframe = useRef<HTMLIFrameElement>(null);
  const { pending, error, setError, run } = useSubmitAction({ errorKeys: previewErrorKeys, fallback: "unavailable" });
  const clearError = useRef(setError);
  clearError.current = setError;
  const templateRef = useRef(template);
  templateRef.current = template;
  const userQuery = useQuery({
    queryKey: ["template-example-users", search],
    enabled: open && canReadExampleUsers,
    queryFn: ({ signal }) => {
      const query = new URLSearchParams({ page: "1", pageSize: "20", search });
      return api<PageResult<UserSummary>>(`/platform/users?${query}`, { signal });
    },
  });
  const preferredVersion = template
    ? (canUpdate && template.draftVersion !== null ? template.draftVersion : template.publishedVersion ?? template.draftVersion ?? undefined)
    : undefined;
  const effectivePreviewVersion = previewVersion ?? preferredVersion;
  const versionOptions = template ? [
    ...(template.draftVersion !== null ? [{ value: template.draftVersion, label: `${t("templateStatusDraft")} · ${t("templateVersion")} ${template.draftVersion}`, disabled: !canUpdate }] : []),
    ...(template.publishedVersion !== null ? [{ value: template.publishedVersion, label: `${t("templateStatusPublished")} · ${t("templateVersion")} ${template.publishedVersion}` }] : []),
  ] : [];

  const selectedVersionInfo = useMemo(() => versions.find((version) => version.version === effectivePreviewVersion)
    ?? (template && template.currentVersion?.version === effectivePreviewVersion ? template.currentVersion : null), [effectivePreviewVersion, template, versions]);
  const boundFields = useMemo(() => {
    if (!template) return [];
    const keys = new Set(Object.values(selectedVersionInfo?.bindings ?? {}));
    for (const placeholder of selectedVersionInfo?.placeholders ?? []) keys.add(placeholder);
    return fields.filter((field) => keys.has(field.key) && field.readable);
  }, [fields, selectedVersionInfo, template]);

  useEffect(() => {
    if (!pdf) {
      setPdfUrl(null);
      return;
    }
    const url = URL.createObjectURL(pdf);
    setPdfUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [pdf]);

  useEffect(() => {
    if (open) return;
    setUserId(undefined);
    setPreviewVersion(undefined);
    setSearch("");
    setValues({});
    setPdf(null);
    clearError.current(null);
  }, [open]);

  useEffect(() => {
    if (open) setPreviewVersion(preferredVersion);
  }, [open, template?.id, template?.draftVersion, template?.publishedVersion, preferredVersion]);

  const generate = async () => {
    if (!template || effectivePreviewVersion === undefined || pending) return;
    const signal = operationSignal();
    const currentTemplate = template;
    await run(async () => {
      const generated = await templatePreview(currentTemplate.id, {
        templateVersion: effectivePreviewVersion,
        ...(userId ? { userId } : {}),
        values,
      }, signal);
      if (signal.aborted || templateRef.current !== currentTemplate) return false;
      setPdf(generated);
      return true;
    });
  };

  const download = () => {
    if (!pdf || !template) return;
    downloadBlob(pdf, `${template.name || "template-preview"}.pdf`);
  };

  const print = () => {
    try {
      iframe.current?.contentWindow?.print();
    } catch {
      void message.error(t("templatePrintUnavailable"));
    }
  };

  return <PlatformDrawer
    open={open}
    title={t("templateTestFillTitle")}
    placement="right"
    width={1000}
    onClose={() => { if (!pending) onClose(); }}
    closable={{ disabled: pending }}
    keyboard={!pending}
    destroyOnHidden
    footer={<Space wrap>
      <Button disabled={pending} onClick={onClose}>{t("cancel")}</Button>
      {pdf && <>
        <Button onClick={download}>{t("templateDownloadPdf")}</Button>
        <Button onClick={print}>{t("templatePrintPdf")}</Button>
      </>}
      <AsyncButton type="primary" pending={pending} disabled={effectivePreviewVersion === undefined} label={t("templateGeneratePdf")} onClick={() => void generate()} />
    </Space>}
  >
    <div className="template-test-fill">
      <Typography.Paragraph type="secondary">{t("templatePreviewHint")}</Typography.Paragraph>
      {versionOptions.length > 1 && <div className="template-test-fill__user">
        <Typography.Text strong>{t("templateVersion")}</Typography.Text>
        <Select
          value={effectivePreviewVersion}
          disabled={pending}
          options={versionOptions}
          onChange={(value: number) => { setPreviewVersion(value); setValues({}); setPdf(null); }}
          style={{ width: "100%" }}
        />
      </div>}
      {canReadExampleUsers && <div className="template-test-fill__user">
        <Typography.Text strong>{t("templateExampleUser")}</Typography.Text>
        <Select
          allowClear
          showSearch
          filterOption={false}
          value={userId}
          placeholder={t("templateChooseExampleUser")}
          loading={userQuery.isFetching}
          disabled={pending}
          options={(userQuery.data?.items ?? []).map((user) => ({ value: user.id, label: `${user.displayName || user.username} (${user.username})` }))}
          onSearch={setSearch}
          onChange={(value) => { setUserId(value); setPdf(null); }}
          onDropdownVisibleChange={(visible) => { if (visible && userQuery.isError) void userQuery.refetch(); }}
          style={{ width: "100%" }}
        />
        {userQuery.isError && <Alert type="error" showIcon title={t("unavailable")} action={<Button type="link" onClick={() => void userQuery.refetch()}>{t("retry")}</Button>} />}
      </div>}
      {pending && <Alert type="info" showIcon title={t("userDocumentGenerationPending")} />}
      {boundFields.length > 0 && <div className="template-test-fill__values">
        <Typography.Text strong>{t("templateOverrideValues")}</Typography.Text>
        <Typography.Text type="secondary">{t("templateOverrideValuesHint")}</Typography.Text>
        {boundFields.map((field) => <label key={field.key} className="template-test-fill__field">
          <span>{userFieldLabel(field, t)}</span>
          <Input
            value={values[field.key] ?? ""}
            aria-label={userFieldLabel(field, t)}
            disabled={pending}
            onChange={(event) => {
              setValues((previous) => ({ ...previous, [field.key]: event.target.value }));
              setPdf(null);
            }}
          />
        </label>)}
      </div>}
      {error && <Alert type="error" showIcon title={t(error)} action={<Button type="link" onClick={() => void generate()}>{t("retry")}</Button>} />}
      {pdfUrl ? <iframe ref={iframe} className="template-test-fill__pdf" title={t("templatePdfPreview")} src={pdfUrl} />
        : <div className="template-test-fill__empty"><Spin spinning={pending} description={t("templatePreviewLoading")}><div className="template-test-fill__empty-placeholder">{t("templatePdfPreview")}</div></Spin></div>}
    </div>
  </PlatformDrawer>;
}
