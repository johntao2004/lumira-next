"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { UploadOutlined } from "@ant-design/icons";
import { Alert, App, Button, Card, Form, Input, Popconfirm, Select, Space, Spin, Table, Tag, Typography, Upload } from "antd";
import type { UploadFile, UploadProps } from "antd";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { FormDrawer } from "@/platform/components/form-drawer";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { PlatformForm } from "@/platform/components/platform-form";
import { QueryFailure } from "@/platform/components/query-failure";
import { ResourceTable } from "@/platform/components/resource-table";
import { isRolePreviewReadOnly } from "@/platform/api/client";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { useListRequest } from "@/platform/routing/use-list-request";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";
import { messageKeyFor } from "@/platform/api/errors";
import {
  createTemplate,
  getTemplate,
  getTemplateFields,
  getTemplateVersions,
  listTemplates,
  replaceTemplateSource,
  saveTemplateBindings,
  templateAction,
  updateTemplate,
} from "@/platform/templates/api";
import { TemplateBindingEditor } from "./template-binding-editor";
import { TemplateTestFillDrawer } from "./template-test-fill-drawer";
import type { BindingMode, TemplateDetail, TemplateStatus, TemplateSummary, TemplateVersion } from "@/platform/templates/types";
import "./print-templates-page.css";

type CreateValues = { name: string; category: string; tags: string[]; bindingMode: BindingMode };
const createErrorKeys = ["unsupportedFile", "documentFileTooLarge", "forbidden", "unavailable"] as const;
const templateErrorKeys = ["staleRecord", "templateNotPublished", "unsupportedFile", "documentFileTooLarge", "forbidden", "fieldNotReadable", "unavailable"] as const;

function statusColor(status: TemplateStatus) {
  return status === "published" ? "success" : status === "inactive" ? "default" : "processing";
}

