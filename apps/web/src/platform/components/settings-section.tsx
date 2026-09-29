"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { AsyncButton } from "./async-button";
import { useState } from "react";
import { Button, Drawer, Input, Modal, Popconfirm, Space, Spin, Table, Typography } from "antd";
import type { TableColumnsType } from "antd";
import { ProCard } from "@ant-design/pro-components";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, mutate } from "../api/client";
import { messageKeyFor } from "../api/errors";
import { usePreferences } from "../providers";
import type { MessageKey } from "../i18n/messages";
import { FeedbackBubble } from "./feedback-bubble";
import { QueryFailure } from "./query-failure";
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZE_OPTIONS } from "../api/pagination";

const settingsErrorKeys = ["staleRecord", "settingRevisionNotFound", "invalidSettingRevision", "invalidChangeReason", "forbidden", "invalidBranding", "invalidAgreements", "invalidWatermark", "invalidMaintenance", "invalidSecurity", "invalidPasskeySettings", "invalidProfileFields", "invalidSmtp", "invalidSmtpCredentials", "smtpPasswordRequired", "smtpPasswordReplacementRequired", "invalidSms", "invalidSmsCredentials", "smsCredentialRequired", "smsCredentialReplacementRequired", "secretKeyUnavailable", "invalidFileRetention"] as const satisfies readonly MessageKey[];

export type Snapshot<T> = { data: T; version: number; [key: string]: unknown };
type Extras<T> = { initialExtras?: Record<string, unknown>; renderExtras?: (snapshot: Snapshot<T>) => React.ReactNode };
type PageResult<T> = { items: T[]; total: number; page: number; pageSize: number };
type SettingRevision = { version: number; changedBy: string; changeReason: string; createdAt: string; sourceVersion: number | null };
type RevisionDetail<T> = SettingRevision & { data: T };

const SYSTEM_SAVE_REASON = "System-generated record: platform settings saved.";
const SYSTEM_RESTORE_REASON_PREFIX = "System-generated record: platform settings restored from revision ";

export function SettingsSection<T extends object>({ section, canWrite, requireChangeReason = false, confirmBeforeSave, children, ...extras }: { section: string; canWrite: boolean; requireChangeReason?: boolean; confirmBeforeSave?: (values: Record<string, unknown>) => Promise<boolean>; children: React.ReactNode } & Extras<T>) {
  const { t } = usePreferences();
  const query = useQuery({ queryKey: ["settings", section], queryFn: ({ signal }) => api<Snapshot<T>>(`/platform/settings/${section}`, { signal }) });
  return <ProCard className="platform-settings-card">
    {!query.data && (query.error ? <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} /> : <Spin />)}
    {query.data && <SectionEditor<T> key={section} section={section} initial={query.data} canWrite={canWrite} requireChangeReason={requireChangeReason} confirmBeforeSave={confirmBeforeSave} {...extras}>{children}</SectionEditor>}
  </ProCard>;
}

