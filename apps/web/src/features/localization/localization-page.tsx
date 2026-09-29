"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, App, Button, Checkbox, Input, Modal, Popconfirm, Radio, Select, Space, Spin, Tag, Typography } from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { AsyncButton } from "@/platform/components/async-button";
import { DeleteAction } from "@/platform/components/delete-action";
import { ResourceTable } from "@/platform/components/resource-table";
import { TableActions } from "@/platform/components/table-actions";
import { ApiError, api, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import { listRequestParams, type PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import { useListRequest } from "@/platform/routing/use-list-request";
import { languages, type MessageKey } from "@/platform/i18n/messages";

type LocaleView = { locale: string; displayName: string; sourceLocale: string; fallbackLocale: string; builtIn: boolean; enabled: boolean; releaseVersion: number; textCount: number };
type CatalogEntry = { id: string; messageKey: string; namespace: string; sourceMessage: string; currentValue: string; valueSource: string; overridden: boolean; builtIn: boolean; enabled: boolean; version: number; translations: Record<string, string> };
type ProviderOption = { id: string; name: string; modelName: string };
type Bundle = { formatVersion: number; locale: string; displayName: string; sourceLocale: string; fallbackLocale: string; releaseVersion?: number; effectiveTextCount?: number; messages: Record<string, string> };
type BundlePreview = { locale: string; newLocale: boolean; added: number; duplicates: number; overwrites: number; unknown: number; fallbacks: number; unknownKeys: string[] };
type AiPreview = { entry: CatalogEntry; source: string; translation?: string; errorCode?: string };
type EditorValues = { messageKey: string; defaultMessage: string; currentValue: string };

const errorKeys = ["forbidden", "invalidLocalization", "invalidLocalizationBundle", "localizationLocaleInvalid", "localizationNotFound", "duplicateLocalization", "staleRecord", "localizationAiUnavailable", "moduleUnavailable"] as const satisfies readonly MessageKey[];

function isPackage(value: unknown): value is Bundle {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const item = value as Record<string, unknown>;
  if (item.formatVersion !== 1 || typeof item.locale !== "string" || typeof item.displayName !== "string" ||
      typeof item.sourceLocale !== "string" || typeof item.fallbackLocale !== "string" ||
      !item.messages || typeof item.messages !== "object" || Array.isArray(item.messages)) return false;
  return Object.entries(item.messages as Record<string, unknown>).every(([key, text]) => key.length > 0 && typeof text === "string");
}
function sourceNeeded(row: CatalogEntry) {
  return !row.overridden && (row.valueSource === "FALLBACK" || row.valueSource === "MISSING");
}
function sourceState(row: CatalogEntry, t: (key: string) => string) {
  if (row.valueSource === "MISSING") return { label: t("localizationMissing"), color: "error" };
  if (row.valueSource === "OVERRIDE") return { label: t("localizationOverride"), color: "blue" };
  if (row.valueSource === "FALLBACK") return { label: t("localizationFallback"), color: "gold" };
  if (row.valueSource === "SOURCE") return { label: t("localizationSourceText"), color: "default" };
  return { label: t("localizationStaticValue"), color: "green" };
}

export function LocalizationPage({ canWrite, canImport }: { canWrite: boolean; canImport: boolean }) {
  const { t, locale: activeLocale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [locale, setSelectedLocale] = useState(activeLocale);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [editor, setEditor] = useState<CatalogEntry | null | undefined>(undefined);
  const [editorBusy, setEditorBusy] = useState(false);
  const [restoreBusyKey, setRestoreBusyKey] = useState<string | null>(null);
  const [editorForm] = Form.useForm<EditorValues>();
  const [bundleOpen, setBundleOpen] = useState(false);
  const [bundle, setBundle] = useState<Bundle | null>(null);
  const [bundleName, setBundleName] = useState("");
  const [bundlePreview, setBundlePreview] = useState<BundlePreview | null>(null);
  const [bundleOverwrite, setBundleOverwrite] = useState(false);
  const [ignoreUnknown, setIgnoreUnknown] = useState(false);
  const [bundleBusy, setBundleBusy] = useState(false);
  const [bundleError, setBundleError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [aiOpen, setAiOpen] = useState(false);
  const [aiSource, setAiSource] = useState("en");
  const [aiTarget, setAiTarget] = useState("zh-CN");
  const [aiScope, setAiScope] = useState<"page" | "selected">("page");
  const [onlyMissing, setOnlyMissing] = useState(true);
  const [overwriteManual, setOverwriteManual] = useState(false);
  const [providerId, setProviderId] = useState<string>();
  const [modelName, setModelName] = useState<string>();
  const [aiBusy, setAiBusy] = useState(false);
  const [aiProgress, setAiProgress] = useState({ done: 0, total: 0, failed: 0 });
  const [aiResults, setAiResults] = useState<Record<string, AiPreview>>({});
  const [aiSelected, setAiSelected] = useState<string[]>([]);
  const abortRef = useRef<AbortController | null>(null);

  const locales = useQuery({ queryKey: ["localization-locales"], queryFn: ({ signal }) => api<LocaleView[]>("/platform/localization/locales", { signal }) });
  const localeOptions = locales.data?.length
    ? locales.data.map((item) => ({ value: item.locale, label: item.displayName || item.locale }))
    : languages.map((item) => ({ value: item.value, label: item.label }));
  const listQuery = useQuery({
    queryKey: ["localization-catalog", locale, request],
    queryFn: ({ signal }) => {
      const params = listRequestParams(request, { locale });
      return api<PageResult<CatalogEntry>>(`/platform/localization/entries?${params}`, { signal });
    },
  });
  const providers = useQuery({ queryKey: ["localization-ai-providers"], enabled: canWrite, queryFn: ({ signal }) => api<ProviderOption[]>("/platform/localization/ai/providers", { signal }) });
  const selectedProvider = providers.data?.find((item) => item.id === providerId);

  useEffect(() => {
    if (!locales.data) return;
    if (!localeOptions.some((item) => item.value === locale)) setSelectedLocale(localeOptions[0].value);
  }, [locales.data, locale]);
  useEffect(() => { setSelectedLocale(activeLocale); }, [activeLocale]);
  useEffect(() => {
    if (providers.data?.length && !providers.data.some((item) => item.id === providerId)) {
      setProviderId(providers.data[0].id);
      setModelName(providers.data[0].modelName);
    }
  }, [providers.data, providerId]);

  const invalidate = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["localization-catalog"] }),
      client.invalidateQueries({ queryKey: ["localization-locales"] }),
      client.invalidateQueries({ queryKey: ["public-localization-locales"] }),
    ]);
    window.dispatchEvent(new Event("lumira:localization-changed"));
  };
  const openCreate = () => {
    editorForm.resetFields();
    editorForm.setFieldsValue({ messageKey: "custom.", defaultMessage: "", currentValue: "" });
    setEditor(null);
  };
  const openEdit = (row: CatalogEntry) => {
    editorForm.setFieldsValue({ messageKey: row.messageKey, defaultMessage: row.sourceMessage, currentValue: row.currentValue });
    setEditor(row);
  };
  const saveEditor = async (values: EditorValues) => {
    setEditorBusy(true);
    try {
      if (editor) {
        await mutate("/platform/localization/translations", "POST", {
          locale,
          overwriteExisting: true,
          entries: [{ messageKey: editor.messageKey, value: values.currentValue, version: editor.version }],
        });
      } else {
        await mutate("/platform/localization/entries", "POST", {
          messageKey: values.messageKey,
          namespace: "custom",
          defaultMessage: values.defaultMessage,
          translations: { [locale]: values.currentValue },
          enabled: true,
        });
      }
      setEditor(undefined);
      await invalidate();
      void message.success(t("localizationSaved"));
    } catch (error) {
      void message.error(t(messageKeyFor(error, errorKeys)));
      if (error instanceof ApiError && error.status === 409) await listQuery.refetch();
    } finally { setEditorBusy(false); }
  };
  const restoreDefault = async (row: CatalogEntry) => {
    if (!canWrite || !row.overridden || restoreBusyKey) return;
    setRestoreBusyKey(row.messageKey);
    const params = new URLSearchParams({ messageKey: row.messageKey, version: String(row.version) });
    try {
      await mutate(`/platform/localization/translations/${encodeURIComponent(locale)}?${params.toString()}`, "DELETE");
      await invalidate();
      void message.success(t("localizationDefaultRestored"));
    } catch (error) {
      void message.error(t(messageKeyFor(error, errorKeys)));
      if (error instanceof ApiError && error.status === 409) await listQuery.refetch();
    } finally { setRestoreBusyKey(null); }
  };

  const chooseBundle = async (file?: File) => {
    setBundle(null); setBundlePreview(null); setBundleError(null); setIgnoreUnknown(false);
    if (!file) return;
    setBundleName(file.name);
    if (file.size > 8_000_000) { setBundleError(t("invalidLocalizationBundle")); return; }
    try {
      const parsed: unknown = JSON.parse(await file.text());
      if (!isPackage(parsed) || Object.keys(parsed.messages).length > 20_000) throw new Error("invalidLocalizationBundle");
      setBundle(parsed);
    } catch { setBundleError(t("invalidLocalizationBundle")); }
  };
  const previewImport = async () => {
    if (!bundle) return;
    setBundleBusy(true); setBundleError(null);
    try {
      const result = await mutate<BundlePreview>("/platform/localization/bundle/preview", "POST", { bundle, overwriteExisting: bundleOverwrite });
      setBundlePreview(result);
    } catch (error) { setBundleError(t(messageKeyFor(error, errorKeys))); }
    finally { setBundleBusy(false); }
  };
  const confirmImport = async () => {
    if (!bundle || !bundlePreview || (bundlePreview.unknown > 0 && !ignoreUnknown)) return;
    setBundleBusy(true); setBundleError(null);
    try {
      const result = await mutate<{ locale: string }>("/platform/localization/bundle/import", "POST", {
        bundle, overwriteExisting: bundleOverwrite, ignoreUnknownKeys: ignoreUnknown,
      });
      setBundleOpen(false); setBundle(null); setBundlePreview(null);
      await invalidate();
      setSelectedLocale(result.locale);
      void message.success(t("localizationSaved"));
    } catch (error) { setBundleError(t(messageKeyFor(error, errorKeys))); }
    finally { setBundleBusy(false); }
  };
  const exportBundle = async () => {
    try {
      const result = await api<Bundle>(`/public/localization/bundle/${encodeURIComponent(locale)}`);
      const blob = new Blob([JSON.stringify(result, null, 2)], { type: "application/json;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a"); anchor.href = url; anchor.download = `${result.locale}.language-bundle.json`; anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) { void message.error(t(messageKeyFor(error, errorKeys))); }
  };

  const runAi = async (retryKeys?: string[]) => {
    if (!providerId || !modelName || aiBusy) return;
    const controller = new AbortController(); abortRef.current = controller; setAiBusy(true);
    if (!retryKeys) { setAiResults({}); setAiSelected([]); }
    try {
      const params = listRequestParams(request, { locale: aiTarget });
      const targetPage = await api<PageResult<CatalogEntry>>(`/platform/localization/entries?${params}`, { signal: controller.signal });
      const allRows = targetPage.items;
      const scopeRows = aiScope === "selected" && !retryKeys ? allRows.filter((row) => selectedKeys.includes(row.id)) : allRows;
      const chosen = retryKeys ? scopeRows.filter((row) => retryKeys.includes(row.messageKey)) : scopeRows;
      const enabledRows = chosen.filter((row) => row.enabled);
      const candidates = onlyMissing && !retryKeys ? enabledRows.filter(sourceNeeded) : enabledRows;
      if (!candidates.length) { void message.info(t("localizationNoSelection")); return; }
      const sourceBundle = await api<Bundle>(`/public/localization/bundle/${encodeURIComponent(aiSource)}`, { signal: controller.signal });
      const sourceMap = sourceBundle.messages ?? {};
      const work = candidates.flatMap((entry) => typeof sourceMap[entry.messageKey] === "string" ? [{ entry, source: sourceMap[entry.messageKey] }] : []);
      if (!work.length) { void message.error(t("invalidLocalization")); return; }
      setAiProgress({ done: 0, total: work.length, failed: 0 });
      const batches: typeof work[] = [];
      let batch: typeof work = []; let charCount = 0;
      for (const item of work) {
        const count = [...item.source].length;
        if (batch.length && (batch.length >= 5 || charCount + count > 20_000)) { batches.push(batch); batch = []; charCount = 0; }
        batch.push(item); charCount += count;
      }
      if (batch.length) batches.push(batch);
      let done = 0; let failed = 0;
      for (const part of batches) {
        if (controller.signal.aborted) break;
        let returned: { items?: Array<{ messageKey: string; translation?: string; errorCode?: string }> } | undefined;
        try {
          returned = await mutate("/platform/localization/ai/translate", "POST", {
            providerId, modelName, sourceLocale: aiSource, targetLocale: aiTarget,
            messageKeys: part.map((item) => item.entry.messageKey),
          }, controller.signal);
        } catch (error) {
          if (controller.signal.aborted) break;
          const code = messageKeyFor(error, errorKeys);
          returned = { items: part.map((item) => ({ messageKey: item.entry.messageKey, errorCode: code })) };
        }
        const results = new Map((returned?.items ?? []).map((item) => [item.messageKey, item]));
        const patch: Record<string, AiPreview> = {};
        for (const item of part) {
          const result = results.get(item.entry.messageKey);
          if (result?.translation) patch[item.entry.messageKey] = { ...item, translation: result.translation };
          else { patch[item.entry.messageKey] = { ...item, errorCode: result?.errorCode ?? "localizationAiItemError" }; failed++; }
          done++;
        }
        setAiResults((current) => ({ ...current, ...patch }));
        setAiSelected((current) => [...new Set([...current, ...Object.entries(patch).filter(([, result]) => result.translation && !result.entry.overridden).map(([key]) => key)])]);
        setAiProgress({ done, total: work.length, failed });
      }
    } catch (error) {
      if (!controller.signal.aborted) void message.error(t(messageKeyFor(error, errorKeys)));
    } finally { setAiBusy(false); abortRef.current = null; }
  };
  const saveAiSelected = async () => {
    const values = aiSelected.map((key) => aiResults[key]).filter((item): item is AiPreview & { translation: string } => Boolean(item?.translation));
    if (!values.length) return;
    setAiBusy(true);
    try {
      await mutate("/platform/localization/translations", "POST", {
        locale: aiTarget,
        overwriteExisting: overwriteManual,
        entries: values.map((item) => ({ messageKey: item.entry.messageKey, value: item.translation, version: item.entry.version })),
      });
      await invalidate(); setSelectedLocale(aiTarget);
      setAiResults({}); setAiSelected([]); setAiOpen(false);
      void message.success(t("localizationSaved"));
    } catch (error) { void message.error(t(messageKeyFor(error, errorKeys))); }
    finally { setAiBusy(false); }
  };
  const aiResultItems = Object.entries(aiResults);
  const failedKeys = aiResultItems.filter(([, item]) => !item.translation).map(([key]) => key);
  const missingOptions = localeOptions;

  return <>
    <ResourceTable<CatalogEntry>
      result={listQuery.data} loading={listQuery.isFetching} error={listQuery.error} request={request}
      onChange={(next) => { if (next.page !== request.page || next.search !== request.search) setSelectedKeys([]); setRequest(next); }}
      onRetry={() => void listQuery.refetch()}
      rowSelection={canWrite ? { selectedRowKeys: selectedKeys, onChange: (keys) => setSelectedKeys(keys.map(String)) } : undefined}
      actions={<Space wrap>
        {canWrite && <Button type="primary" onClick={openCreate}>{t("createLocalizationEntry")}</Button>}
        {canImport && <Button onClick={() => { setBundleOpen(true); setBundle(null); setBundlePreview(null); setBundleError(null); }}>{t("importLocalizationEntries")}</Button>}
        <Button onClick={() => void exportBundle()}>{t("localizationExportBundle")}</Button>
        <Select aria-label={t("localizationLocale")} value={locale} options={localeOptions} onChange={(value) => { setSelectedLocale(value); setSelectedKeys([]); setRequest({ ...request, page: 1 }); }} style={{ minWidth: 150, maxWidth: "100%" }} />
        {canWrite && <Button onClick={() => { setAiOpen(true); setAiTarget(locale); setAiResults({}); void providers.refetch(); }}>{t("localizationAiTranslate")}</Button>}
      </Space>}
      columns={[
        { title: t("localizationKey"), dataIndex: "messageKey", search: true, ellipsis: true },
        { title: t("localizationNamespace"), dataIndex: "namespace", ellipsis: true },
        { title: t("localizationSourceText"), dataIndex: "sourceMessage", width: 300, render: (_value, row) => <Typography.Paragraph style={{ maxWidth: 300, margin: 0, overflowWrap: "anywhere", whiteSpace: "pre-wrap" }}>{row.sourceMessage}</Typography.Paragraph> },
        { title: t("localizationEffectiveValue"), dataIndex: "currentValue", width: 300, render: (_value, row) => <Typography.Paragraph style={{ maxWidth: 300, margin: 0, overflowWrap: "anywhere", whiteSpace: "pre-wrap" }}>{row.currentValue || t("localizationMissing")}</Typography.Paragraph> },
        { title: t("localizationValueSource"), dataIndex: "valueSource", render: (_value, row) => { const state = sourceState(row, t); return <Tag color={state.color}>{state.label}</Tag>; } },
        { title: t("status"), dataIndex: "enabled", render: (_value, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
        { title: t("actions"), render: (_value, row) => <TableActions wrap>
          <Button type="link" onClick={() => openEdit(row)}>{t(canWrite ? "edit" : "details")}</Button>
          {canWrite && row.overridden && <Popconfirm
            title={t("localizationRestoreDefaultConfirm")}
            okText={t("localizationRestoreDefault")}
            cancelText={t("cancel")}
            okButtonProps={{ loading: restoreBusyKey === row.messageKey }}
            cancelButtonProps={{ disabled: Boolean(restoreBusyKey) }}
            onConfirm={() => restoreDefault(row)}
            disabled={Boolean(restoreBusyKey)}
          ><Button type="link" loading={restoreBusyKey === row.messageKey} disabled={Boolean(restoreBusyKey)}>{t("localizationRestoreDefault")}</Button></Popconfirm>}
          {canWrite && !row.builtIn && <AsyncButton type="link" label={t(row.enabled ? "disableLocalization" : "enableLocalization")} pending={editorBusy} onClick={async () => { try { await mutate(`/platform/localization/entries/${row.id}/status`, "PATCH", { enabled: !row.enabled, version: row.version }); await invalidate(); } catch (error) { void message.error(t(messageKeyFor(error, errorKeys))); } }} />}
          {canWrite && !row.builtIn && <DeleteAction name={row.messageKey} onDelete={async () => { await mutate(`/platform/localization/entries/${row.id}?version=${row.version}`, "DELETE"); await invalidate(); }} />}
        </TableActions> },
      ]}
    />

    <Modal open={editor !== undefined} title={t(editor ? "editLocalizationEntry" : "createLocalizationEntry")} onCancel={() => { if (!editorBusy) setEditor(undefined); }} footer={null} width={640} style={{ maxWidth: "calc(100vw - 24px)" }} styles={{ body: { maxHeight: "70vh", overflowY: "auto" } }} destroyOnHidden>
      {editor !== undefined && <Form id="localization-editor" form={editorForm} layout="vertical" disabled={editorBusy || !canWrite} onFinish={(values) => void saveEditor(values as EditorValues)}>
        <Form.Item name="messageKey" label={t("localizationKey")} rules={[{ required: true, pattern: editor ? /^[A-Za-z][A-Za-z0-9_.:-]{0,199}$/u : /^custom\.[A-Za-z0-9][A-Za-z0-9_.-]{0,193}$/u }]}><Input disabled={Boolean(editor)} maxLength={200} /></Form.Item>
        {editor === null && <Form.Item name="defaultMessage" label={t("localizationDefaultMessage")} rules={[{ required: true, max: 2000 }]}><Input.TextArea rows={4} maxLength={2000} /></Form.Item>}
        {editor === null && <Alert type="info" showIcon message={t("localizationCustomKeyHint")} />}
        {editor && <><Alert type="info" showIcon message={t("localizationEditTranslationHint")} /><Typography.Paragraph type="secondary" style={{ overflowWrap: "anywhere", marginTop: 12 }}>{editor.sourceMessage}</Typography.Paragraph></>}
        <Form.Item name="currentValue" label={t("localizationEffectiveValue")} rules={[{ required: true, whitespace: true, max: 2000 }]}><Input.TextArea rows={6} maxLength={2000} /></Form.Item>
        <Space wrap>
          <Button onClick={() => setEditor(undefined)} disabled={editorBusy}>{t("cancel")}</Button>
          <Button type="primary" htmlType="submit" form="localization-editor" loading={editorBusy} disabled={!canWrite}>{t("save")}</Button>
        </Space>
      </Form>}
    </Modal>

    <Modal open={bundleOpen} title={t("importLocalizationEntries")} onCancel={() => { if (!bundleBusy) setBundleOpen(false); }} width={720} style={{ maxWidth: "calc(100vw - 24px)" }} styles={{ body: { maxHeight: "70vh", overflowY: "auto" } }} footer={null} destroyOnHidden>
        <Space direction="vertical" size="middle" style={{ width: "100%" }}>
        <Typography.Text>{t("localizationBundleFile")}</Typography.Text>
        <input ref={fileInput} type="file" accept=".json,application/json" hidden onChange={(event) => { void chooseBundle(event.currentTarget.files?.[0]); event.currentTarget.value = ""; }} />
        <Space wrap><Button onClick={() => fileInput.current?.click()}>{t("localizationSelectBundleFile")}</Button><Typography.Text ellipsis>{bundleName}</Typography.Text></Space>
        <Typography.Text type="secondary">{t("localizationBundleFormat")}: v1 · {t("localizationSourceLocale")} · {t("localizationFallbackLocale")} · messages</Typography.Text>
        <Alert type="info" showIcon message={t("localizationUnknownKeyHint")} />
        <Radio.Group value={bundleOverwrite} onChange={(event) => { setBundleOverwrite(event.target.value); setBundlePreview(null); }}>
          <Space direction="vertical"><Radio value={false}>{t("localizationKeepExisting")}</Radio><Radio value>{t("localizationOverwriteExisting")}</Radio></Space>
        </Radio.Group>
        {bundleError && <Alert type="error" showIcon message={bundleError} />}
        {bundlePreview && <Alert type="info" showIcon message={t("localizationBundleStats", { added: bundlePreview.added, duplicates: bundlePreview.duplicates, overwrites: bundlePreview.overwrites, unknown: bundlePreview.unknown, fallbacks: bundlePreview.fallbacks })} description={<div style={{ overflowWrap: "anywhere" }}>{bundlePreview.unknown > 0 && <><Typography.Text strong>{t("localizationUnknownKeys")}</Typography.Text><div>{bundlePreview.unknownKeys.map((key) => <div key={key}>{key}</div>)}</div><Checkbox checked={ignoreUnknown} onChange={(event) => setIgnoreUnknown(event.target.checked)}>{t("localizationIgnoreUnknown")}</Checkbox></>}</div>} />}
        <Space wrap>
          <Button onClick={() => void previewImport()} disabled={!bundle || bundleBusy}>{t("localizationBundlePreview")}</Button>
          <AsyncButton type="primary" pending={bundleBusy} disabled={!bundlePreview || (bundlePreview.unknown > 0 && !ignoreUnknown)} label={t("localizationConfirmImport")} onClick={() => void confirmImport()} />
          <Button onClick={() => setBundleOpen(false)} disabled={bundleBusy}>{t("cancel")}</Button>
        </Space>
      </Space>
    </Modal>

    <Modal open={aiOpen} title={t("localizationAiTranslate")} onCancel={() => { if (!aiBusy) setAiOpen(false); }} width={760} style={{ maxWidth: "calc(100vw - 24px)" }} styles={{ body: { maxHeight: "72vh", overflowY: "auto" } }} footer={null} destroyOnHidden>
      <Space direction="vertical" size="middle" style={{ width: "100%" }}>
        {providers.data?.length === 0 && <Alert type="warning" showIcon message={t("localizationAiUnavailable")} />}
        {providers.error && <Alert type="error" showIcon message={t(messageKeyFor(providers.error, errorKeys))} />}
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(min(220px, 100%), 1fr))", gap: 12 }}>
          <label>{t("localizationSourceLocale")}<Select aria-label={t("localizationSourceLocale")} value={aiSource} options={missingOptions} onChange={setAiSource} style={{ width: "100%" }} /></label>
          <label>{t("localizationLocale")}<Select aria-label={t("localizationLocale")} value={aiTarget} options={missingOptions} onChange={setAiTarget} style={{ width: "100%" }} /></label>
          <label>{t("localizationAiProvider")}<Select aria-label={t("localizationAiProvider")} value={providerId} options={(providers.data ?? []).map((item) => ({ value: item.id, label: item.name }))} onChange={(id) => { setProviderId(id); setModelName(providers.data?.find((item) => item.id === id)?.modelName); }} style={{ width: "100%" }} /></label>
          <label>{t("localizationAiModel")}<Select aria-label={t("localizationAiModel")} value={modelName} options={selectedProvider ? [{ value: selectedProvider.modelName, label: selectedProvider.modelName }] : []} onChange={setModelName} style={{ width: "100%" }} /></label>
        </div>
        <Typography.Text>{t("localizationAiSelectScope")}</Typography.Text>
        <Radio.Group value={aiScope} onChange={(event) => setAiScope(event.target.value)}><Space wrap><Radio value="page">{t("localizationCurrentPage")}</Radio><Radio value="selected">{t("localizationSelectedKeys")} ({selectedKeys.length})</Radio></Space></Radio.Group>
        <Checkbox checked={onlyMissing} onChange={(event) => setOnlyMissing(event.target.checked)}>{t("localizationOnlyMissing")}</Checkbox>
        <Checkbox checked={overwriteManual} onChange={(event) => setOverwriteManual(event.target.checked)}>{t("localizationOverwriteManual")}</Checkbox>
        <Typography.Text type="secondary">{t("localizationBatchHint", { count: 5, chars: 20_000 })}</Typography.Text>
        <Space wrap>
          <AsyncButton type="primary" pending={aiBusy} disabled={!providers.data?.length || !providerId || !modelName || aiSource === aiTarget} label={t("localizationAiTranslate")} onClick={() => void runAi()} />
          {aiBusy && <Button onClick={() => abortRef.current?.abort()}>{t("localizationCancel")}</Button>}
          {!aiBusy && failedKeys.length > 0 && <Button onClick={() => void runAi(failedKeys)}>{t("localizationRetryFailed")}</Button>}
        </Space>
        {aiProgress.total > 0 && <Typography.Text>{t("localizationAiProgress", aiProgress)}</Typography.Text>}
        {aiBusy && <Spin size="small" />}
        {aiResultItems.length > 0 && <>
          <Typography.Title level={5}>{t("localizationAiPreview")}</Typography.Title>
          <Checkbox.Group value={aiSelected} onChange={(values) => setAiSelected(values.map(String))} style={{ width: "100%" }}>
            <Space direction="vertical" style={{ width: "100%" }}>
              {aiResultItems.map(([key, item]) => <div key={key} style={{ border: "1px solid #eee", borderRadius: 6, padding: 10, overflowWrap: "anywhere" }}>
                <Checkbox value={key} disabled={!item.translation}>{key}</Checkbox>
                <Typography.Paragraph type="secondary" style={{ margin: "4px 0", overflowWrap: "anywhere" }}>{item.source}</Typography.Paragraph>
                {item.translation ? <Typography.Paragraph style={{ marginBottom: 0, overflowWrap: "anywhere" }}>{item.translation}</Typography.Paragraph> : <Typography.Text type="danger">{t(item.errorCode ?? "localizationAiItemError")}</Typography.Text>}
              </div>)}
            </Space>
          </Checkbox.Group>
          <AsyncButton type="primary" pending={aiBusy} disabled={!aiSelected.length} label={t("localizationSaveSelected")} onClick={() => void saveAiSelected()} />
        </>}
      </Space>
    </Modal>
  </>;
}