function formatVersionDate(value: string, locale: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

export function PrintTemplatesPage({ canCreate, canUpdate, canPublish, canUse, canReadExampleUsers, readOnlyPreview = false }: {
  canCreate: boolean;
  canUpdate: boolean;
  canPublish: boolean;
  canUse: boolean;
  canReadExampleUsers: boolean;
  readOnlyPreview?: boolean;
}) {
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const operationSignal = useSessionScope();
  const [request, setRequest] = useListRequest();
  const previewReadOnly = readOnlyPreview || isRolePreviewReadOnly();
  const effectiveCanCreate = canCreate && !previewReadOnly;
  const effectiveCanUpdate = canUpdate && !previewReadOnly;
  const effectiveCanPublish = canPublish && !previewReadOnly;
  const effectiveCanUse = canUse && !previewReadOnly;
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createFile, setCreateFile] = useState<File | null>(null);
  const [createFileList, setCreateFileList] = useState<UploadFile[]>([]);
  const [editSource, setEditSource] = useState<File | null>(null);
  const [editSourceList, setEditSourceList] = useState<UploadFile[]>([]);
  const [bindings, setBindings] = useState<Record<string, string>>({});
  const [bindingsDirty, setBindingsDirty] = useState(false);
  const [metadataDirty, setMetadataDirty] = useState(false);
  const [pendingSourceReview, setPendingSourceReview] = useState(false);
  const [testFillOpen, setTestFillOpen] = useState(false);
  const [createForm] = PlatformForm.useForm<CreateValues>();
  const [editForm] = PlatformForm.useForm<{ name: string; category: string; tags: string[] }>();
  const initializedTemplateId = useRef<string | null>(null);
  const createAction = useSubmitAction({ errorKeys: createErrorKeys, errorAliases: { fileTooLarge: "documentFileTooLarge" }, fallback: "unavailable" });
  const editAction = useSubmitAction({ errorKeys: templateErrorKeys, errorAliases: { fileTooLarge: "documentFileTooLarge" }, fallback: "unavailable" });

  const query = useQuery({
    queryKey: ["print-templates", request],
    queryFn: ({ signal }) => listTemplates({
      page: request.page,
      pageSize: request.pageSize,
      search: request.search,
      category: request.filters?.category,
      tag: request.filters?.tag,
      status: request.filters?.status,
    }, signal),
  });
  const detailsQuery = useQuery({
    queryKey: ["print-template", selectedId],
    enabled: Boolean(selectedId),
    queryFn: ({ signal }) => getTemplate(selectedId!, signal),
    retry: false,
  });
  const versionsQuery = useQuery({
    queryKey: ["print-template-versions", selectedId],
    enabled: Boolean(selectedId),
    queryFn: ({ signal }) => getTemplateVersions(selectedId!, signal),
    retry: false,
  });
  const fieldsQuery = useQuery({
    queryKey: ["print-template-fields"],
    queryFn: ({ signal }) => getTemplateFields(signal),
    retry: false,
  });
  const detail = detailsQuery.data ?? null;
  const statusLabels = useMemo<Record<TemplateStatus, string>>(() => ({
    draft: t("templateStatusDraft"),
    published: t("templateStatusPublished"),
    inactive: t("templateStatusInactive"),
  }), [t]);

  useEffect(() => {
    if (!selectedId) {
      initializedTemplateId.current = null;
      setPendingSourceReview(false);
      return;
    }
    if (!detail || detail.id !== selectedId || initializedTemplateId.current === selectedId) return;
    initializedTemplateId.current = selectedId;
    setBindings(detail.currentVersion?.bindings ?? {});
    setBindingsDirty(false);
    editForm.setFieldsValue({ name: detail.name, category: detail.category, tags: detail.tags ?? [] });
    setMetadataDirty(false);
    setEditSource(null);
    setEditSourceList([]);
  }, [detail, editForm, selectedId]);

  const invalidateLists = async (templateId?: string) => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["print-templates"] }, { throwOnError: true }),
      ...(templateId ? [client.invalidateQueries({ queryKey: ["print-template-versions", templateId] }, { throwOnError: true })] : []),
    ]);
  };

  const closeDetail = () => {
    if (editAction.isPending()) return;
    setSelectedId(null);
    setTestFillOpen(false);
    setEditSource(null);
    setEditSourceList([]);
    setMetadataDirty(false);
  };

  const createBeforeUpload: UploadProps["beforeUpload"] = (file) => {
    const extension = file.name.split(".").pop()?.toLowerCase();
    if (extension !== "docx" && extension !== "xlsx") {
      createAction.setError("unsupportedFile");
      setCreateFile(null);
      setCreateFileList([]);
      return Upload.LIST_IGNORE;
    }
    if (file.size > 10 * 1024 * 1024) {
      createAction.setError("documentFileTooLarge");
      setCreateFile(null);
      setCreateFileList([]);
      return Upload.LIST_IGNORE;
    }
    createAction.setError(null);
    setCreateFile(file);
    return false;
  };

  const editBeforeUpload: UploadProps["beforeUpload"] = (file) => {
    const extension = file.name.split(".").pop()?.toLowerCase();
    if (extension !== "docx" && extension !== "xlsx") {
      editAction.setError("unsupportedFile");
      setEditSource(null);
      setEditSourceList([]);
      return Upload.LIST_IGNORE;
    }
    if (file.size > 10 * 1024 * 1024) {
      editAction.setError("documentFileTooLarge");
      setEditSource(null);
      setEditSourceList([]);
      return Upload.LIST_IGNORE;
    }
    setEditSource(file);
    editAction.setError(null);
    return false;
  };

  const create = async (values: CreateValues) => {
    const signal = operationSignal();
    await createAction.run(async () => {
      if (!createFile || signal.aborted || previewReadOnly) return false;
      const result = await createTemplate(createFile, values, signal);
      if (signal.aborted) return false;
      setCreateOpen(false);
      setCreateFile(null);
      setCreateFileList([]);
      createForm.resetFields();
      setSelectedId(result.id);
      await invalidateLists(result.id);
      void message.success(t("templateCreateSuccess"));
      return true;
    });
  };

  const persistDraft = async (signal: AbortSignal, current: TemplateDetail): Promise<false | "saved" | "sourceChanged"> => {
    let version = current.version;
    const metadataWasDirty = metadataDirty;
    const bindingsWereDirty = bindingsDirty;
    if (metadataWasDirty) {
      const values = await editForm.validateFields();
      const savedMetadata = await updateTemplate(current.id, {
        version,
        name: values.name.trim(),
        category: values.category.trim(),
        tags: values.tags ?? [],
      }, signal);
      if (signal.aborted) return false;
      version = savedMetadata.version;
      setMetadataDirty(false);
      client.setQueryData(["print-template", current.id], savedMetadata);
    }
    if (editSource) {
      const previousBindings = bindings;
      await replaceTemplateSource(current.id, version, editSource, signal);
      if (signal.aborted) return false;
      setEditSource(null);
      setEditSourceList([]);
      const refreshed = await detailsQuery.refetch({ throwOnError: true });
      if (!refreshed.data || signal.aborted) return false;
      const validTargets = new Set([
        ...(refreshed.data.currentVersion?.slots ?? []).map((slot) => slot.key),
        ...(refreshed.data.currentVersion?.placeholders ?? []),
      ]);
      const readableFields = fieldsQuery.data
        ? new Set(fieldsQuery.data.filter((field) => field.readable).map((field) => field.key))
        : null;
      const generatedBindings = refreshed.data.currentVersion?.bindings ?? {};
      const retainedBindings = Object.fromEntries(Object.entries(previousBindings).filter(([target, fieldKey]) => validTargets.has(target) && (!readableFields || readableFields.has(fieldKey))));
      setBindings({ ...generatedBindings, ...retainedBindings });
      setBindingsDirty(true);
      setPendingSourceReview(true);
      setMetadataDirty(false);
      await invalidateLists(current.id);
      void message.info(t("templateSourceDraftSavedReview"));
      return "sourceChanged";
    }
    if (bindingsWereDirty) {
      const savedBindings = await saveTemplateBindings(current.id, version, bindings, signal);
      if (signal.aborted) return false;
      client.setQueryData(["print-template", current.id], savedBindings);
      const refreshed = await detailsQuery.refetch({ throwOnError: true });
      if (signal.aborted || !refreshed.data) return false;
      setBindings(refreshed.data.currentVersion?.bindings ?? {});
      setBindingsDirty(false);
      setPendingSourceReview(false);
      await invalidateLists(current.id);
    } else if (metadataWasDirty) {
      await Promise.all([detailsQuery.refetch({ throwOnError: true }), invalidateLists(current.id)]);
    }
    return "saved";
  };

  const saveDraft = async () => {
    if (!detail || editAction.isPending()) return;
    const signal = operationSignal();
    await editAction.run(async () => {
      const saved = await persistDraft(signal, detail);
      if (saved === "saved") void message.success(t("templateDraftSaved"));
      return saved !== false;
    });
  };

  const runTemplateAction = async (action: "publish" | "deactivate") => {
    if (!detail || editAction.isPending()) return;
    const signal = operationSignal();
    await editAction.run(async () => {
      if (action === "publish") {
        const saved = await persistDraft(signal, detail);
        if (saved !== "saved" || signal.aborted) return false;
      }
      const latest = await detailsQuery.refetch({ throwOnError: true });
      if (signal.aborted || !latest.data) return false;
      await templateAction(latest.data.id, action, latest.data.version, signal);
      if (signal.aborted) return false;
      await Promise.all([detailsQuery.refetch({ throwOnError: true }), invalidateLists(latest.data.id)]);
      void message.success(t(action === "publish" ? "templatePublishedSuccess" : "templateDeactivatedSuccess"));
      return true;
    });
  };

  const tagLabels = (values: string[]) => values.map((tag) => <Tag key={tag}>{tag}</Tag>);

  return <>
    {fieldsQuery.error && <QueryFailure title={t("unavailable")} pending={fieldsQuery.isFetching} onRetry={() => void fieldsQuery.refetch()} />}
    <ResourceTable<TemplateSummary>
      result={query.data}
      loading={query.isFetching}
      error={query.error}
      request={request}
      onChange={setRequest}
      onRetry={() => void query.refetch()}
      columns={[
        { title: t("templateName"), dataIndex: "name", search: true, render: (_, row) => <Button type="link" onClick={() => setSelectedId(row.id)}>{row.name}</Button> },
        { title: t("templateCategory"), dataIndex: "category", search: true },
        { title: t("templateTags"), dataIndex: "tag", search: true, render: (_, row) => <Space size={[0, 4]} wrap>{tagLabels(row.tags ?? [])}</Space> },
        { title: t("templateVersion"), dataIndex: "version", search: false, render: (_, row) => row.draftVersion ?? row.publishedVersion ?? "—" },
        { title: t("status"), dataIndex: "status", search: true, valueEnum: {
          draft: { text: statusLabels.draft }, published: { text: statusLabels.published }, inactive: { text: statusLabels.inactive },
        }, render: (_, row) => <Tag color={statusColor(row.status)}>{statusLabels[row.status]}</Tag> },
        { title: t("actions"), key: "actions", search: false, render: (_, row) => <Button type="link" onClick={() => setSelectedId(row.id)}>{t("details")}</Button> },
      ]}
      actions={effectiveCanCreate ? <Button type="primary" onClick={() => setCreateOpen(true)}>{t("templateCreate")}</Button> : null}
    />

    <FormDrawer
      open={createOpen}
      title={t("templateCreate")}
      pending={createAction.pending}
      submitDisabled={!createFile}
      onClose={() => {
        if (createAction.isPending()) return;
        setCreateOpen(false);
        setCreateFile(null);
        setCreateFileList([]);
        createForm.resetFields();
        createAction.setError(null);
      }}
      formId="print-template-create"
      size={760}
    >
      <PlatformForm
        form={createForm}
        id="print-template-create"
        name="print-template-create-form"
        layout="vertical"
        disabled={createAction.pending}
        initialValues={{ tags: [], bindingMode: "auto" }}
        onFinish={create}
      >
        <Alert type="info" showIcon title={t("templateCreateHint")} />
        <PlatformForm.Item name="name" label={t("templateName")} rules={[{ required: true, whitespace: true }]}><Input maxLength={120} /></PlatformForm.Item>
        <PlatformForm.Item name="category" label={t("templateCategory")} rules={[{ required: true, whitespace: true }]}><Input maxLength={80} /></PlatformForm.Item>
        <PlatformForm.Item name="tags" label={t("templateTags")}><Select mode="tags" tokenSeparators={[",", "，"]} /></PlatformForm.Item>
        <PlatformForm.Item name="bindingMode" label={t("templateBindingMode")} rules={[{ required: true }]}>
          <Select options={[{ value: "auto", label: t("templateBindingAuto") }, { value: "manual", label: t("templateBindingManual") }]} />
        </PlatformForm.Item>
        <PlatformForm.Item label={t("templateSourceFile")} required extra={t("templateUploadHint")}>
          <Upload
            accept=".docx,.xlsx,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            maxCount={1}
            fileList={createFileList}
            disabled={createAction.pending}
            beforeUpload={createBeforeUpload}
            onChange={({ fileList }) => setCreateFileList(fileList.slice(-1))}
            onRemove={() => { setCreateFile(null); setCreateFileList([]); return true; }}
          ><Button icon={<UploadOutlined />}>{t("uploadFile")}</Button></Upload>
        </PlatformForm.Item>
        <FeedbackBubble id="print-template-create-error" content={createAction.errorMessage} />
      </PlatformForm>
    </FormDrawer>

    <PlatformDrawer
      open={Boolean(selectedId)}
      title={detail?.name ?? t("printTemplates")}
      placement="right"
      width={1040}
      onClose={closeDetail}
      closable={{ disabled: editAction.pending }}
      keyboard={!editAction.pending}
      destroyOnHidden
      footer={detail && <Space wrap>
        <Button disabled={editAction.pending} onClick={closeDetail}>{t("close")}</Button>
        {effectiveCanUse && <Button disabled={editAction.pending || !(detail.publishedVersion !== null || (detail.draftVersion !== null && effectiveCanUpdate))} onClick={() => setTestFillOpen(true)}>{t("templateTestFill")}</Button>}
        {effectiveCanUpdate && <AsyncButton pending={editAction.pending} label={t("templateSaveDraft")} onClick={() => void saveDraft()} />}
        {effectiveCanPublish && detail.draftVersion !== null && <Popconfirm title={t("templatePublishConfirm")} onConfirm={() => void runTemplateAction("publish")}>
          <Button type="primary" disabled={editAction.pending || Boolean(editSource) || pendingSourceReview}>{t("publish")}</Button>
        </Popconfirm>}
        {effectiveCanUpdate && detail.status === "published" && <Popconfirm title={t("templateDeactivateConfirm")} onConfirm={() => void runTemplateAction("deactivate")}>
          <Button danger disabled={editAction.pending}>{t("templateDeactivate")}</Button>
        </Popconfirm>}
      </Space>}
    >
      {selectedId && !detail && (detailsQuery.error
        ? <QueryFailure title={t("unavailable")} pending={detailsQuery.isFetching} onRetry={() => void detailsQuery.refetch()} />
        : <div className="template-detail-loading"><Spin description={t("loading")} /></div>)}
      {detail && <div className="template-detail">
        <div className="template-detail__heading">
          <Space wrap>
            <Tag color={statusColor(detail.status)}>{statusLabels[detail.status]}</Tag>
            {detail.draftVersion !== null && <Tag>{t("templateStatusDraft")} · {t("templateVersion")} {detail.draftVersion}</Tag>}
            {detail.publishedVersion !== null && <Tag color="success">{t("templateStatusPublished")} · {t("templateVersion")} {detail.publishedVersion}</Tag>}
          </Space>
          {detail.currentVersion?.createdAt && <Typography.Text type="secondary">{detail.currentVersion.createdAt}</Typography.Text>}
        </div>
        <FeedbackBubble id="print-template-edit-error" content={editAction.errorMessage} />
        <PlatformForm
          form={editForm}
          id="print-template-details"
          name="print-template-details-form"
          layout="vertical"
          disabled={!effectiveCanUpdate || editAction.pending}
          onValuesChange={() => setMetadataDirty(true)}
          onFinish={() => undefined}
        >
          <div className="template-detail__metadata">
            <PlatformForm.Item name="name" label={t("templateName")} rules={[{ required: true, whitespace: true }]}><Input maxLength={120} /></PlatformForm.Item>
            <PlatformForm.Item name="category" label={t("templateCategory")} rules={[{ required: true, whitespace: true }]}><Input maxLength={80} /></PlatformForm.Item>
            <PlatformForm.Item name="tags" label={t("templateTags")}><Select mode="tags" tokenSeparators={[",", "，"]} /></PlatformForm.Item>
          </div>
        </PlatformForm>

        <Card className="template-detail__history" size="small" title={t("templateVersionHistory")}>
          {versionsQuery.error
            ? <QueryFailure title={t("unavailable")} pending={versionsQuery.isFetching} onRetry={() => void versionsQuery.refetch()} />
            : <Table<TemplateVersion>
              className="template-detail__history-table"
              size="small"
              rowKey="version"
              loading={versionsQuery.isFetching}
              dataSource={versionsQuery.data ?? []}
              scroll={{ x: 800 }}
              pagination={{ pageSize: 5, showSizeChanger: true, pageSizeOptions: [5, 10, 20] }}
              columns={[
                { title: t("templateVersion"), dataIndex: "version", width: 90 },
                { title: t("status"), dataIndex: "status", width: 130, render: (status: TemplateVersion["status"]) => <Tag color={status === "published" ? "success" : status === "draft" ? "processing" : "default"}>{t(status === "draft" ? "templateStatusDraft" : status === "published" ? "templateStatusPublished" : "templateVersionStatusSuperseded")}</Tag> },
                { title: t("templateVersionSourceFile"), dataIndex: "filename", width: 220, ellipsis: true },
                { title: t("templateVersionCreatedAt"), dataIndex: "createdAt", width: 190, render: (value: string) => formatVersionDate(value, locale) },
                { title: t("templateVersionCreatedBy"), dataIndex: "createdBy", width: 150 },
              ]}
            />}
        </Card>

        {effectiveCanUpdate && <Card className="template-detail__source" size="small" title={t("templateSourceFile")}>
          {pendingSourceReview && <Alert className="template-source-review-alert" type="info" showIcon title={t("templateSourceDraftSavedReview")} />}
          <Space className="template-detail__source-controls" wrap>
            <Upload
              accept=".docx,.xlsx,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              maxCount={1}
              fileList={editSourceList}
              disabled={editAction.pending}
              beforeUpload={editBeforeUpload}
              onChange={({ fileList }) => setEditSourceList(fileList.slice(-1))}
              onRemove={() => { setEditSource(null); setEditSourceList([]); return true; }}
            ><Button icon={<UploadOutlined />}>{t("templateReplaceSource")}</Button></Upload>
            {editSource && <Typography.Text className="template-detail__source-filename" type="secondary">{editSource.name}</Typography.Text>}
          </Space>
          <Typography.Paragraph className="template-detail__source-hint" type="secondary">{t("templateNewSourceDraftHint")}</Typography.Paragraph>
        </Card>}

        {fieldsQuery.isFetching ? <Spin /> : fieldsQuery.data && <TemplateBindingEditor
          fileName={editSource?.name}
          slots={detail.currentVersion?.slots ?? []}
          placeholders={detail.currentVersion?.placeholders ?? []}
          fields={fieldsQuery.data}
          bindings={bindings}
          readOnly={!effectiveCanUpdate || editAction.pending}
          onChange={(next) => { setBindings(next); setBindingsDirty(true); }}
        />}
      </div>}
    </PlatformDrawer>

    <TemplateTestFillDrawer
      template={detail}
      versions={versionsQuery.data ?? []}
      fields={fieldsQuery.data ?? []}
      open={testFillOpen}
      canReadExampleUsers={canReadExampleUsers && !previewReadOnly}
      canUpdate={effectiveCanUpdate}
      onClose={() => setTestFillOpen(false)}
    />
  </>;
}
