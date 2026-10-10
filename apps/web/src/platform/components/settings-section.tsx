"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { AsyncButton } from "./async-button";
import { useRef, useState } from "react";
import { Button, Drawer, Modal, Popconfirm, Space, Spin, Table, Typography } from "antd";
import type { FormInstance, FormProps, TableColumnsType } from "antd";
import { ProCard } from "@ant-design/pro-components";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, mutate } from "../api/client";
import { usePreferences } from "../providers";
import type { MessageKey } from "../i18n/messages";
import { FeedbackBubble } from "./feedback-bubble";
import { QueryFailure } from "./query-failure";
import { tableActionColumn } from "./table-actions";
import { DEFAULT_TABLE_PAGE_SIZE, listRequestParams, TABLE_PAGE_SIZE_OPTIONS, type PageResult } from "../api/pagination";
import { formatDateTime } from "../i18n/format";
import { useSubmitAction } from "../forms/use-submit-action";
import { useSessionScope } from "../auth/use-session-scope";

const settingsErrorKeys = ["staleRecord", "settingRevisionNotFound", "invalidSettingRevision", "invalidChangeReason", "forbidden", "invalidBranding", "invalidAgreements", "invalidWatermark", "invalidMaintenance", "invalidSecurity", "invalidPasskeySettings", "invalidProfileFields", "invalidSmtp", "invalidSmtpCredentials", "smtpPasswordRequired", "smtpPasswordReplacementRequired", "invalidSms", "invalidSmsCredentials", "smsCredentialRequired", "smsCredentialReplacementRequired", "secretKeyUnavailable", "invalidFileRetention"] as const satisfies readonly MessageKey[];

export type Snapshot<T> = { data: T; version: number; [key: string]: unknown };
type Extras<T> = { initialExtras?: Record<string, unknown>; renderExtras?: (snapshot: Snapshot<T>) => React.ReactNode };
type SubmissionTransform<T> = (input: {
  submitted: Record<string, unknown>;
  formValues: Record<string, unknown>;
  form: FormInstance;
  snapshot: Snapshot<T>;
}) => Record<string, unknown>;
type SettingRevision = { version: number; changedBy: string; changeReason: string; createdAt: string; sourceVersion: number | null };
type RevisionDetail<T> = SettingRevision & { data: T };
type SettingsAction = "save" | "reload" | "restore";
type RunSettingsAction = (kind: SettingsAction, operation: () => Promise<void | boolean>, signal?: AbortSignal) => Promise<boolean>;

const SYSTEM_SAVE_REASON = "System-generated record: platform settings saved.";
const SYSTEM_RESTORE_REASON_PREFIX = "System-generated record: platform settings restored from revision ";

export function SettingsSection<T extends object>({ section, canWrite, className, formName, showSecondaryActions = true, saveBlocked = false, confirmBeforeSave, prepareSubmission, onFinishFailed, children, ...extras }: { section: string; canWrite: boolean; className?: string; formName?: string; showSecondaryActions?: boolean; saveBlocked?: boolean; confirmBeforeSave?: (values: Record<string, unknown>) => Promise<boolean>; prepareSubmission?: SubmissionTransform<T>; onFinishFailed?: FormProps["onFinishFailed"]; children: React.ReactNode } & Extras<T>) {
  const { t } = usePreferences();
  const query = useQuery({ queryKey: ["settings", section], queryFn: ({ signal }) => api<Snapshot<T>>(`/platform/settings/${section}`, { signal }) });
  return <ProCard className={["platform-settings-card", className].filter(Boolean).join(" ")}>
    {!query.data && (query.error ? <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} /> : <Spin />)}
    {query.data && <SectionEditor<T> key={section} section={section} initial={query.data} canWrite={canWrite} formName={formName} showSecondaryActions={showSecondaryActions} saveBlocked={saveBlocked} confirmBeforeSave={confirmBeforeSave} prepareSubmission={prepareSubmission} onFinishFailed={onFinishFailed} {...extras}>{children}</SectionEditor>}
  </ProCard>;
}