function SectionEditor<T extends object>({ section, initial, canWrite, requireChangeReason, confirmBeforeSave, children, initialExtras, renderExtras }: { section: string; initial: Snapshot<T>; canWrite: boolean; requireChangeReason: boolean; confirmBeforeSave?: (values: Record<string, unknown>) => Promise<boolean>; children: React.ReactNode } & Extras<T>) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm();
  const [restoreForm] = Form.useForm();
  const [snapshot, setSnapshot] = useState(initial);
  const [pending, setPending] = useState(false);
  const [restorePending, setRestorePending] = useState(false);
  const [error, setError] = useState<MessageKey | null>(null);
  const [restoreError, setRestoreError] = useState<MessageKey | null>(null);
  const [saved, setSaved] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyPage, setHistoryPage] = useState(1);
  const [historyPageSize, setHistoryPageSize] = useState(DEFAULT_TABLE_PAGE_SIZE);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const historyQuery = useQuery({
    queryKey: ["setting-history", section, historyPage, historyPageSize],
    enabled: historyOpen,
    queryFn: ({ signal }) => api<PageResult<SettingRevision>>(`/platform/settings/${section}/history?page=${historyPage}&pageSize=${historyPageSize}`, { signal }),
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
  const setEditorValues = (data: T) => form.setFieldsValue({
    ...initialExtras,
    data,
    ...(requireChangeReason ? { changeReason: "" } : {}),
  });

  async function reload() {
    if (pending) return;
    setPending(true);
    setSaved(false);
    try {
      const result = await api<Snapshot<T>>(`/platform/settings/${section}`);
      form.resetFields();
      setEditorValues(result.data);
      setSnapshot(result);
      client.setQueryData(["settings", section], result);
      setError(null);
      await client.invalidateQueries({ queryKey: [`public-${section}`] });
    } catch {
      setError("unavailable");
    } finally {
      setPending(false);
    }
  }

  async function save(values: Record<string, unknown>) {
    if (!canWrite || pending) return;
    setPending(true);
    setError(null);
    setSaved(false);
    try {
      if (confirmBeforeSave && !(await confirmBeforeSave(values))) return;
      // ProForm omits fields that are not currently mounted, such as the watermark
      // image URL while text mode is selected. Preserve the current snapshot values.
      const submitted = { ...values };
      if (!requireChangeReason) delete submitted.changeReason;
      const result = await mutate<Snapshot<T>>(`/platform/settings/${section}`, "PUT", {
        ...submitted,
        data: { ...snapshot.data, ...((values.data ?? {}) as Partial<T>) },
        version: snapshot.version,
      });
      form.resetFields();
      setEditorValues(result.data);
      setSnapshot(result);
      client.setQueryData(["settings", section], result);
      setSaved(true);
      await Promise.all([
        client.invalidateQueries({ queryKey: [`public-${section}`] }),
        client.invalidateQueries({ queryKey: ["setting-history", section] }),
      ]);
    } catch (e) {
      setError(messageKeyFor(e, settingsErrorKeys));
    } finally {
      setPending(false);
    }
  }

  function openRevision(revision: SettingRevision) {
    setRestoreError(null);
    restoreForm.resetFields();
    restoreForm.setFieldsValue({ ...initialExtras, ...(requireChangeReason ? { changeReason: "" } : {}) });
    setSelectedVersion(revision.version);
  }

  async function restore(values: Record<string, unknown>) {
    if (!canWrite || restorePending || selectedVersion === null) return;
    setRestorePending(true);
    setRestoreError(null);
    try {
      const result = await mutate<Snapshot<T> & Record<string, unknown>>(`/platform/settings/${section}/rollback`, "POST", {
        ...values,
        version: snapshot.version,
        sourceVersion: selectedVersion,
      });
      form.resetFields();
      setEditorValues(result.data);
      setSnapshot(result);
      setSelectedVersion(null);
      setHistoryOpen(false);
      setSaved(true);
      client.setQueryData(["settings", section], result);
      await Promise.all([
        client.invalidateQueries({ queryKey: [`public-${section}`] }),
        client.invalidateQueries({ queryKey: ["setting-history", section] }),
      ]);
    } catch (e) {
      setRestoreError(messageKeyFor(e, settingsErrorKeys));
    } finally {
      setRestorePending(false);
    }
  }

  const historyColumns: TableColumnsType<SettingRevision> = [
    { title: t("configVersion"), dataIndex: "version", width: 100 },
    { title: t("actor"), dataIndex: "changedBy", width: 140 },
    { title: t("changeReason"), dataIndex: "changeReason", render: value => displayReason(String(value ?? "")) },
    { title: t("settingUpdatedAt"), dataIndex: "createdAt", width: 190, render: value => value ? <time dateTime={new Date(String(value)).toISOString()}>{new Date(String(value)).toLocaleString()}</time> : "—" },
    { title: t("settingSourceVersion"), dataIndex: "sourceVersion", width: 120, render: value => value ?? "—" },
    { title: t("actions"), key: "actions", width: 100, render: (_value, row) => <Button type="link" onClick={() => openRevision(row)}>{t("details")}</Button> },
  ];

  return <>
    <Form className="settings-form" form={form} layout="vertical" initialValues={{ ...initialExtras, data: initial.data, ...(requireChangeReason ? { changeReason: "" } : {}) }} disabled={!canWrite || pending} onValuesChange={() => setSaved(false)} onFinish={save}>
      {children}
      {renderExtras?.(snapshot)}
      {requireChangeReason && <Form.Item name="changeReason" id="changeReason" label={t("changeReason")} rules={[{ required: true, whitespace: true, message: t("invalidChangeReason") }, { max: 1000 }]}>
        <Input.TextArea maxLength={1000} rows={2} />
      </Form.Item>}
      <FeedbackBubble id={`settings-${section}-error`} content={error ? t(error) : undefined} />
      <FeedbackBubble kind="success" id={`settings-${section}-saved`} content={saved ? t("settingsSaved") : undefined} />
      <Space className="form-actions" size={8} wrap>
        <AsyncButton htmlType="submit" type="primary" pending={pending} disabled={!canWrite} label={t("save")} />
        <Button onClick={() => { setHistoryPage(1); setHistoryOpen(true); }} disabled={pending}>{t("settingHistory")}</Button>
        <Popconfirm title={t("reloadSettingsHint")} okText={t("reloadSettings")} okButtonProps={{ "aria-label": t("reloadSettings"), "aria-busy": pending }} cancelText={t("cancel")} onConfirm={reload} disabled={pending}>
          <Button disabled={pending}>{t("reloadSettings")}</Button>
        </Popconfirm>
      </Space>
    </Form>

    <Drawer title={t("settingHistory")} open={historyOpen} width={900} destroyOnHidden onClose={() => setHistoryOpen(false)}>
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
          scroll={{ x: 760 }}
        />}
    </Drawer>

    <Modal
      title={t("details")}
      open={selectedVersion !== null}
      width={720}
      destroyOnHidden
      onCancel={() => { if (!restorePending) setSelectedVersion(null); }}
      footer={canWrite && <Space className="form-actions">
        <Button onClick={() => setSelectedVersion(null)} disabled={restorePending}>{t("cancel")}</Button>
        <AsyncButton type="primary" form={`restore-${section}`} htmlType="submit" pending={restorePending} label={t("restoreSetting")} />
      </Space>}
    >
      {detailQuery.isFetching && <Spin />}
      {detailQuery.error && <QueryFailure title={t("unavailable")} pending={detailQuery.isFetching} onRetry={() => void detailQuery.refetch()} />}
      {detailQuery.data && <>
        <Typography.Paragraph type="secondary">{t("configVersion")}: {detailQuery.data.version} · {detailQuery.data.changedBy} · {new Date(detailQuery.data.createdAt).toLocaleString()}</Typography.Paragraph>
        <Typography.Paragraph>{displayReason(detailQuery.data.changeReason)}</Typography.Paragraph>
        <pre className="settings-history-json">{JSON.stringify(detailQuery.data.data, null, 2)}</pre>
        {canWrite && <>
          <Typography.Paragraph type="secondary">{t("restoreSettingHint")}</Typography.Paragraph>
          <Form id={`restore-${section}`} form={restoreForm} layout="vertical" initialValues={{ ...initialExtras, changeReason: "" }} disabled={restorePending} onFinish={restore}>
            {renderExtras?.(snapshot)}
            {requireChangeReason && <Form.Item name="changeReason" id={`restore-${section}_changeReason`} label={t("changeReason")} rules={[{ required: true, whitespace: true, message: t("invalidChangeReason") }, { max: 1000 }]}>
              <Input.TextArea id={`restore-${section}_changeReason`} maxLength={1000} rows={2} />
            </Form.Item>}
          </Form>
          <FeedbackBubble id={`settings-${section}-history-error`} content={restoreError ? t(restoreError) : undefined} />
        </>}
      </>}
    </Modal>
  </>;
}
