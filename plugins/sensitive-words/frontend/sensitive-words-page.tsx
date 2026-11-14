"use client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {Alert, App, Button, Collapse, Input, Radio, Select, Space, Spin, Table, Tag, Typography} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { ApiError, api, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import { AsyncButton } from "@/platform/components/async-button";
import { DeleteAction } from "@/platform/components/delete-action";
import { FormDrawer } from "@/platform/components/form-drawer";
import { useCenteredModalConfirm } from "@/platform/components/use-centered-modal-confirm";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import { builtinDictionaryItemLabel } from "@/platform/i18n/builtin-dictionary-labels";
import type { MessageKey } from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import {
  decodeAndParseSensitiveWordCsv,
  sensitiveCsvFileSizeIssue,
  validateSensitiveImportPayload,
  validateSensitivePaste,
  sensitiveWordValidationIssue,
  SENSITIVE_IMPORT_LIMITS,
  type SensitiveCsvParseResult,
  type SensitiveImportIssue,
} from "./sensitive-word-csv";

type SensitiveWord = {
  id: string;
  word: string;
  normalizedWord: string;
  category: string;
  severity: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  action: "BLOCK" | "LOG_ONLY";
  enabled: boolean;
  version: number;
};
type SensitiveCategoryOption = { value: string; label: string; sortOrder: number };
type SensitiveCategoryOptionsResponse = { categories: SensitiveCategoryOption[] };
type EditorValues = Pick<SensitiveWord, "word" | "category" | "severity" | "action" | "enabled">;
type ImportSource = "paste" | "csv";
type ImportValues = { words?: string; category: string; severity: SensitiveWord["severity"]; action: SensitiveWord["action"] };
const severityColors: Record<SensitiveWord["severity"], string> = { LOW: "default", MEDIUM: "blue", HIGH: "orange", CRITICAL: "red" };
const severityLabels: Record<SensitiveWord["severity"], MessageKey> = {
  LOW: "sensitiveSeverityLOW",
  MEDIUM: "sensitiveSeverityMEDIUM",
  HIGH: "sensitiveSeverityHIGH",
  CRITICAL: "sensitiveSeverityCRITICAL",
};
const severities = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const satisfies readonly SensitiveWord["severity"][];

const sensitiveErrorKeys = [
    "forbidden", "invalidSensitiveWord", "invalidSensitiveImport", "sensitiveWordNotFound", "duplicateSensitiveWord", "staleRecord", "moduleUnavailable", "sensitiveTextTooLong",
] as const satisfies readonly MessageKey[];