function SectionEditor<T extends object>({ section, initial, canWrite, formName, showSecondaryActions, saveBlocked, confirmBeforeSave, prepareSubmission, onFinishFailed, children, initialExtras, renderExtras }: { section: string; initial: Snapshot<T>; canWrite: boolean; formName?: string; showSecondaryActions: boolean; saveBlocked: boolean; confirmBeforeSave?: (values: Record<string, unknown>) => Promise<boolean>; prepareSubmission?: SubmissionTransform<T>; onFinishFailed?: FormProps["onFinishFailed"]; children: React.ReactNode } & Extras<T>) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const operationSignal = useSessionScope();
  const [form] = Form.useForm();
  const [snapshot, setSnapshot] = useState(initial);
  const [error, setError] = useState<MessageKey | null>(null);
  const [restoreError, setRestoreError] = useState<MessageKey | null>(null);
  const [saved, setSaved] = useState(false);
  const operationKind = useRef<SettingsAction | null>(null);
  const actionSignal = useRef<AbortSignal | null>(null);
  const action = useSubmitAction({
    errorKeys: settingsErrorKeys,
    fallback: "unavailable",
    onError: key => {
      if (actionSignal.current?.aborted) return;
      if (operationKind.current === "restore") setRestoreError(key);
      else setError(key);
    },
  });
  const pending = action.pending;
  const setEditorValues = (data: T) => form.setFieldsValue({ ...initialExtras, data });

  async function runSettingsAction(kind: SettingsAction, operation: () => Promise<void | boolean>, signal?: AbortSignal) {
    // Check the hook's synchronous guard before setting the error destination.
    // The shared run then owns one lock across save, reload, and restore.
    if (action.isPending()) return false;
    operationKind.current = kind;
    actionSignal.current = signal ?? null;
    setError(null);
    setRestoreError(null);
    try {
      return await action.run(async () => {
        if (signal?.aborted) return false;
        try {
          return await operation();
        } catch (error) {
          if (signal?.aborted) return false;
          throw error;
        }
      });
    } finally {
      if (actionSignal.current === signal) actionSignal.current = null;
      operationKind.current = null;
    }
  }

  async function applySnapshot(result: Snapshot<T>, wasSaved: boolean, signal?: AbortSignal) {
    signal?.throwIfAborted();
    form.resetFields();
    setEditorValues(result.data);
    setSnapshot(result);
    client.setQueryData(["settings", section], result);
    setError(null);
    await client.invalidateQueries({ queryKey: [`public-${section}`] });
    if (showSecondaryActions) await client.invalidateQueries({ queryKey: ["setting-history", section] });
    signal?.throwIfAborted();
    setSaved(wasSaved);
  }

  async function save(values: Record<string, unknown>) {
    const signal = operationSignal();
    if (!canWrite || saveBlocked || signal.aborted) return;
    await runSettingsAction("save", async () => {
      signal.throwIfAborted();
      setSaved(false);
      const formValues = form.getFieldsValue(true) as Record<string, unknown>;
      const completeValues = prepareSubmission?.({ submitted: values, formValues, form, snapshot }) ?? values;
      if (confirmBeforeSave && !(await confirmBeforeSave(completeValues))) return false;
      if (signal.aborted) return false;
      // ProForm omits fields that are not currently mounted, such as the watermark
      // image URL while text mode is selected. Preserve the current snapshot values.
      const submitted = { ...completeValues };
      delete submitted.changeReason;
      const result = await mutate<Snapshot<T>>(`/platform/settings/${section}`, "PUT", {
        ...submitted,
        data: { ...snapshot.data, ...((completeValues.data ?? {}) as Partial<T>) },
        version: snapshot.version,
      }, signal);
      signal.throwIfAborted();
      await applySnapshot(result, true, signal);
    }, signal);
  }

  return <>
    <Form name={formName} className="settings-form" form={form} layout="vertical" initialValues={{ ...initialExtras, data: initial.data }} disabled={!canWrite || pending} onValuesChange={() => setSaved(false)} onFinish={save} onFinishFailed={onFinishFailed}>
      {children}
      {renderExtras?.(snapshot)}
      <FeedbackBubble id={`settings-${section}-error`} content={error ? t(error) : undefined} />
      <FeedbackBubble kind="success" id={`settings-${section}-saved`} content={saved ? t("settingsSaved") : undefined} />
      <Space className="form-actions" size={8} wrap>
        <AsyncButton htmlType="submit" type="primary" pending={pending} disabled={!canWrite || saveBlocked} label={t("save")} />
        {showSecondaryActions && <SettingsSecondaryActions<T>
          section={section} snapshot={snapshot} canWrite={canWrite} pending={pending || saveBlocked}
          restoreError={restoreError} setRestoreError={setRestoreError} setSaved={setSaved}
          runSettingsAction={runSettingsAction} isBusy={action.isPending} onSnapshot={applySnapshot}
          initialExtras={initialExtras} renderExtras={renderExtras}
        />}
      </Space>
    </Form>
  </>;
}

