"use client";

import { useSubmitAction } from "@/platform/forms/use-submit-action";
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
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { languages, type MessageKey } from "@/platform/i18n/messages";
import { loadTranslationCandidates, type TranslationScope } from "./localization-ai-scope";
import { isCurrentLocalizationBundlePreview, mergeLocalizationAiResults, sameLocalizationAiScope, type LocalizationAiPreviewScope, type LocalizationBundlePreviewScope } from "./localization-preview-state";

type LocaleView = { locale: string; displayName: string; sourceLocale: string; fallbackLocale: string; builtIn: boolean; enabled: boolean; releaseVersion: number; textCount: number };
type CatalogEntry = { id: string; messageKey: string; namespace: string; sourceMessage: string; currentValue: string; valueSource: string; overridden: boolean; builtIn: boolean; enabled: boolean; version: number; translations: Record<string, string> };
type ProviderOption = { id: string; name: string; modelName: string; version?: number };
type Bundle = { formatVersion: number; locale: string; displayName: string; sourceLocale: string; fallbackLocale: string; releaseVersion?: number; snapshotToken?: string; effectiveTextCount?: number; messages: Record<string, string> };
type BundlePreview = { locale: string; newLocale: boolean; added: number; duplicates: number; overwrites: number; unknown: number; fallbacks: number; unknownKeys: string[] };
type AiPreview = { entry: CatalogEntry; source: string; translation?: string; errorCode?: string; generation: number };
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
function sourceState(row: CatalogEntry, t: (key: string) => string) {
  if (row.valueSource === "MISSING") return { label: t("localizationMissing"), color: "error" };
  if (row.valueSource === "OVERRIDE") return { label: t("localizationOverride"), color: "blue" };
  if (row.valueSource === "FALLBACK") return { label: t("localizationFallback"), color: "gold" };
  if (row.valueSource === "SOURCE") return { label: t("localizationSourceText"), color: "default" };
  return { label: t("localizationStaticValue"), color: "green" };
}

