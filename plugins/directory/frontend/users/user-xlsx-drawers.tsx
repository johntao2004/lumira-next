"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Radio, Select, Space, Spin, Table, Tag, Transfer, Typography, Upload, Input } from "antd";
import type { UploadFile, UploadProps } from "antd";
import { LeftOutlined, RightOutlined, UploadOutlined } from "@ant-design/icons";
import { AsyncButton } from "@/platform/components/async-button";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { downloadBlob } from "@/platform/files/download-blob";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { isRolePreviewReadOnly, sessionMe } from "@/platform/api/client";
import type { ListRequest } from "@/platform/api/pagination";
import type { CurrentUser } from "@/platform/auth/api-types";
import { userFieldLabel } from "@/platform/templates/field-labels";
import { commitUserImport, exportUsersXlsx, getExportFields, getImportFields, getUserImportErrors, getUserImportTask, getUserImportTemplate, inspectImportColumns, previewUserImport } from "@/platform/templates/api";
import type { ExportField, ImportColumn, ImportField, ImportPreview, ImportPreviewRow, ImportTask } from "@/platform/templates/types";
import "./user-xlsx-drawers.css";
import { importDelegationErrorKeys, importRowErrorKeys, importTaskErrorKeys } from "./user-import-errors";
import { automaticImportMapping } from "./user-import-mapping";

type ExportScope = "currentPage" | "selected" | "filtered";
type ImportMode = "create" | "update" | "upsert";
type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;
const transferErrorKeys = [...importDelegationErrorKeys, "unsupportedFile", "documentFileTooLarge", "forbidden", "fieldNotReadable", "importPreviewExpired", "importVersionConflict", "idempotencyKeyConflict", "batchLimitExceeded", "staleRecord", "unavailable"] as const;

function importRowErrorLabel(code: string, fields: ImportField[], t: Translate) {
  if (code.startsWith("requiredFieldMissing:")) {
    const fieldKey = code.slice("requiredFieldMissing:".length);
    const field = fields.find((candidate) => candidate.key === fieldKey);
    const fieldLabel = field ? userFieldLabel(field, (key, params) => t(key as MessageKey, params), "import") : null;
    return fieldLabel ? t("userImportRowRequiredFieldMissing", { field: fieldLabel }) : t("userImportRowUnknownError");
  }
  return t(importRowErrorKeys[code] ?? "userImportRowUnknownError");
}

function importTaskErrorKey(code: string | null | undefined): MessageKey {
  return code ? importTaskErrorKeys[code] ?? "userImportTaskFailedMessage" : "userImportTaskFailedMessage";
}