export function SensitiveWordLibrary({ canRead, canCreate, canUpdate, canDelete, canImport }: { canRead: boolean; canCreate: boolean; canUpdate: boolean; canDelete: boolean; canImport: boolean }) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const confirm = useCenteredModalConfirm();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [selected, setSelected] = useState<string | null | undefined>();
  const [editingCategory, setEditingCategory] = useState<string | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [importSource, setImportSource] = useState<ImportSource>("paste");
  const [csvFile, setCsvFile] = useState<File | null>(null);
  const [csvPreview, setCsvPreview] = useState<SensitiveCsvParseResult | null>(null);
  const [csvFileError, setCsvFileError] = useState<MessageKey | null>(null);
  const [csvParsing, setCsvParsing] = useState(false);
  const csvReadGeneration = useRef(0);
  const [importIssues, setImportIssues] = useState<SensitiveImportIssue[]>([]);
  const [confirmingImport, setConfirmingImport] = useState(false);

  const [pendingToggle, setPendingToggle] = useState<string | null>(null);

  const [editorForm] = Form.useForm<EditorValues>();
  const [importForm] = Form.useForm<ImportValues>();
  const categoriesQuery = useQuery({
    queryKey: ["dictionary-options", "sensitive_word_category"],
    enabled: canRead,
    queryFn: ({ signal }) => api<SensitiveCategoryOptionsResponse>("/platform/sensitive-words/options", { signal }),
  });
  const categoryOptions = categoriesQuery.data?.categories ?? [];
  const categoryLabel = (value: string, stored: string) => builtinDictionaryItemLabel("sensitive_word_category", value, stored, t);
  const categoryLabels = Object.fromEntries(categoryOptions.map((item) => [item.value, categoryLabel(item.value, item.label)]));
  const categoryValueEnum = Object.fromEntries(categoryOptions.map((item) => [item.value, { text: categoryLabel(item.value, item.label) }]));
  const editorCategoryOptions = (currentCategory?: string | null) => {
    const options = categoryOptions.map(({ value, label }) => ({ value, label: categoryLabel(value, label) }));
    if (currentCategory && !categoryOptions.some((item) => item.value === currentCategory)) {
      options.push({ value: currentCategory, label: currentCategory });
    }
    return options;
  };
  const listQuery = useQuery({
    queryKey: ["sensitive-words", request],
    enabled: canRead,
    queryFn: ({ signal }) => {
      return api<PageResult<SensitiveWord>>(`/platform/sensitive-words?${listRequestParams(request)}`, { signal });
    },
  });
  const detail = useQuery({
    queryKey: ["sensitive-word", selected],
    enabled: canRead && typeof selected === "string",
    queryFn: ({ signal }) => api<SensitiveWord>(`/platform/sensitive-words/${selected}`, { signal }),
  });
  const saveAction=useSubmitAction({errorKeys:sensitiveErrorKeys,onError:async(_key,error)=>{
    if(error instanceof ApiError&&error.status===409&&selected)await detail.refetch();
  }});
  const importAction=useSubmitAction({errorKeys:sensitiveErrorKeys,onError:key=>{void message.error(t(key));}});
  const pending=saveAction.pending||importAction.pending;
  const {error:formError,setError:setFormError}=saveAction;
  const ready = selected === null || (!!detail.data && !detail.isFetching && !detail.error);

  const openCreate = () => {
    if (!canCreate) return;
    setFormError(null);
    setEditingCategory(null);
    editorForm.resetFields();
    editorForm.setFieldsValue({ category: "DEFAULT", severity: "MEDIUM", action: "LOG_ONLY", enabled: true });
    setSelected(null);
  };
  const openEdit = (row: SensitiveWord) => {
    setFormError(null);
    setEditingCategory(row.category);
    setSelected(row.id);
    editorForm.setFieldsValue(row);
  };
  const closeEditor = () => {
    if (!pending) {
      setSelected(undefined);
      setEditingCategory(null);
      setFormError(null);
      editorForm.resetFields();
    }
  };
  const refresh = () => client.invalidateQueries({ queryKey: ["sensitive-words"] });

  const clearCsvSelection = () => {
    csvReadGeneration.current += 1;
    setCsvFile(null);
    setCsvPreview(null);
    setCsvFileError(null);
    setImportIssues([]);
    setCsvParsing(false);
  };

  const openImport = () => {
    importForm.resetFields();
    importForm.setFieldsValue({ category: "IMPORTED", severity: "MEDIUM", action: "LOG_ONLY" });
    setImportSource("paste");
    clearCsvSelection();
    setImportOpen(true);
  };

  const closeImport = () => {
    if (pending || confirmingImport || csvParsing) return;
    setImportOpen(false);
    importForm.resetFields();
    setImportSource("paste");
    clearCsvSelection();
  };

  const loadCsvFile = async (file: File | null) => {
    clearCsvSelection();
    const readGeneration = csvReadGeneration.current;
    if (!file) return;
    setCsvFile(file);
    if (!file.name.toLocaleLowerCase().endsWith(".csv")) {
      setCsvFileError("sensitiveImportCsvWrongExtension");
      return;
    }
    const sizeIssue = sensitiveCsvFileSizeIssue(file.size);
    if (sizeIssue) {
      setCsvFileError(sizeIssue.key);
      return;
    }
    setCsvParsing(true);
    try {
      const parsed = decodeAndParseSensitiveWordCsv(new Uint8Array(await file.arrayBuffer()));
      if (readGeneration !== csvReadGeneration.current) return;
      setCsvPreview(parsed);
      setImportIssues(parsed.issues);
    } catch {
      if (readGeneration !== csvReadGeneration.current) return;
      setCsvFileError("sensitiveImportCsvReadFailed");
    } finally {
      if (readGeneration === csvReadGeneration.current) setCsvParsing(false);
    }
  };

  const save = async (values: EditorValues) => {
    if (pending || (selected ? !canUpdate : !canCreate)) return;
    return saveAction.run(async () => {
      await mutate(selected ? `/platform/sensitive-words/${selected}` : "/platform/sensitive-words", selected ? "PUT" : "POST", { ...values, version: selected ? detail.data?.version : undefined });
      closeEditor();
      await refresh();
      void message.success(t("sensitiveWordSaved"));
    });
  };

  const toggle = async (row: SensitiveWord) => {
    if (!canUpdate || pendingToggle) return;
    setPendingToggle(row.id);
    try {
      await mutate(`/platform/sensitive-words/${row.id}/status`, "PATCH", { enabled: !row.enabled, version: row.version });
      await refresh();
    } catch (error) {
      void message.error(t(messageKeyFor(error, sensitiveErrorKeys)));
      if (error instanceof ApiError && error.status === 409) await listQuery.refetch();
    } finally {
      setPendingToggle(null);
    }
  };



  const importWords = (values: ImportValues) => {
    if (!canImport || pending || confirmingImport || csvParsing) return;
    const parsed = importSource === "csv"
      ? csvPreview ?? { words: [], issues: [{ line: null, key: "sensitiveImportCsvFileRequired" as MessageKey }], textChars: 0 }
      : validateSensitivePaste(values.words ?? "");
    const words = parsed.words.map((item) => item.word);
    const issues = [...parsed.issues];
    if (importSource === "csv" && csvFileError) issues.push({ line: null, key: csvFileError });
    setImportIssues(issues);
    if (issues.length > 0 || words.length === 0) return;
    const payload = { words, category: values.category, severity: values.severity, action: values.action };
    const payloadCheck = validateSensitiveImportPayload(payload);
    if (payloadCheck.issue) {
      setImportIssues([payloadCheck.issue]);
      return;
    }
    const sourceLabel = t(importSource === "csv" ? "sensitiveImportCsvSource" : "sensitiveImportPasteSource");
    setConfirmingImport(true);
    confirm({
      title: t("sensitiveImportConfirmTitle"),
      content: t("sensitiveImportConfirmBody", { source: sourceLabel, count: words.length, bytes: payloadCheck.bodyBytes }),
      okText: t("confirm"),
      cancelText: t("cancel"),
      onCancel: () => setConfirmingImport(false),
      onOk: async () => {
        setConfirmingImport(false);
        return importAction.run(async () => {
          const result = await mutate<{ imported: number; duplicated: number; invalid: number }>("/platform/sensitive-words/import", "POST", payload);
          setImportOpen(false);
          importForm.resetFields();
          setImportSource("paste");
          clearCsvSelection();
          await refresh();
          void message.success(t("sensitiveImportSummary", result));
        });
      },
    });
  };

  return <>
    <FeedbackBubble id="sensitive-category-options-error" content={categoriesQuery.error
      ? t(categoriesQuery.error instanceof ApiError && categoriesQuery.error.status === 403 ? "forbidden" : "unavailable")
      : undefined} />
    <ResourceTable<SensitiveWord>
      searchInitialValues={{ word: request.filters?.word, category: request.filters?.category, severity: request.filters?.severity, action: request.filters?.action, enabled: request.filters?.enabled }}
      result={listQuery.data}
      loading={listQuery.isFetching}
      error={listQuery.error}
      request={request}
      onChange={setRequest}
      onRetry={() => void listQuery.refetch()}
      actions={<Space wrap>
        {canCreate && <Button type="primary" onClick={openCreate}>{t("createSensitiveWord")}</Button>}
        {canImport && <Button onClick={openImport}>{t("importSensitiveWords")}</Button>}
      </Space>}
      columns={[
        { title: t("sensitiveWord"), dataIndex: "word", search: true },
        { title: t("sensitiveWordCategory"), dataIndex: "category", search: true, valueEnum: categoryValueEnum, render: (_, row) => categoryLabels[row.category] ?? row.category },
        { title: t("sensitiveWordSeverity"), dataIndex: "severity", search: true, valueEnum: Object.fromEntries(severities.map(value=>[value,{text:t(severityLabels[value])}])), render: (_, row) => <Tag color={severityColors[row.severity]}>{t(severityLabels[row.severity])}</Tag> },
        { title: t("sensitiveWordAction"), dataIndex: "action", search: true, valueEnum: { BLOCK: { text: t("sensitiveActionBlock") }, LOG_ONLY: { text: t("sensitiveActionLog") } }, render: (_, row) => row.action === "BLOCK" ? t("sensitiveActionBlock") : t("sensitiveActionLog") },
        { title: t("status"), key: "status", dataIndex: "enabled", search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
        { title: t("actions"), key: "actions", search: false, render: (_, row) => <TableActions wrap>
          <Button type="link" onClick={() => openEdit(row)}>{t(canUpdate ? "edit" : "details")}</Button>
          {canUpdate && <AsyncButton type="link" label={t(row.enabled ? "disableSensitiveWord" : "enableSensitiveWord")} pending={pendingToggle === row.id} disabled={pendingToggle !== null && pendingToggle !== row.id} onClick={() => void toggle(row)} />}
          {canDelete && <DeleteAction name={row.word} onDelete={async () => { await mutate(`/platform/sensitive-words/${row.id}?version=${row.version}`, "DELETE", {}); await refresh(); }} />}
        </TableActions> },
      ]}
    />

    <FeedbackBubble id="sensitive-word-editor-error" content={formError ? t(formError) : undefined}/>
    <FormDrawer open={selected !== undefined} title={t(selected ? "editSensitiveWord" : "createSensitiveWord")} pending={pending} formId="sensitive-word-editor" submitDisabled={!ready || (selected ? !canUpdate : !canCreate)} onClose={closeEditor}>
      {selected && detail.isFetching && <Spin size="small" />}
      {ready && <Form id="sensitive-word-editor" name="sensitive-word-editor" form={editorForm} layout="vertical" disabled={pending || (selected ? !canUpdate : !canCreate)} onFinish={(values) => void save(values)}>
        <Typography.Paragraph type="secondary">{t("sensitiveDailyFieldsHint")}</Typography.Paragraph>
        <Form.Item name="word" label={t("sensitiveWord")} rules={[
          { required: true, whitespace: true, max: 128, message: t("invalidSensitiveWord") },
          { validator: (_rule, value: string | undefined) => !value || !sensitiveWordValidationIssue(value)
            ? Promise.resolve() : Promise.reject(new Error(t("invalidSensitiveWord"))) },
        ]}><Input maxLength={128} /></Form.Item>
        <Form.Item name="category" label={t("sensitiveWordCategory")} rules={[{ required: true, message: t("invalidSensitiveWord") }]}><Select options={editorCategoryOptions(selected ? editingCategory : null)} showSearch optionFilterProp="label" /></Form.Item>
        <Collapse ghost items={[{ key: "advanced", label: t("sensitiveAdvancedSettings"), forceRender: true, children: <>
          <Form.Item name="severity" label={t("sensitiveWordSeverity")}><Select options={severities.map((value) => ({ value, label: t(severityLabels[value]) }))} /></Form.Item>
          <Form.Item name="action" label={t("sensitiveWordAction")} extra={t("sensitivePolicyHint")}><Select options={[{ value: "BLOCK", label: t("sensitiveActionBlock") }, { value: "LOG_ONLY", label: t("sensitiveActionLog") }]} /></Form.Item>
          <Form.Item name="enabled" label={t("status")}><Select options={[{ value: true, label: t("enabled") }, { value: false, label: t("disabled") }]} /></Form.Item>
          <Typography.Paragraph type="secondary">{t("sensitiveMatchSemantics")}</Typography.Paragraph>
        </> }]} />
      </Form>}
    </FormDrawer>
    <FormDrawer open={importOpen} title={t("importSensitiveWords")} pending={pending || confirmingImport || csvParsing} submitDisabled={importSource === "csv" && (!csvPreview || importIssues.length > 0 || !!csvFileError || csvParsing)} formId="sensitive-word-import" onClose={closeImport}>
      <Form id="sensitive-word-import" name="sensitive-word-import" form={importForm} layout="vertical" disabled={pending || confirmingImport || csvParsing || !canImport} onFinish={(values) => importWords(values)} initialValues={{ category: "IMPORTED", severity: "MEDIUM", action: "LOG_ONLY" }}>
        <Form.Item label={t("sensitiveImportSource")}>
          <Radio.Group value={importSource} optionType="button" buttonStyle="solid" disabled={pending || confirmingImport || csvParsing || !canImport} onChange={(event) => {
            const source = event.target.value as ImportSource;
            setImportSource(source);
            setImportIssues(source === "csv" ? csvPreview?.issues ?? [] : []);
          }} options={[
            { value: "paste", label: t("sensitiveImportPasteSource") },
            { value: "csv", label: t("sensitiveImportCsvSource") },
          ]} />
        </Form.Item>
        <Alert className="mb-3" type="info" showIcon message={t("sensitiveImportSelectedOnly", { source: t(importSource === "csv" ? "sensitiveImportCsvSource" : "sensitiveImportPasteSource") })} />
        {importSource === "paste" ? <Form.Item name="words" label={t("sensitiveWordList")} rules={[{ required: true, whitespace: true, message: t("invalidSensitiveImport") }]}><Input.TextArea rows={8} maxLength={SENSITIVE_IMPORT_LIMITS.textChars} placeholder={t("sensitiveWordListPlaceholder")} /></Form.Item> : <>
          <Form.Item label={t("sensitiveImportCsvChooseFile")} required>
            <input type="file" aria-label={t("sensitiveImportCsvChooseFile")} accept=".csv,text/csv" disabled={pending || confirmingImport || csvParsing || !canImport} onChange={(event) => {
              const file = event.currentTarget.files?.[0] ?? null;
              event.currentTarget.value = "";
              void loadCsvFile(file);
            }} />
          </Form.Item>
          {csvFile && <Alert className="mb-3" type="info" showIcon message={t("sensitiveImportCsvSelectedFile", { name: csvFile.name, bytes: csvFile.size })} />}
          {csvFileError && <Alert className="mb-3" type="error" showIcon message={t(csvFileError)} />}
          {csvParsing && <Spin size="small" />}
          {csvPreview && <>
            <Typography.Paragraph type="secondary">{t("sensitiveImportCsvPreviewCount", { count: csvPreview.words.length, chars: csvPreview.textChars })}</Typography.Paragraph>
            {csvPreview.words.length > 0 && <Table<SensitiveCsvParseResult["words"][number]>
              size="medium"
              rowKey={(row) => `${row.line}-${row.word}`}
              pagination={{ defaultPageSize: 10, showSizeChanger: true, pageSizeOptions: [10, 20, 50, 100] }}
              dataSource={csvPreview.words}
              columns={[
                { title: t("sensitiveImportCsvSourceLine"), dataIndex: "line", width: 120 },
                { title: t("sensitiveImportCsvPreviewWord"), dataIndex: "word" },
              ]}
            />}
          </>}
        </>}
        {importSource === "paste" && importIssues.length > 0 && <Table<SensitiveImportIssue & { rowKey: string }>
          size="medium"
          rowKey="rowKey"
          pagination={{ defaultPageSize: 10, showSizeChanger: true, pageSizeOptions: [10, 20, 50, 100] }}
          dataSource={importIssues.map((item, index) => ({ ...item, rowKey: `${item.line ?? "input"}-${item.key}-${index}` }))}
          columns={[
            { title: t("sensitiveImportInputRow"), dataIndex: "line", render: (line: number | null) => line ?? t("sensitiveImportInputLevel") },
            { title: t("sensitiveImportRowValues"), dataIndex: "cells", render: (cells?: string[]) => cells ? JSON.stringify(cells) : "" },
            { title: t("details"), dataIndex: "key", render: (key: MessageKey) => t(key) },
          ]}
        />}
        {importSource === "csv" && importIssues.length > 0 && <>
          <Typography.Title level={5}>{t("sensitiveImportCsvErrorRows")}</Typography.Title>
          <Table<SensitiveImportIssue & { rowKey: string }>
            size="medium"
            rowKey="rowKey"
            pagination={{ defaultPageSize: 10, showSizeChanger: true, pageSizeOptions: [10, 20, 50, 100] }}
            dataSource={importIssues.map((item, index) => ({ ...item, rowKey: `${item.line ?? "file"}-${item.key}-${index}` }))}
            columns={[
              { title: t("sensitiveImportCsvSourceLine"), dataIndex: "line", render: (line: number | null) => line ?? t("sensitiveImportCsvFileLevel") },
              { title: t("sensitiveImportRowValues"), dataIndex: "cells", render: (cells?: string[]) => cells ? JSON.stringify(cells) : "" },
              { title: t("details"), dataIndex: "key", render: (key: MessageKey) => t(key) },
            ]}
          />
        </>}
        <Form.Item name="category" label={t("sensitiveWordCategory")} rules={[{ required: true, message: t("invalidSensitiveWord") }]}><Select options={editorCategoryOptions()} showSearch optionFilterProp="label" /></Form.Item>
        <Collapse ghost items={[{ key: "advanced", label: t("sensitiveAdvancedSettings"), forceRender: true, children: <>
          <Form.Item name="severity" label={t("sensitiveWordSeverity")}><Select options={severities.map((value) => ({ value, label: t(severityLabels[value]) }))} /></Form.Item>
          <Form.Item name="action" label={t("sensitiveWordAction")} extra={t("sensitivePolicyHint")}><Select options={[{ value: "BLOCK", label: t("sensitiveActionBlock") }, { value: "LOG_ONLY", label: t("sensitiveActionLog") }]} /></Form.Item>
        </> }]} />
      </Form>
    </FormDrawer>
  </>;
}