function SettingsSecondaryActions<T extends object>({ section, snapshot, canWrite, pending, restoreError, setRestoreError, setSaved, runSettingsAction, isBusy, onSnapshot, initialExtras, renderExtras }: {
  section: string; snapshot: Snapshot<T>; canWrite: boolean; pending: boolean;
  restoreError: MessageKey | null; setRestoreError: (value: MessageKey | null) => void;
  setSaved: (value: boolean) => void; runSettingsAction: RunSettingsAction; isBusy: () => boolean;
  onSnapshot: (result: Snapshot<T>, wasSaved: boolean, signal?: AbortSignal) => Promise<void>;
} & Extras<T>) {
  const { t, locale } = usePreferences();
  const operationSignal = useSessionScope();
  const [restoreForm] = Form.useForm();
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyPage, setHistoryPage] = useState(1);
  const [historyPageSize, setHistoryPageSize] = useState(DEFAULT_TABLE_PAGE_SIZE);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const historyQuery = useQuery({
    queryKey: ["setting-history", section, historyPage, historyPageSize],
    enabled: historyOpen,
    queryFn: ({ signal }) => api<PageResult<SettingRevision>>(`/platform/settings/${section}/history?${listRequestParams({ page: historyPage, pageSize: historyPageSize, search: "" })}`, { signal }),
  });
  const detailQuery = useQuery({
    queryKey: ["setting-history-detail", section, selectedVersion],
    enabled: selectedVersion !== null,
    queryFn: ({ signal }) => api<RevisionDetail<T>>(`/platform/settings/${section}/history/${selectedVersion}`, { signal }),
  });
  const displayReason = (reason: string) => {
    if (reason === SYSTEM_SAVE_REASON) return t("settingsSavedAutomatically");
    if (reason.startsWith(SYSTEM_RESTORE_REASON_PREFIX)) {
      const version = reason.slice(SYSTEM_RESTORE_REASON_PREFIX.length);
      return t("settingsRestoredAutomatically", { version });
    }
    return reason;
  };

  async function reload() {
    const signal = operationSignal();
    if (pending || signal.aborted) return;
    await runSettingsAction("reload", async () => {
      signal.throwIfAborted();
      setSaved(false);
      try {
        const result = await api<Snapshot<T>>(`/platform/settings/${section}`, { signal });
        signal.throwIfAborted();
        await onSnapshot(result, false, signal);
      } catch (error) {
        if (signal.aborted) return false;
        throw new Error("unavailable");
      }
    }, signal);
  }

  function openRevision(revision: SettingRevision) {
    if (isBusy()) return;
    setRestoreError(null);
    restoreForm.resetFields();
    restoreForm.setFieldsValue(initialExtras ?? {});
    setSelectedVersion(revision.version);
  }

  async function restore(values: Record<string, unknown>) {
    const signal = operationSignal();
    if (!canWrite || selectedVersion === null || signal.aborted) return;
    await runSettingsAction("restore", async () => {
      signal.throwIfAborted();
      const result = await mutate<Snapshot<T> & Record<string, unknown>>(`/platform/settings/${section}/rollback`, "POST", {
        ...values,
        version: snapshot.version,
        sourceVersion: selectedVersion,
      }, signal);
      signal.throwIfAborted();
      await onSnapshot(result, true, signal);
      signal.throwIfAborted();
      setSelectedVersion(null);
      setHistoryOpen(false);
    }, signal);
  }

  const historyColumns: TableColumnsType<SettingRevision> = [
    { title: t("configVersion"), dataIndex: "version", width: 100 },
    { title: t("actor"), dataIndex: "changedBy", width: 140 },
    { title: t("changeReason"), dataIndex: "changeReason", render: value => displayReason(String(value ?? "")) },
    { title: t("settingUpdatedAt"), dataIndex: "createdAt", width: 190, render: value => {
      const date = value ? new Date(String(value)) : null;
      return date && !Number.isNaN(date.valueOf()) ? <time dateTime={date.toISOString()}>{formatDateTime(date, locale)}</time> : "—";
    } },
    { title: t("settingSourceVersion"), dataIndex: "sourceVersion", width: 120, render: value => value ?? "—" },
    { title: t("actions"), key: "actions", ...tableActionColumn, render: (_value, row) => <Button type="link" disabled={pending} onClick={() => openRevision(row)}>{t("details")}</Button> },
  ];

  return <>
    <Space size={8} wrap>
      <Button onClick={() => { if (isBusy()) return; setHistoryPage(1); setHistoryOpen(true); }} disabled={pending}>{t("settingHistory")}</Button>
      <Popconfirm title={t("reloadSettingsHint")} okText={t("reloadSettings")} okButtonProps={{ "aria-label": t("reloadSettings"), "aria-busy": pending }} cancelText={t("cancel")} onConfirm={reload} disabled={pending}>
        <Button disabled={pending}>{t("reloadSettings")}</Button>
      </Popconfirm>
    </Space>

    <Drawer title={t("settingHistory")} open={historyOpen} width={900} destroyOnHidden closable={!pending} maskClosable={!pending} keyboard={!pending} onClose={() => { if (!isBusy()) setHistoryOpen(false); }}>
      {historyQuery.error
        ? <QueryFailure title={t("unavailable")} pending={historyQuery.isFetching} onRetry={() => void historyQuery.refetch()} />
        : <Table<SettingRevision>
          rowKey="version"
          size="small"
          loading={historyQuery.isFetching}
          dataSource={historyQuery.data?.items ?? []}
          columns={historyColumns}
          pagination={{ current: historyPage, pageSize: historyPageSize, total: historyQuery.data?.total ?? 0, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS], onChange: (page, pageSize) => {
            const nextPageSize = pageSize ?? historyPageSize;
            if (nextPageSize !== historyPageSize) {
              setHistoryPageSize(nextPageSize);
              setHistoryPage(1);
            } else setHistoryPage(page);
          } }}
          scroll={{ x: "max-content" }}
        />}
    </Drawer>

    <Modal
      title={t("details")}
      open={selectedVersion !== null}
      width={720}
      destroyOnHidden
      closable={!pending}
      maskClosable={!pending}
      keyboard={!pending}
      onCancel={() => { if (!isBusy()) setSelectedVersion(null); }}
      footer={canWrite && <Space className="form-actions">
        <Button onClick={() => { if (!isBusy()) setSelectedVersion(null); }} disabled={pending}>{t("cancel")}</Button>
        <AsyncButton type="primary" form={`restore-${section}`} htmlType="submit" pending={pending} disabled={pending} label={t("restoreSetting")} />
      </Space>}
    >
      {detailQuery.isFetching && <Spin />}
      {detailQuery.error && <QueryFailure title={t("unavailable")} pending={detailQuery.isFetching} onRetry={() => void detailQuery.refetch()} />}
      {detailQuery.data && <>
        <Typography.Paragraph type="secondary">{t("configVersion")}: {detailQuery.data.version} · {detailQuery.data.changedBy} · {formatDateTime(detailQuery.data.createdAt, locale)}</Typography.Paragraph>
        <Typography.Paragraph>{displayReason(detailQuery.data.changeReason)}</Typography.Paragraph>
        <pre className="settings-history-json">{JSON.stringify(detailQuery.data.data, null, 2)}</pre>
        {canWrite && <>
          <Typography.Paragraph type="secondary">{t("restoreSettingHint")}</Typography.Paragraph>
          <Form id={`restore-${section}`} form={restoreForm} layout="vertical" initialValues={initialExtras} disabled={pending} onFinish={restore}>
            {renderExtras?.({ ...snapshot, data: detailQuery.data.data })}
          </Form>
          <FeedbackBubble id={`settings-${section}-history-error`} content={restoreError ? t(restoreError) : undefined} />
        </>}
      </>}
    </Modal>
  </>;
}