function displayValue(value: unknown) {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

export function UserExportDrawer({ open, onClose, request, currentPageIds, selectedIds }: {
  open: boolean;
  onClose: () => void;
  request: ListRequest;
  currentPageIds: string[];
  selectedIds: string[];
}) {
  const { t } = usePreferences();
  const operationSignal = useSessionScope();
  const [scope, setScope] = useState<ExportScope>(selectedIds.length ? "selected" : "currentPage");
  const [fields, setFields] = useState<string[]>([]);
  const fieldsInitialized = useRef(false);
  const fieldsQuery = useQuery({
    queryKey: ["user-export-fields"],
    enabled: open,
    queryFn: ({ signal }) => getExportFields(signal),
    retry: false,
  });
  const { pending, error, run, setError } = useSubmitAction({ errorKeys: transferErrorKeys, fallback: "unavailable" });
  const clearError = useRef(setError);
  clearError.current = setError;

  useEffect(() => {
    if (!open) {
      setScope(selectedIds.length ? "selected" : "currentPage");
      setFields([]);
      fieldsInitialized.current = false;
      clearError.current(null);
      return;
    }
    if (!fieldsInitialized.current && fieldsQuery.data) {
      setFields(fieldsQuery.data.filter((field) => field.readable).map((field) => field.key));
      fieldsInitialized.current = true;
    }
  }, [fieldsQuery.data, open, selectedIds.length]);

  const exportFile = async () => {
    if (pending || fieldsQuery.isFetching || fields.length === 0 || (scope === "selected" && selectedIds.length === 0)) return;
    const signal = operationSignal();
    await run(async () => {
      const file = await exportUsersXlsx({
        scope,
        page: request.page,
        pageSize: request.pageSize,
        search: request.search,
        userIds: scope === "selected" ? selectedIds : scope === "currentPage" ? currentPageIds : [],
        filters: request.filters ?? {},
        filterModes: request.filterModes ?? {},
        fields,
        statusLabels: { true: t("enabled"), false: t("disabled") },
        fieldLabels: Object.fromEntries((fieldsQuery.data ?? [])
          .filter(field => field.readable && fields.includes(field.key))
          .map(field => [field.key, userFieldLabel(field, t)])),
      }, signal);
      if (signal.aborted) return false;
      downloadBlob(file, "users.xlsx");
      onClose();
      return true;
    });
  };

  const fieldOptions = (fieldsQuery.data ?? []).filter((field) => field.readable);

  return <PlatformDrawer
    open={open}
    title={t("userExportTitle")}
    placement="right"
    width={840}
    onClose={() => { if (!pending) onClose(); }}
    closable={{ disabled: pending }}
    keyboard={!pending}
    destroyOnHidden
    footer={<Space wrap>
      <Button disabled={pending} onClick={onClose}>{t("cancel")}</Button>
      <AsyncButton type="primary" pending={pending} disabled={fieldsQuery.isFetching || !fields.length || (scope === "selected" && !selectedIds.length)} label={t("userExportXlsx")} onClick={() => void exportFile()} />
    </Space>}
  >
    <div className="user-xlsx-drawer">
      {fieldsQuery.error && <Alert type="error" showIcon title={t("unavailable")} action={<Button type="link" onClick={() => void fieldsQuery.refetch()}>{t("retry")}</Button>} />}
      <div className="user-xlsx-field">
        <Typography.Text strong>{t("userExportRange")}</Typography.Text>
        <Radio.Group value={scope} disabled={pending} onChange={(event) => setScope(event.target.value)}>
          <Space direction="vertical">
            <Radio value="currentPage">{t("userExportCurrentPage", { count: currentPageIds.length })}</Radio>
            <Radio value="selected" disabled={selectedIds.length === 0}>{t("userExportSelected", { count: selectedIds.length })}</Radio>
            <Radio value="filtered">{t("userExportFilteredAllPages")}</Radio>
          </Space>
        </Radio.Group>
      </div>
      <div className="user-xlsx-field">
        <Typography.Text strong>{t("userExportFields")}</Typography.Text>
        <Spin spinning={fieldsQuery.isFetching}>
          <Transfer
            className="user-export-field-transfer"
            dataSource={fieldOptions}
            targetKeys={fields}
            disabled={pending || fieldsQuery.isFetching || !fieldOptions.length}
            titles={[t("userExportAvailableFields"), t("userExportSelectedFields")]}
            actions={[
              <Button key="move-to-selected" type="primary" size="small" aria-label={t("userExportMoveToSelected")} title={t("userExportMoveToSelected")} icon={<RightOutlined />} />,
              <Button key="move-to-available" type="primary" size="small" aria-label={t("userExportMoveToAvailable")} title={t("userExportMoveToAvailable")} icon={<LeftOutlined />} />,
            ]}
            showSearch={{ placeholder: t("search") }}
            render={(field) => userFieldLabel(field, t)}
            onChange={(targetKeys) => setFields(targetKeys.map(String))}
          />
        </Spin>
      </div>
      {error && <Alert type="error" showIcon title={t(error)} action={<Button type="link" onClick={() => void exportFile()}>{t("retry")}</Button>} />}
    </div>
  </PlatformDrawer>;
}

export function UserImportDrawer({ open, onClose, onImported }: {
  open: boolean;
  onClose: () => void;
  onImported: () => Promise<void>;
}) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const onImportedRef = useRef(onImported);
  onImportedRef.current = onImported;
  const operationSignal = useSessionScope();
  const session = useQuery({
    queryKey: ["session"],
    queryFn: ({ signal }) => sessionMe<CurrentUser>("workspace-session", signal),
  });
  const currentUserId = session.data?.userId;
  const lastTaskStorageKey = currentUserId ? `lumira:last-user-import-task:${currentUserId}` : null;
  const previewReadOnly = isRolePreviewReadOnly();
  const [file, setFile] = useState<File | null>(null);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [columns, setColumns] = useState<ImportColumn[]>([]);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [mode, setMode] = useState<ImportMode>("upsert");
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [commitKey, setCommitKey] = useState<string | null>(null);
  const [lookupTaskId, setLookupTaskId] = useState("");
  const [taskSearch, setTaskSearch] = useState("");
  const fieldsQuery = useQuery({
    queryKey: ["user-import-fields"],
    enabled: open,
    queryFn: ({ signal }) => getImportFields(signal),
    retry: false,
  });
  const taskQuery = useQuery({
    queryKey: ["user-import-task", currentUserId, lookupTaskId],
    enabled: open && Boolean(currentUserId && lookupTaskId),
    queryFn: ({ signal }) => getUserImportTask(lookupTaskId, signal),
    retry: false,
    refetchInterval: (query) => query.state.data?.status === "running" ? 3_000 : false,
  });
  const transferActionOptions = { errorKeys: transferErrorKeys, errorAliases: { fileTooLarge: "documentFileTooLarge" as const }, fallback: "unavailable" as const };
  const columnsAction = useSubmitAction(transferActionOptions);
  const previewAction = useSubmitAction(transferActionOptions);
  const commitAction = useSubmitAction(transferActionOptions);
  const taskAction = useSubmitAction(transferActionOptions);
  const downloadAction = useSubmitAction(transferActionOptions);
  const pending = columnsAction.pending || previewAction.pending || commitAction.pending || downloadAction.pending;
  const commitErrorRef = useRef(commitAction.setError);
  commitErrorRef.current = commitAction.setError;
  const writableFields = fieldsQuery.data?.filter((field) => field.writable) ?? [];
  const hasErrors = Boolean(preview?.rows.some((row) => row.action === "invalid" || row.errors.length > 0));
  const taskResult = taskQuery.data;
  const pendingCompletionTaskId = useRef<string | null>(null);

  const reset = () => {
    setFile(null);
    setFileList([]);
    setColumns([]);
    setMapping({});
    setMode("upsert");
    setPreview(null);
    setCommitKey(null);
    setLookupTaskId("");
    setTaskSearch("");
    columnsAction.setError(null);
    previewAction.setError(null);
    commitAction.setError(null);
    taskAction.setError(null);
    downloadAction.setError(null);
  };

  useEffect(() => {
    if (!open) reset();
    // Reset is intentionally a close-boundary operation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    if (!open) return;
    if (!lastTaskStorageKey) {
      setLookupTaskId("");
      setTaskSearch("");
      return;
    }
    try {
      const savedTaskId = sessionStorage.getItem(lastTaskStorageKey) ?? "";
      setLookupTaskId(savedTaskId);
      setTaskSearch(savedTaskId);
    } catch {
      setLookupTaskId("");
      setTaskSearch("");
    }
  }, [open, lastTaskStorageKey]);

  useEffect(() => {
    if (!taskResult || taskResult.status !== "completed" || pendingCompletionTaskId.current !== taskResult.taskId) return;
    pendingCompletionTaskId.current = null;
    void Promise.all([
      client.invalidateQueries({ queryKey: ["users"] }, { throwOnError: true }),
      onImportedRef.current(),
    ]).catch(() => commitErrorRef.current("unavailable"));
  }, [client, taskResult]);

  const beforeUpload: UploadProps["beforeUpload"] = (selected) => {
    const extension = selected.name.split(".").pop()?.toLowerCase();
    if (extension !== "xlsx") {
      columnsAction.setError("unsupportedFile");
      setFile(null);
      setFileList([]);
      setColumns([]);
      setMapping({});
      setPreview(null);
      return Upload.LIST_IGNORE;
    }
    if (selected.size > 10 * 1024 * 1024) {
      columnsAction.setError("documentFileTooLarge");
      setFile(null);
      setFileList([]);
      setColumns([]);
      setMapping({});
      setPreview(null);
      return Upload.LIST_IGNORE;
    }
    setFile(selected);
    setColumns([]);
    setMapping({});
    setPreview(null);
    setCommitKey(null);
    setLookupTaskId("");
    setTaskSearch("");
    columnsAction.setError(null);
    previewAction.setError(null);
    commitAction.setError(null);
    return false;
  };

  const inspectColumns = async () => {
    if (!file || pending) return;
    const signal = operationSignal();
    await columnsAction.run(async () => {
      const response = await inspectImportColumns(file, signal);
      if (signal.aborted) return false;
      setColumns(response.columns);
      setMapping(automaticImportMapping(response.columns, writableFields, (field) => userFieldLabel(field, t, "import")));
      setPreview(null);
      setCommitKey(null);
      return true;
    });
  };

  const mappingIsValid = Object.keys(mapping).length > 0 && Object.values(mapping).includes("username") && new Set(Object.values(mapping)).size === Object.values(mapping).length;

  const inspectRows = async () => {
    if (!file || !mappingIsValid || pending) return;
    const signal = operationSignal();
    await previewAction.run(async () => {
      const response = await previewUserImport(file, mapping, mode, signal);
      if (signal.aborted) return false;
      setPreview(response);
      setCommitKey(globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`);
      setLookupTaskId("");
      setTaskSearch("");
      commitAction.setError(null);
      taskAction.setError(null);
      return true;
    });
  };

  const commit = async () => {
    if (!preview || hasErrors || pending || !commitKey) return;
    const signal = operationSignal();
    const currentPreview = preview;
    await commitAction.run(async () => {
      const task = await commitUserImport(currentPreview.previewId, currentPreview.previewVersion, commitKey, signal);
      if (signal.aborted || currentPreview !== preview) return false;
      if (lastTaskStorageKey) {
        try { sessionStorage.setItem(lastTaskStorageKey, task.taskId); } catch { /* Task lookup remains available for this session. */ }
      }
      pendingCompletionTaskId.current = task.status === "failed" ? null : task.taskId;
      setLookupTaskId(task.taskId);
      setTaskSearch(task.taskId);
      if (task.status === "failed") {
        commitAction.setError("userImportTaskFailedMessage");
      }
      return true;
    });
  };

  const lookupTask = async () => {
    const taskId = taskSearch.trim();
    if (!taskId || taskAction.isPending()) return;
    const signal = operationSignal();
    await taskAction.run(async () => {
      if (lastTaskStorageKey) {
        try { sessionStorage.setItem(lastTaskStorageKey, taskId); } catch { /* Task lookup remains available for this session. */ }
      }
      setLookupTaskId(taskId);
      return !signal.aborted;
    });
  };

  const downloadFile = async (kind: "template" | "errors") => {
    if (downloadAction.isPending()) return;
    const signal = operationSignal();
    await downloadAction.run(async () => {
      const blob = kind === "template"
        ? await getUserImportTemplate(signal)
        : preview ? await getUserImportErrors(preview.previewId, signal) : null;
      if (!blob || signal.aborted) return false;
      downloadBlob(blob, kind === "template" ? "user-import-template.xlsx" : "user-import-errors.xlsx");
      return true;
    });
  };

  const stageLabel = preview ? t("userImportStagePreview") : columns.length ? t("userImportStageMapping") : t("userImportStageFile");
  const primaryLabel = preview ? t("userImportConfirm") : columns.length ? t("userImportPreview") : t("userImportReadColumns");
  const primaryAction = preview ? commit : columns.length ? inspectRows : inspectColumns;
  const primaryDisabled = preview ? hasErrors || Boolean(taskResult) : columns.length ? !mappingIsValid : !file;

  const renderError = (errorKey: MessageKey | null, retry: () => void) => errorKey && <Alert type="error" showIcon title={t(errorKey)} action={<Button type="link" onClick={retry}>{t("retry")}</Button>} />;

  return <PlatformDrawer
    open={open}
    title={t("userImportTitle")}
    placement="right"
    width={1000}
    onClose={() => { if (!pending) onClose(); }}
    closable={{ disabled: pending }}
    keyboard={!pending}
    destroyOnHidden
    footer={<Space wrap>
      <Button disabled={pending} onClick={onClose}>{t("cancel")}</Button>
      <AsyncButton type="primary" pending={pending} disabled={primaryDisabled || previewReadOnly} label={primaryLabel} onClick={() => void primaryAction()} />
    </Space>}
  >
    <div className="user-import-drawer">
      <div className="user-import-drawer__stage">
        <Typography.Text strong>{stageLabel}</Typography.Text>
        <Typography.Text type="secondary">{t("userImportLimits")}</Typography.Text>
        <AsyncButton pending={downloadAction.pending} disabled={previewReadOnly} label={t("userImportDownloadTemplate")} onClick={() => void downloadFile("template")} />
      </div>
      {pending && <Alert type="info" showIcon title={t("userImportSubmitting")} />}
      {fieldsQuery.error && <Alert type="error" showIcon title={t("unavailable")} action={<Button type="link" onClick={() => void fieldsQuery.refetch()}>{t("retry")}</Button>} />}
      <div className="user-xlsx-field">
        <Typography.Text strong>{t("userImportMode")}</Typography.Text>
        <Radio.Group value={mode} disabled={pending || Boolean(preview) || previewReadOnly} onChange={(event) => { setMode(event.target.value as ImportMode); setPreview(null); setCommitKey(null); }}>
          <Radio value="create">{t("userImportCreateOnly")}</Radio>
          <Radio value="update">{t("userImportUpdateOnly")}</Radio>
          <Radio value="upsert">{t("userImportCreateUpdate")}</Radio>
        </Radio.Group>
      </div>
      <div className="user-xlsx-field">
        <Typography.Text strong>{t("userImportFile")}</Typography.Text>
        <Upload accept=".xlsx" maxCount={1} fileList={fileList} disabled={pending || previewReadOnly} beforeUpload={beforeUpload} onChange={({ fileList: next }) => setFileList(next.slice(-1))} onRemove={() => { setFile(null); setFileList([]); setColumns([]); setMapping({}); setPreview(null); return true; }}>
          <Button icon={<UploadOutlined />}>{t("uploadFile")}</Button>
        </Upload>
        {file && <Typography.Text type="secondary">{file.name} · {(file.size / (1024 * 1024)).toFixed(2)} MiB</Typography.Text>}
      </div>
      {renderError(columnsAction.error, () => void inspectColumns())}
      {columns.length > 0 && <section className="user-import-mapping">
        <div className="user-import-mapping__heading">
          <Typography.Title level={5}>{t("userImportMapping")}</Typography.Title>
          <Typography.Text type="secondary">{t("userImportUsernameRequired")}</Typography.Text>
        </div>
        {columns.map((column) => {
          const selected = mapping[column.name];
          return <div className="user-import-mapping__row" key={`${column.index}-${column.name}`}>
            <div><Typography.Text strong>{column.name}</Typography.Text><Typography.Text type="secondary">{t("userImportColumnNumber", { number: column.index + 1 })}</Typography.Text></div>
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              value={selected}
              disabled={pending || Boolean(preview)}
              placeholder={t("userImportIgnoreColumn")}
              options={writableFields.map((field) => ({ value: field.key, label: userFieldLabel(field, t, "import"), disabled: Object.values(mapping).includes(field.key) && selected !== field.key }))}
              onChange={(fieldKey: string | undefined) => {
                setMapping((previous) => {
                  const next = { ...previous };
                  if (fieldKey) next[column.name] = fieldKey;
                  else delete next[column.name];
                  return next;
                });
                setPreview(null);
                setCommitKey(null);
              }}
            />
          </div>;
        })}
        {!mappingIsValid && <Alert type="warning" showIcon title={t("userImportMappingRequiresUsername")} />}
      </section>}
      {renderError(previewAction.error, () => void inspectRows())}
      {preview && <section className="user-import-preview">
        <div className="user-import-preview__summary">
          <Typography.Title level={5}>{t("userImportPreviewTitle")}</Typography.Title>
          <Space wrap>
            <Tag color="success">{t("userImportCreateCount", { count: preview.rows.filter((row) => row.action === "create").length })}</Tag>
            <Tag color="processing">{t("userImportUpdateCount", { count: preview.rows.filter((row) => row.action === "update").length })}</Tag>
            <Tag color={hasErrors ? "error" : "default"}>{t("userImportErrorCount", { count: preview.rows.filter((row) => row.action === "invalid" || row.errors.length > 0).length })}</Tag>
            <Button type="link" disabled={pending} onClick={() => { setPreview(null); setCommitKey(null); }}>{t("userImportEditMapping")}</Button>
          </Space>
        </div>
        <Typography.Text type="secondary">{t("userImportPreviewExpires", { time: new Date(preview.expiresAt).toLocaleString() })}</Typography.Text>
      {hasErrors && <Alert type="error" showIcon title={t("userImportErrorsBlockCommit")} action={<AsyncButton pending={downloadAction.pending} disabled={previewReadOnly} label={t("userImportDownloadErrors")} onClick={() => void downloadFile("errors")} />} />}
        <Table<ImportPreviewRow>
          size="small"
          rowKey="rowNumber"
          dataSource={preview.rows}
          scroll={{ x: "max-content" }}
          pagination={{ pageSize: 10, showSizeChanger: true }}
          columns={[
            { title: "#", dataIndex: "rowNumber", width: 70 },
            { title: t("userImportAction"), dataIndex: "action", width: 120, render: (action: ImportPreviewRow["action"]) => <Tag color={action === "create" ? "success" : action === "update" ? "processing" : "error"}>{t(action === "create" ? "userImportCreateAction" : action === "update" ? "userImportUpdateAction" : "userImportInvalidAction")}</Tag> },
            { title: t("userImportChanges"), dataIndex: "changes", render: (_: unknown, row) => <div className="user-import-change-list">{Object.entries(row.changes ?? {}).map(([key, change]) => {
              const field = writableFields.find((item) => item.key === key);
              return <div key={key}><Typography.Text strong>{field ? userFieldLabel(field, t, "import") : t("unavailable")}</Typography.Text>: {displayValue(change.before)} → {displayValue(change.after)}</div>;
            })}</div> },
            { title: t("userImportErrors"), dataIndex: "errors", render: (errors: string[]) => errors?.length ? <ul className="user-import-errors">{errors.map((code, index) => <li key={`${code}-${index}`}>{importRowErrorLabel(code, writableFields, t)}</li>)}</ul> : "—" },
          ]}
        />
      </section>}
      {renderError(commitAction.error, () => {
          if (commitAction.error === "importPreviewExpired" || commitAction.error === "importVersionConflict" || commitAction.error === "userImportTaskFailedMessage") {
            setPreview(null);
            setCommitKey(null);
            commitAction.setError(null);
            void inspectRows();
          } else void commit();
        })}
      {renderError(downloadAction.error, () => void downloadFile(preview && hasErrors ? "errors" : "template"))}
      {taskResult && <Alert
        type={taskResult.status === "completed" ? "success" : taskResult.status === "running" ? "info" : "error"}
        showIcon
        title={t(taskResult.status === "completed" ? "userImportTaskComplete" : taskResult.status === "running" ? "userImportTaskRunningMessage" : importTaskErrorKey(taskResult.errorCode), { taskId: taskResult.taskId, created: taskResult.created, updated: taskResult.updated, failed: taskResult.failed })}
        description={<Typography.Text copyable>{taskResult.taskId}</Typography.Text>}
      />}
      {taskQuery.error && <Alert type="error" showIcon title={t("userImportTaskUnavailable")} action={<Button type="link" onClick={() => void taskQuery.refetch()}>{t("retry")}</Button>} />}
      <section className="user-import-task-lookup">
        <Typography.Text strong>{t("userImportFindTask")}</Typography.Text>
        <Space.Compact block>
          <Input value={taskSearch} disabled={taskAction.pending} placeholder={t("userImportTaskId")} onChange={(event) => setTaskSearch(event.target.value)} />
          <AsyncButton pending={taskAction.pending} disabled={!taskSearch.trim() || previewReadOnly} label={t("search")} onClick={() => void lookupTask()} />
        </Space.Compact>
        {renderError(taskAction.error, () => void lookupTask())}
        {taskResult && <div className="user-import-task-result">
          <Space wrap>
            <Typography.Text strong>{t("userImportTaskStatus")}: {t(taskResult.status === "completed" ? "userImportTaskCompleted" : taskResult.status === "running" ? "userImportTaskRunning" : "userImportTaskFailed")}</Typography.Text>
            {taskResult.status === "failed" && <Button disabled={pending || !file || !mappingIsValid} onClick={() => { setPreview(null); setCommitKey(null); void inspectRows(); }}>{t("userImportPreview")}</Button>}
          </Space>
          <Table<ImportTask["rows"][number]>
            size="small"
            rowKey="rowNumber"
            dataSource={taskResult.rows}
            scroll={{ x: "max-content" }}
            pagination={{ pageSize: 10 }}
            columns={[
              { title: "#", dataIndex: "rowNumber", width: 70 },
              { title: t("username"), dataIndex: "username" },
              { title: t("userImportAction"), dataIndex: "action", render: (action: ImportTask["rows"][number]["action"]) => t(action === "create" ? "userImportCreateAction" : action === "update" ? "userImportUpdateAction" : "userImportInvalidAction") },
              { title: t("userImportErrors"), dataIndex: "errors", render: (errors: string[]) => errors?.map((code) => importRowErrorLabel(code, writableFields, t)).join("; ") || "—" },
            ]}
          />
        </div>}
      </section>
    </div>
  </PlatformDrawer>;
}