export function LocalizationPage({ canRead, canCreate, canUpdate, canDelete, canImport, canPublish }: { canRead: boolean; canCreate: boolean; canUpdate: boolean; canDelete: boolean; canImport: boolean; canPublish: boolean }) {
  const { t, locale: activeLocale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const sessionSignal = useSessionScope();
  const [request, setRequest] = useListRequest();
  const [locale, setSelectedLocale] = useState(activeLocale);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [editor, setEditor] = useState<CatalogEntry | null | undefined>(undefined);
  const [restoreBusyKey, setRestoreBusyKey] = useState<string | null>(null);
  const [editorForm] = Form.useForm<EditorValues>();
  const [bundleOpen, setBundleOpen] = useState(false);
  const [bundle, setBundle] = useState<Bundle | null>(null);
  const [bundleName, setBundleName] = useState("");
  const [bundlePreviewSnapshotValue, setBundlePreviewSnapshotValue] = useState<LocalizationBundlePreviewScope<Bundle, BundlePreview> | null>(null);
  const [bundleOverwrite, setBundleOverwrite] = useState(false);
  const [ignoreUnknown, setIgnoreUnknown] = useState(false);
  const [bundleError, setBundleError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [aiOpen, setAiOpen] = useState(false);
  const [aiSource, setAiSource] = useState("en");
  const [aiTarget, setAiTarget] = useState("zh-CN");
  const [aiScope, setAiScope] = useState<TranslationScope>("page");
  const [onlyMissing, setOnlyMissing] = useState(true);
  const [overwriteManual, setOverwriteManual] = useState(false);
  const [providerId, setProviderId] = useState<string>();
  const [modelName, setModelName] = useState<string>();
  const [aiBusy, setAiBusy] = useState(false);
  const [aiProgress, setAiProgress] = useState({ done: 0, total: 0, failed: 0 });
  const [aiResults, setAiResults] = useState<Record<string, AiPreview>>({});
  const [aiSelected, setAiSelected] = useState<string[]>([]);
  const abortRef = useRef<AbortController | null>(null);
  const aiGeneration = useRef(0);
  const aiPreviewScopeRef = useRef<LocalizationAiPreviewScope | null>(null);
  const [aiPreviewScope, setAiPreviewScope] = useState<LocalizationAiPreviewScope | null>(null);
  const [aiEntryVersions, setAiEntryVersions] = useState<Record<string, number>>({});
  const bundleGeneration = useRef(0);
  const bundleController = useRef<AbortController | null>(null);
  const bundlePreviewSnapshot = useRef<LocalizationBundlePreviewScope<Bundle, BundlePreview> | null>(null);
  const [bundleBusyGeneration, setBundleBusyGeneration] = useState<number | null>(null);

  const locales = useQuery({ queryKey: ["localization-locales"], enabled: canRead, queryFn: ({ signal }) => api<LocaleView[]>("/platform/localization/locales", { signal }) });
  const localeOptions = locales.data?.length
    ? locales.data.map((item) => ({ value: item.locale, label: item.displayName || item.locale }))
    : languages.map((item) => ({ value: item.value, label: item.label }));
  const listQuery = useQuery({
    queryKey: ["localization-catalog", locale, request],
    enabled: canRead,
    queryFn: ({ signal }) => {
      const params = listRequestParams(request, { locale });
      return api<PageResult<CatalogEntry>>(`/platform/localization/entries?${params}`, { signal });
    },
  });
  const providers = useQuery({ queryKey: ["localization-ai-providers"], enabled: canRead, queryFn: ({ signal }) => api<ProviderOption[]>("/platform/localization/ai/providers", { signal }) });
  const selectedProvider = providers.data?.find((item) => item.id === providerId);
  const bundlePreview = bundlePreviewSnapshotValue?.result ?? null;
  const bundleBusy = bundleBusyGeneration !== null;

  const setBundlePreviewContext = (value: LocalizationBundlePreviewScope<Bundle, BundlePreview> | null) => {
    bundlePreviewSnapshot.current = value;
    setBundlePreviewSnapshotValue(value);
  };
  const invalidateBundle = (clearBundle = false) => {
    bundleGeneration.current += 1;
    bundleController.current?.abort();
    bundleController.current = null;
    setBundleBusyGeneration(null);
    setBundlePreviewContext(null);
    setBundleError(null);
    if (clearBundle) {
      setBundle(null);
      setBundleName("");
      setIgnoreUnknown(false);
    }
    return bundleGeneration.current;
  };
  const invalidateAi = () => {
    aiGeneration.current += 1;
    abortRef.current?.abort();
    abortRef.current = null;
    aiPreviewScopeRef.current = null;
    setAiPreviewScope(null);
    setAiResults({});
    setAiSelected([]);
    setAiEntryVersions({});
    setAiProgress({ done: 0, total: 0, failed: 0 });
    setAiBusy(false);
    return aiGeneration.current;
  };

  useEffect(() => () => {
    bundleGeneration.current += 1;
    bundleController.current?.abort();
    aiGeneration.current += 1;
    abortRef.current?.abort();
  }, []);

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
    if (!canCreate) return;
    editorForm.resetFields();
    editorForm.setFieldsValue({ messageKey: "custom.", defaultMessage: "", currentValue: "" });
    setEditor(null);
  };
  const openEdit = (row: CatalogEntry) => {
    editorForm.setFieldsValue({ messageKey: row.messageKey, defaultMessage: row.sourceMessage, currentValue: row.currentValue });
    setEditor(row);
  };
  const {pending:editorBusy,run:runEditor}=useSubmitAction({errorKeys,onError:async(key,error)=>{
    void message.error(t(key));if(error instanceof ApiError&&error.status===409)await listQuery.refetch();
  }});
  const saveEditor = async (values: EditorValues) => {
    return runEditor(async () => {
      if (editor ? !canUpdate : !canCreate) return false;
      const signal = sessionSignal();
      signal.throwIfAborted();
      try {
        if (editor) {
          await mutate("/platform/localization/translations", "POST", {
            locale,
            overwriteExisting: true,
            entries: [{ messageKey: editor.messageKey, value: values.currentValue, version: editor.version }],
          }, signal);
        } else {
          await mutate("/platform/localization/entries", "POST", {
            messageKey: values.messageKey,
            namespace: "custom",
            defaultMessage: values.defaultMessage,
            translations: { [locale]: values.currentValue },
            enabled: true,
          }, signal);
        }
      } catch (error) {
        if (signal.aborted) return false;
        throw error;
      }
      if (signal.aborted) return false;
      setEditor(undefined);
      await invalidate();
      if (signal.aborted) return false;
      void message.success(t("localizationSaved"));
    });
  };
  const restoreDefault = async (row: CatalogEntry) => {
    if (!canUpdate || !row.overridden || restoreBusyKey) return;
    const signal = sessionSignal();
    signal.throwIfAborted();
    setRestoreBusyKey(row.messageKey);
    const params = new URLSearchParams({ messageKey: row.messageKey, version: String(row.version) });
    try {
      await mutate(`/platform/localization/translations/${encodeURIComponent(locale)}?${params.toString()}`, "DELETE", undefined, signal);
      if (signal.aborted) return;
      await invalidate();
      if (signal.aborted) return;
      void message.success(t("localizationDefaultRestored"));
    } catch (error) {
      if (signal.aborted) return;
      void message.error(t(messageKeyFor(error, errorKeys)));
      if (error instanceof ApiError && error.status === 409) await listQuery.refetch();
    } finally { if (!signal.aborted) setRestoreBusyKey(null); }
  };

  const chooseBundle = async (file?: File) => {
    const generation = invalidateBundle(true);
    if (!file) return;
    setBundleName(file.name);
    if (file.size > 8_000_000) { setBundleError(t("invalidLocalizationBundle")); return; }
    const controller = new AbortController();
    bundleController.current = controller;
    const signal = AbortSignal.any([sessionSignal(), controller.signal]);
    try {
      const contents = await file.text();
      if (signal.aborted || generation !== bundleGeneration.current) return;
      const parsed: unknown = JSON.parse(contents);
      if (!isPackage(parsed) || Object.keys(parsed.messages).length > 20_000) throw new Error("invalidLocalizationBundle");
      if (signal.aborted || generation !== bundleGeneration.current) return;
      const snapshot = Object.freeze({ ...parsed, messages: Object.freeze({ ...parsed.messages }) });
      setBundle(snapshot);
    } catch {
      if (!signal.aborted && generation === bundleGeneration.current) setBundleError(t("invalidLocalizationBundle"));
    } finally {
      if (bundleController.current === controller) bundleController.current = null;
    }
  };
  const previewImport = async () => {
    if (!bundle) return;
    const candidate = bundle;
    const overwrite = bundleOverwrite;
    const generation = bundleGeneration.current;
    bundleController.current?.abort();
    const controller = new AbortController();
    bundleController.current = controller;
    const signal = AbortSignal.any([sessionSignal(), controller.signal]);
    setBundleBusyGeneration(generation); setBundleError(null); setBundlePreviewContext(null);
    try {
      const result = await mutate<BundlePreview>("/platform/localization/bundle/preview", "POST", { bundle: candidate, overwriteExisting: overwrite }, signal);
      if (!signal.aborted && generation === bundleGeneration.current && bundle === candidate && bundleOverwrite === overwrite) {
        setBundlePreviewContext(Object.freeze({ generation, bundle: candidate, overwrite, result }));
      }
    } catch (error) {
      if (!signal.aborted && generation === bundleGeneration.current) setBundleError(t(messageKeyFor(error, errorKeys)));
    } finally {
      if (bundleController.current === controller) bundleController.current = null;
      if (!signal.aborted && generation === bundleGeneration.current) setBundleBusyGeneration(null);
    }
  };
  const confirmImport = async () => {
    if (!canImport) return;
    const preview = bundlePreviewSnapshot.current;
    if (!preview || !isCurrentLocalizationBundlePreview(preview, { generation: bundleGeneration.current, bundle, overwrite: bundleOverwrite }) || (preview.result.unknown > 0 && !ignoreUnknown)) return;
    const candidate = preview.bundle;
    const overwrite = preview.overwrite;
    const ignore = ignoreUnknown;
    const generation = preview.generation;
    const controller = new AbortController();
    bundleController.current = controller;
    const signal = AbortSignal.any([sessionSignal(), controller.signal]);
    setBundleBusyGeneration(generation); setBundleError(null);
    try {
      const result = await mutate<{ locale: string }>("/platform/localization/bundle/import", "POST", {
        bundle: candidate, overwriteExisting: overwrite, ignoreUnknownKeys: ignore,
      }, signal);
      if (signal.aborted || !isCurrentLocalizationBundlePreview(preview, { generation: bundleGeneration.current, bundle, overwrite: bundleOverwrite })) return;
      await invalidate();
      if (signal.aborted || !isCurrentLocalizationBundlePreview(preview, { generation: bundleGeneration.current, bundle, overwrite: bundleOverwrite })) return;
      invalidateBundle(true);
      setBundleOpen(false);
      setSelectedLocale(result.locale);
      void message.success(t("localizationSaved"));
    } catch (error) {
      if (!signal.aborted && isCurrentLocalizationBundlePreview(preview, { generation: bundleGeneration.current, bundle, overwrite: bundleOverwrite })) setBundleError(t(messageKeyFor(error, errorKeys)));
    } finally {
      if (bundleController.current === controller) bundleController.current = null;
      if (!signal.aborted && generation === bundleGeneration.current) setBundleBusyGeneration(null);
    }
  };
  const exportBundle = async () => {
    const signal = sessionSignal();
    try {
      const result = await api<Bundle>(`/public/localization/bundle/${encodeURIComponent(locale)}`, { signal });
      if (signal.aborted) return;
      const blob = new Blob([JSON.stringify(result, null, 2)], { type: "application/json;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a"); anchor.href = url; anchor.download = `${result.locale}.language-bundle.json`; anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) { if (!signal.aborted) void message.error(t(messageKeyFor(error, errorKeys))); }
  };

  const publishLocale = async () => {
    if (!canPublish) return;
    try {
      const result = await mutate<{ locale: string; version: number; count: number }>(`/platform/localization/publish/${encodeURIComponent(locale)}`, "POST", {});
      await invalidate();
      void message.success(t("localizationPublished", result));
    } catch (error) {
      void message.error(t(messageKeyFor(error, errorKeys)));
    }
  };

  const runAi = async (retryKeys?: string[]) => {
    if (!canUpdate || !providerId || !modelName || aiBusy) return;
    const generation = retryKeys ? aiGeneration.current : invalidateAi();
    const currentContext = (sourceBundleVersion: number | null, sourceSnapshotToken: string): LocalizationAiPreviewScope => Object.freeze({
      generation,
      sourceLocale: aiSource,
      targetLocale: aiTarget,
      providerId,
      modelName,
      providerVersion: selectedProvider?.version ?? null,
      sourceBundleVersion,
      sourceSnapshotToken,
      overwriteManual: aiScope === "all-missing" ? false : overwriteManual,
      scope: aiScope,
    });
    const previousContext = aiPreviewScopeRef.current;
    if (retryKeys && (!previousContext || !sameLocalizationAiScope(previousContext, currentContext(previousContext.sourceBundleVersion, previousContext.sourceSnapshotToken)))) return;
    const controller = new AbortController();
    abortRef.current = controller;
    const signal = AbortSignal.any([sessionSignal(), controller.signal]);
    setAiBusy(true);
    if (!retryKeys) setAiEntryVersions({});
    try {
      const candidates = retryKeys
        ? retryKeys.flatMap((key) => aiResults[key]?.generation === generation ? [aiResults[key].entry] : [])
        : await loadTranslationCandidates<CatalogEntry>({ scope: aiScope, request, selectedKeys, onlyMissing, signal }, (pageRequest) => {
          const params = listRequestParams(pageRequest, { locale: aiTarget });
          return api<PageResult<CatalogEntry>>(`/platform/localization/entries?${params}`, { signal });
        });
      if (signal.aborted || generation !== aiGeneration.current) return;
      if (!candidates.length) { void message.info(t("localizationNoSelection")); return; }
      const sourceBundle = await api<Bundle>(`/public/localization/bundle/${encodeURIComponent(aiSource)}`, { signal });
      if (signal.aborted || generation !== aiGeneration.current) return;
      if (!sourceBundle.snapshotToken) throw new ApiError(409, "localizationSourceChanged");
      const context = currentContext(sourceBundle.releaseVersion ?? null, sourceBundle.snapshotToken);
      if (retryKeys && !sameLocalizationAiScope(previousContext, context)) {
        invalidateAi();
        void message.error(t("staleRecord"));
        return;
      }
      aiPreviewScopeRef.current = context;
      setAiPreviewScope(context);
      const sourceMap = sourceBundle.messages ?? {};
      const work = candidates.flatMap((entry) => typeof sourceMap[entry.messageKey] === "string" ? [{ entry, source: sourceMap[entry.messageKey] }] : []);
      if (!work.length) { void message.error(t("invalidLocalization")); return; }
      setAiEntryVersions((current) => ({ ...current, ...Object.fromEntries(work.map(({ entry }) => [entry.messageKey, entry.version])) }));
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
        if (signal.aborted || generation !== aiGeneration.current) break;
        let returned: { items?: Array<{ messageKey: string; translation?: string; errorCode?: string }> } | undefined;
        try {
          returned = await mutate("/platform/localization/ai/translate", "POST", {
            providerId, modelName, sourceLocale: aiSource, targetLocale: aiTarget,
            sourceSnapshotToken: context.sourceSnapshotToken,
            messageKeys: part.map((item) => item.entry.messageKey),
          }, signal);
        } catch (error) {
          if (signal.aborted || generation !== aiGeneration.current) break;
          if (error instanceof ApiError && error.message === "localizationSourceChanged") {
            invalidateAi();
            void message.error(t("staleRecord"));
            return;
          }
          const code = messageKeyFor(error, errorKeys);
          returned = { items: part.map((item) => ({ messageKey: item.entry.messageKey, errorCode: code })) };
        }
        if (signal.aborted || generation !== aiGeneration.current) break;
        const results = new Map((returned?.items ?? []).map((item) => [item.messageKey, item]));
        const patch: Record<string, AiPreview> = {};
        for (const item of part) {
          const result = results.get(item.entry.messageKey);
          if (result?.translation) patch[item.entry.messageKey] = { ...item, translation: result.translation, generation };
          else { patch[item.entry.messageKey] = { ...item, errorCode: result?.errorCode ?? "localizationAiItemError", generation }; failed++; }
          done++;
        }
        setAiResults((current) => mergeLocalizationAiResults(current, patch, generation, aiGeneration.current));
        setAiSelected((current) => [...new Set([...current, ...Object.entries(patch).filter(([, result]) => result.translation && !result.entry.overridden).map(([key]) => key)])]);
        if (generation === aiGeneration.current) setAiProgress({ done, total: work.length, failed });
      }
    } catch (error) {
      if (!signal.aborted && generation === aiGeneration.current) void message.error(t(messageKeyFor(error, errorKeys)));
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      if (!signal.aborted && generation === aiGeneration.current) setAiBusy(false);
    }
  };
  const saveAiSelected = async () => {
    if (!canUpdate) return;
    const context = aiPreviewScopeRef.current;
    if (!context || !sameLocalizationAiScope(context, Object.freeze({
      ...context,
      generation: aiGeneration.current,
      sourceLocale: aiSource,
      targetLocale: aiTarget,
      providerId: providerId ?? "",
      modelName: modelName ?? "",
      providerVersion: selectedProvider?.version ?? null,
      overwriteManual: aiScope === "all-missing" ? false : overwriteManual,
      scope: aiScope,
    }))) return;
    const values = aiSelected.flatMap((key) => {
      const item = aiResults[key];
      if (!item?.translation || item.generation !== context.generation || aiEntryVersions[key] !== item.entry.version) return [];
      return [item];
    });
      if (!values.length) return;
    const controller = new AbortController();
    abortRef.current = controller;
    const signal = AbortSignal.any([sessionSignal(), controller.signal]);
    const generation = context.generation;
    setAiBusy(true);
    try {
      // The translations endpoint accepts at most 100 overrides per transaction.
      for (let start = 0; start < values.length; start += 100) {
        const batch = values.slice(start, start + 100);
        signal.throwIfAborted();
        if (generation !== aiGeneration.current) return;
        await mutate("/platform/localization/translations", "POST", {
          locale: context.targetLocale,
          overwriteExisting: context.overwriteManual,
          sourceLocale: context.sourceLocale,
          sourceSnapshotToken: context.sourceSnapshotToken,
          entries: batch.map((item) => ({ messageKey: item.entry.messageKey, value: item.translation, version: item.entry.version })),
        }, signal);
        if (signal.aborted || generation !== aiGeneration.current) return;
        const savedKeys = new Set(batch.map((item) => item.entry.messageKey));
        setAiResults((current) => Object.fromEntries(Object.entries(current).filter(([key]) => !savedKeys.has(key))));
        setAiSelected((current) => current.filter((key) => !savedKeys.has(key)));
      }
      await invalidate();
      if (signal.aborted || generation !== aiGeneration.current) return;
      setSelectedLocale(context.targetLocale);
      invalidateAi();
      setAiOpen(false);
      void message.success(t("localizationSaved"));
    } catch (error) {
      if (!signal.aborted && generation === aiGeneration.current) {
        if (error instanceof ApiError && error.message === "localizationSourceChanged") {
          invalidateAi();
          await invalidate();
          void message.error(t("staleRecord"));
          return;
        }
        await invalidate();
        if (generation === aiGeneration.current) void message.error(t(messageKeyFor(error, errorKeys)));
      }
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      if (!signal.aborted && generation === aiGeneration.current) setAiBusy(false);
    }
  };
  const aiResultItems = Object.entries(aiResults);
  const failedKeys = aiResultItems.filter(([, item]) => !item.translation).map(([key]) => key);
  const missingOptions = localeOptions;

  return <>
    {canRead ? <ResourceTable<CatalogEntry>
      result={listQuery.data} loading={listQuery.isFetching} error={listQuery.error} request={request}
      onChange={(next) => { invalidateAi(); if (next.page !== request.page || next.search !== request.search) setSelectedKeys([]); setRequest(next); }}
      onRetry={() => void listQuery.refetch()}
      rowSelection={canUpdate ? { selectedRowKeys: selectedKeys, onChange: (keys) => { invalidateAi(); setSelectedKeys(keys.map(String)); } } : undefined}
      actions={<Space wrap>
        {canCreate && <Button type="primary" onClick={openCreate}>{t("createLocalizationEntry")}</Button>}
        {canImport && <Button onClick={() => { invalidateBundle(true); setBundleOpen(true); }}>{t("importLocalizationEntries")}</Button>}
        <Button onClick={() => void exportBundle()}>{t("localizationExportBundle")}</Button>
        <Select aria-label={t("localizationLocale")} value={locale} options={localeOptions} onChange={(value) => { invalidateAi(); setSelectedLocale(value); setSelectedKeys([]); setRequest({ ...request, page: 1 }); }} style={{ minWidth: 150, maxWidth: "100%" }} />
        {canUpdate && <Button onClick={() => { invalidateAi(); setAiTarget(locale); setAiOpen(true); void providers.refetch(); }}>{t("localizationAiTranslate")}</Button>}
        {canPublish && <AsyncButton label={t("publishLocalization")} pending={locales.isFetching} onClick={publishLocale} />}
      </Space>}
      columns={[
        { title: t("localizationKey"), dataIndex: "messageKey", search: true, ellipsis: true },
        { title: t("localizationNamespace"), dataIndex: "namespace", ellipsis: true },
        { title: t("localizationSourceText"), dataIndex: "sourceMessage", width: 300, render: (_value, row) => <Typography.Paragraph style={{ maxWidth: 300, margin: 0, overflowWrap: "anywhere", whiteSpace: "pre-wrap" }}>{row.sourceMessage}</Typography.Paragraph> },
        { title: t("localizationEffectiveValue"), dataIndex: "currentValue", width: 300, render: (_value, row) => <Typography.Paragraph style={{ maxWidth: 300, margin: 0, overflowWrap: "anywhere", whiteSpace: "pre-wrap" }}>{row.currentValue || t("localizationMissing")}</Typography.Paragraph> },
        { title: t("localizationValueSource"), dataIndex: "valueSource", render: (_value, row) => { const state = sourceState(row, t); return <Tag color={state.color}>{state.label}</Tag>; } },
        { title: t("status"), dataIndex: "enabled", render: (_value, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
        { title: t("actions"), key: "actions", render: (_value, row) => <TableActions wrap>
          <Button type="link" onClick={() => openEdit(row)}>{t(canUpdate ? "edit" : "details")}</Button>
          {canUpdate && row.overridden && <Popconfirm
            title={t("localizationRestoreDefaultConfirm")}
            okText={t("localizationRestoreDefault")}
            cancelText={t("cancel")}
            okButtonProps={{ loading: restoreBusyKey === row.messageKey }}
            cancelButtonProps={{ disabled: Boolean(restoreBusyKey) }}
            onConfirm={() => restoreDefault(row)}
            disabled={Boolean(restoreBusyKey)}
          ><Button type="link" loading={restoreBusyKey === row.messageKey} disabled={Boolean(restoreBusyKey)}>{t("localizationRestoreDefault")}</Button></Popconfirm>}
          {canUpdate && !row.builtIn && <AsyncButton type="link" label={t(row.enabled ? "disableLocalization" : "enableLocalization")} pending={editorBusy} onClick={async () => { try { await mutate(`/platform/localization/entries/${row.id}/status`, "PATCH", { enabled: !row.enabled, version: row.version }); await invalidate(); } catch (error) { void message.error(t(messageKeyFor(error, errorKeys))); } }} />}
          {canDelete && !row.builtIn && <DeleteAction name={row.messageKey} onDelete={async () => { await mutate(`/platform/localization/entries/${row.id}?version=${row.version}`, "DELETE"); await invalidate(); }} />}
        </TableActions> },
      ]}
    /> : <Alert type="info" showIcon message={t("forbidden")} />}

    <Modal open={editor !== undefined} title={t(editor ? "editLocalizationEntry" : "createLocalizationEntry")} onCancel={() => { if (!editorBusy) setEditor(undefined); }} footer={null} width={640} style={{ maxWidth: "calc(100vw - 24px)" }} styles={{ body: { maxHeight: "70vh", overflowY: "auto" } }} destroyOnHidden>
      {editor !== undefined && <Form id="localization-editor" name="localization-editor" form={editorForm} layout="vertical" disabled={editorBusy || (editor === null ? !canCreate : !canUpdate)} onFinish={(values) => void saveEditor(values as EditorValues)}>
        <Form.Item name="messageKey" label={t("localizationKey")} rules={[{ required: true, pattern: editor ? /^[A-Za-z][A-Za-z0-9_.:-]{0,199}$/u : /^custom\.[A-Za-z0-9][A-Za-z0-9_.-]{0,193}$/u }]}><Input disabled={Boolean(editor)} maxLength={200} /></Form.Item>
        {editor === null && <Form.Item name="defaultMessage" label={t("localizationDefaultMessage")} rules={[{ required: true, max: 2000 }]}><Input.TextArea rows={4} maxLength={2000} /></Form.Item>}
        {editor === null && <Alert type="info" showIcon message={t("localizationCustomKeyHint")} />}
        {editor && <><Alert type="info" showIcon message={t("localizationEditTranslationHint")} /><Typography.Paragraph type="secondary" style={{ overflowWrap: "anywhere", marginTop: 12 }}>{editor.sourceMessage}</Typography.Paragraph></>}
        <Form.Item name="currentValue" label={t("localizationEffectiveValue")} rules={[{ required: true, whitespace: true, max: 2000 }]}><Input.TextArea rows={6} maxLength={2000} /></Form.Item>
        <Space wrap>
          <Button onClick={() => setEditor(undefined)} disabled={editorBusy}>{t("cancel")}</Button>
          <Button type="primary" htmlType="submit" form="localization-editor" loading={editorBusy} disabled={editor === null ? !canCreate : !canUpdate}>{t("save")}</Button>
        </Space>
      </Form>}
    </Modal>

    <Modal open={bundleOpen} title={t("importLocalizationEntries")} onCancel={() => { if (!bundleBusy) { invalidateBundle(true); setBundleOpen(false); } }} width={720} style={{ maxWidth: "calc(100vw - 24px)" }} styles={{ body: { maxHeight: "70vh", overflowY: "auto" } }} footer={null} destroyOnHidden>
        <Space direction="vertical" size="middle" style={{ width: "100%" }}>
        <Typography.Text>{t("localizationBundleFile")}</Typography.Text>
        <input ref={fileInput} type="file" accept=".json,application/json" hidden onChange={(event) => { void chooseBundle(event.currentTarget.files?.[0]); event.currentTarget.value = ""; }} />
        <Space wrap><Button onClick={() => fileInput.current?.click()}>{t("localizationSelectBundleFile")}</Button><Typography.Text ellipsis>{bundleName}</Typography.Text></Space>
        <Typography.Text type="secondary">{t("localizationBundleFormat")}: v1 · {t("localizationSourceLocale")} · {t("localizationFallbackLocale")} · messages</Typography.Text>
        <Alert type="info" showIcon message={t("localizationUnknownKeyHint")} />
        <Radio.Group value={bundleOverwrite} onChange={(event) => { invalidateBundle(false); setBundleOverwrite(event.target.value); }}>
          <Space direction="vertical"><Radio value={false}>{t("localizationKeepExisting")}</Radio><Radio value>{t("localizationOverwriteExisting")}</Radio></Space>
        </Radio.Group>
        {bundleError && <Alert type="error" showIcon message={bundleError} />}
        {bundlePreview && <Alert type="info" showIcon message={t("localizationBundleStats", { added: bundlePreview.added, duplicates: bundlePreview.duplicates, overwrites: bundlePreview.overwrites, unknown: bundlePreview.unknown, fallbacks: bundlePreview.fallbacks })} description={<div style={{ overflowWrap: "anywhere" }}>{bundlePreview.unknown > 0 && <><Typography.Text strong>{t("localizationUnknownKeys")}</Typography.Text><div>{bundlePreview.unknownKeys.map((key) => <div key={key}>{key}</div>)}</div><Checkbox checked={ignoreUnknown} onChange={(event) => setIgnoreUnknown(event.target.checked)}>{t("localizationIgnoreUnknown")}</Checkbox></>}</div>} />}
        <Space wrap>
          <Button onClick={() => void previewImport()} disabled={!bundle || bundleBusy}>{t("localizationBundlePreview")}</Button>
          <AsyncButton type="primary" pending={bundleBusy} disabled={!bundlePreview || (bundlePreview.unknown > 0 && !ignoreUnknown)} label={t("localizationConfirmImport")} onClick={() => void confirmImport()} />
          <Button onClick={() => { invalidateBundle(true); setBundleOpen(false); }} disabled={bundleBusy}>{t("cancel")}</Button>
        </Space>
      </Space>
    </Modal>

    <Modal open={aiOpen} title={t("localizationAiTranslate")} onCancel={() => { if (!aiBusy) { invalidateAi(); setAiOpen(false); } }} width={760} style={{ maxWidth: "calc(100vw - 24px)" }} styles={{ body: { maxHeight: "72vh", overflowY: "auto" } }} footer={null} destroyOnHidden>
      <Space direction="vertical" size="middle" style={{ width: "100%" }}>
        {providers.data?.length === 0 && <Alert type="warning" showIcon message={t("localizationAiUnavailable")} />}
        {providers.error && <Alert type="error" showIcon message={t(messageKeyFor(providers.error, errorKeys))} />}
        <div className="localization-ai-fields" style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(min(300px, 100%), 1fr))", gap: 12 }}>
          <label>{t("localizationSourceLocale")}<Select disabled={aiBusy} aria-label={t("localizationSourceLocale")} value={aiSource} options={missingOptions} onChange={(value) => { invalidateAi(); setAiSource(value); }} style={{ width: "100%" }} /></label>
          <label>{t("localizationLocale")}<Select disabled={aiBusy} aria-label={t("localizationLocale")} value={aiTarget} options={missingOptions} onChange={(value) => { invalidateAi(); setAiTarget(value); }} style={{ width: "100%" }} /></label>
          <label>{t("localizationAiProvider")}<Select disabled={aiBusy} aria-label={t("localizationAiProvider")} value={providerId} options={(providers.data ?? []).map((item) => ({ value: item.id, label: item.name }))} onChange={(id) => { invalidateAi(); setProviderId(id); setModelName(providers.data?.find((item) => item.id === id)?.modelName); }} style={{ width: "100%" }} /></label>
          <label>{t("localizationAiModel")}<Select disabled={aiBusy} aria-label={t("localizationAiModel")} value={modelName} options={selectedProvider ? [{ value: selectedProvider.modelName, label: selectedProvider.modelName }] : []} onChange={(value) => { invalidateAi(); setModelName(value); }} style={{ width: "100%" }} /></label>
        </div>
        <Typography.Text>{t("localizationAiSelectScope")}</Typography.Text>
        <Radio.Group disabled={aiBusy} value={aiScope} onChange={(event) => { invalidateAi(); setAiScope(event.target.value); }}><Space wrap><Radio value="page">{t("localizationCurrentPage")}</Radio><Radio value="selected">{t("localizationSelectedKeys")} ({selectedKeys.length})</Radio><Radio value="all-missing">{t("localizationAllMissing")}</Radio></Space></Radio.Group>
        <Checkbox disabled={aiBusy || aiScope === "all-missing"} checked={aiScope === "all-missing" || onlyMissing} onChange={(event) => { invalidateAi(); setOnlyMissing(event.target.checked); }}>{t("localizationOnlyMissing")}</Checkbox>
        <Checkbox disabled={aiBusy || aiScope === "all-missing"} checked={aiScope !== "all-missing" && overwriteManual} onChange={(event) => { invalidateAi(); setOverwriteManual(event.target.checked); }}>{t("localizationOverwriteManual")}</Checkbox>
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
          {aiPreviewScope && <Typography.Text type="secondary">
            {t("localizationSourceLocale")}: {aiPreviewScope.sourceLocale} → {t("localizationLocale")}: {aiPreviewScope.targetLocale} ·
            {t("localizationAiProvider")}: {selectedProvider?.name ?? aiPreviewScope.providerId} / {aiPreviewScope.modelName}
            {aiPreviewScope.providerVersion === null ? "" : ` v${aiPreviewScope.providerVersion}`} ·
            {t("localizationBundleFormat")} v{aiPreviewScope.sourceBundleVersion ?? "?"} · #{aiPreviewScope.generation} ·
            {t("localizationOverwriteManual")}: {t(aiPreviewScope.overwriteManual ? "enabled" : "disabled")}
          </Typography.Text>}
          <Checkbox.Group value={aiSelected} onChange={(values) => setAiSelected(values.map(String))} style={{ width: "100%" }}>
            <Space direction="vertical" style={{ width: "100%" }}>
              {aiResultItems.map(([key, item]) => <div key={key} style={{ border: "1px solid var(--ant-color-border-secondary)", borderRadius: 6, padding: 10, overflowWrap: "anywhere" }}>
                <Checkbox value={key} disabled={!item.translation || item.generation !== aiPreviewScope?.generation || aiEntryVersions[key] !== item.entry.version}>{key} · v{item.entry.version}</Checkbox>
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
