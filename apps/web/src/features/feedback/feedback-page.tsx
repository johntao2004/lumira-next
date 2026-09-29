"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import { useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {Alert, App, Button, Input, Segmented, Select, Space, Tag, Typography} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api, ApiError, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import { FormDrawer } from "@/platform/components/form-drawer";
import {
  hasRichTextContent,
  RichTextEditor,
  RichTextPreview,
} from "@/platform/components/rich-text-editor";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { DraftActions } from "@/platform/drafts/draft-actions";
import { useAccountDraft } from "@/platform/drafts/use-account-draft";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { formatDateTime } from "@/platform/i18n/format";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { decodeFeedbackDraft, encodeFeedbackDraft } from "./feedback-draft";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";

type Choice = { value: string; label: string; terminal?: boolean };
type FeedbackCatalog = {
  policy?: { statuses: Record<string, boolean>; priorities: string[]; categories: string[]; initialStatus: string; defaultPriority: string };
  statuses?: Choice[];
  priorities?: Choice[];
  categories?: Choice[];
};
const EMPTY_CHOICES: Choice[] = [];
type FeedbackSummary = {
  id: string;
  ownerId: string;
  ownerName: string | null;
  title: string;
  priority: string;
  category: string | null;
  status: string;
  version: number;
  createdAt: string;
  updatedAt: string;
};
type FeedbackDetail = FeedbackSummary & {
  detailHtml: string;
  adminReply: string | null;
  handledBy: string | null;
  handledAt: string | null;
};

const statusColors: Record<string, string> = {
  OPEN: "blue",
  PROCESSING: "gold",
  RESOLVED: "green",
  CLOSED: "default",
};
const priorityColors: Record<string, string> = {
  LOW: "default",
  NORMAL: "blue",
  HIGH: "orange",
  URGENT: "red",
};

const feedbackErrorKeys = [
    "forbidden",
    "invalidFeedback",
    "feedbackNotFound",
    "feedbackPolicyUnavailable",
    "sensitiveContent",
    "moduleUnavailable",
    "staleRecord",
] as const satisfies readonly MessageKey[];

function dateTime(value: string, locale: string) {
  return formatDateTime(value, locale, { dateStyle: "medium", timeStyle: "short" });
}

export function FeedbackPage({ canCreate, canManage }: { canCreate: boolean; canManage: boolean }) {
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [scope, setScope] = useState<"mine" | "admin">(() => canManage ? "admin" : "mine");
  const activeScope = canManage ? scope : "mine";
  const [request, setRequest] = useListRequest();
  const [createOpen, setCreateOpen] = useState(false);
  const [detailRow, setDetailRow] = useState<FeedbackSummary | null>(null);
  const [handleOpen, setHandleOpen] = useState(false);
  const [detailHtml, setDetailHtml] = useState("");
  const [formError, setFormError] = useState<MessageKey | null>(null);
  const [invalidDraft, setInvalidDraft] = useState(false);
  const [draftWasRestored, setDraftWasRestored] = useState(false);
  const [restoredDraftCategory, setRestoredDraftCategory] = useState(false);
  const [createPending, setCreatePending] = useState(false);
  const [handlePending, setHandlePending] = useState(false);
  const [createForm] = Form.useForm<{ title: string; priority: string; category: string }>();
  const [handleForm] = Form.useForm<{ status: string; adminReply?: string }>();
  const createDraft = useAccountDraft(canCreate ? "feedback.create" : undefined);

  useEffect(() => {
    setScope(canManage ? "admin" : "mine");
  }, [canManage]);

  const options = useQuery({
    queryKey: ["feedback-options"],
    queryFn: ({ signal }) => api<FeedbackCatalog>("/feedback/options", { signal }),
  });
  const choices = options.data;
  const categories = Array.isArray(choices?.categories) ? choices.categories : EMPTY_CHOICES;
  const statuses = Array.isArray(choices?.statuses) ? choices.statuses : EMPTY_CHOICES;
  const priorities = Array.isArray(choices?.priorities) ? choices.priorities : EMPTY_CHOICES;
  const defaultPriority = choices?.policy?.defaultPriority ?? "NORMAL";
  const categoryPolicyUnavailable = options.isError || Boolean(choices && categories.length === 0);
  const listQuery = useQuery({
    queryKey: ["feedback", activeScope, request],
    queryFn: ({ signal }) => {
      const params = listRequestParams(request, {
        status: request.filters?.status ?? "",
        priority: request.filters?.priority ?? "",
        category: request.filters?.category ?? "",
      });
      return api<PageResult<FeedbackSummary>>(
        `${activeScope === "admin" ? "/feedback/manage" : "/feedback"}?${params.toString()}`,
        { signal },
      );
    },
  });
  const detailQuery = useQuery({
    queryKey: ["feedback-detail", activeScope, detailRow?.id],
    enabled: Boolean(detailRow),
    queryFn: ({ signal }) => {
      const row = detailRow;
      if (!row) throw new Error("feedbackNotFound");
      return api<FeedbackDetail>(
        `${activeScope === "admin" ? "/feedback/manage" : "/feedback"}/${row.id}`,
        { signal },
      );
    },
  });

  useEffect(() => {
    if (createOpen && !draftWasRestored && !createDraft.draft && choices?.policy?.defaultPriority) {
      createForm.setFieldValue("priority", choices.policy.defaultPriority);
      if (!createForm.getFieldValue("category")) createForm.setFieldValue("category", categories[0]?.value);
    }
  }, [categories, choices?.policy?.defaultPriority, createDraft.draft, createForm, createOpen, draftWasRestored]);

  useEffect(() => {
    if (!createOpen || !restoredDraftCategory || !choices || options.isFetching || options.isError) return;
    const category = createForm.getFieldValue("category");
    if (!categories.some((choice) => choice.value === category)) {
      createForm.setFieldValue("category", "");
      void createForm.validateFields(["category"]).catch(() => undefined);
    }
    setRestoredDraftCategory(false);
  }, [categories, choices, createForm, createOpen, options.isError, options.isFetching, restoredDraftCategory]);

  const label = (items: Choice[] | undefined, value: string) =>
    items?.find((item) => item.value === value)?.label ?? value;
  const resetCreate = () => {
    setCreateOpen(false);
    setDraftWasRestored(false);
    setRestoredDraftCategory(false);
    setDetailHtml("");
    setFormError(null);
    setInvalidDraft(false);
    createForm.resetFields();
  };
  const openCreate = () => {
    setFormError(null);
    setDraftWasRestored(false);
    setRestoredDraftCategory(false);
    setInvalidDraft(false);
    createForm.resetFields();
    createForm.setFieldsValue({ priority: defaultPriority, category: categories[0]?.value ?? "" });
    setDetailHtml("");
    setCreateOpen(true);
  };
  const refresh = () => client.invalidateQueries({ queryKey: ["feedback"] });

  const saveCreate = async (values: { title: string; priority: string; category: string }) => {
    if (createPending || !canCreate) return;
    if (!hasRichTextContent(detailHtml)) {
      setFormError("invalidFeedback");
      return;
    }
    setCreatePending(true);
    setFormError(null);
    try {
      await mutate("/feedback", "POST", { ...values, detailHtml });
      try {
        await createDraft.clearConsumed();
      } catch {
        void message.warning(t("draftCleanupFailed"));
      }
      void message.success(t("feedbackSubmitted"));
      resetCreate();
      await refresh();
    } catch (error) {
      setFormError(messageKeyFor(error, feedbackErrorKeys));
    } finally {
      setCreatePending(false);
    }
  };

  const openHandle = () => {
    if (!detailQuery.data || !canManage) return;
    handleForm.setFieldsValue({ status: detailQuery.data.status, adminReply: detailQuery.data.adminReply ?? "" });
    setFormError(null);
    setHandleOpen(true);
  };
  const saveHandle = async (values: { status: string; adminReply?: string }) => {
    if (!detailQuery.data || handlePending || !canManage) return;
    setHandlePending(true);
    setFormError(null);
    try {
      const result = await mutate<FeedbackDetail>(`/feedback/manage/${detailQuery.data.id}`, "PUT", {
        ...values,
        version: detailQuery.data.version,
        adminReply: values.adminReply ?? "",
      });
      client.setQueryData(["feedback-detail", activeScope, detailQuery.data.id], result);
      setHandleOpen(false);
      void message.success(t("feedbackStatusUpdated"));
      await refresh();
    } catch (error) {
      setFormError(messageKeyFor(error, feedbackErrorKeys));
      if (error instanceof ApiError && error.status === 409) await detailQuery.refetch();
    } finally {
      setHandlePending(false);
    }
  };

  return (
    <>
      <FeedbackBubble id="feedback-form-error" content={formError ? t(formError) : undefined}/>
      <FeedbackBubble kind="warning" id="feedback-invalid-draft" content={invalidDraft ? t("invalidDraft") : undefined}/>
      {categoryPolicyUnavailable && <Alert type="warning" showIcon message={t("feedbackCategoriesUnavailable")} className="mb-4" />}
      <ResourceTable<FeedbackSummary>
        result={listQuery.data}
        loading={listQuery.isFetching}
        error={listQuery.error}
        request={request}
        onChange={setRequest}
        onRetry={() => void listQuery.refetch()}
        searchInitialValues={{
          title: request.filters?.title,
          ...(activeScope === "admin" ? { ownerName: request.filters?.ownerName } : {}),
          status: request.filters?.status,
          priority: request.filters?.priority,
          category: request.filters?.category,
        }}
        onSearch={({ filters, filterModes }) => setRequest({
          ...request,
          page: 1,
          search: "",
          filters: filters ?? {},
          filterModes: filterModes ?? {},
        })}
        actions={
          <Space wrap>
            {canManage && (
              <Segmented
                value={scope}
                options={[{ label: t("allFeedback"), value: "admin" }, { label: t("myFeedback"), value: "mine" }]}
                onChange={(value) => {
                  setScope(value as "mine" | "admin");
                  setRequest((current) => ({ ...current, page: 1 }));
                }}
              />
            )}
            {canCreate && <Button type="primary" onClick={openCreate}>{t("createFeedback")}</Button>}
          </Space>
        }
        columns={[
          { title: t("feedbackTitle"), dataIndex: "title", search: true },
          { title: t("feedbackCategory"), dataIndex: "category", search: true, valueEnum: Object.fromEntries(categories.map((item) => [item.value, { text: item.label }])), render: (_: unknown, row: FeedbackSummary) => <Tag>{row.category ? label(categories, row.category) : t("uncategorized")}</Tag> },
          ...(activeScope === "admin" ? [{ title: t("feedbackOwner"), dataIndex: "ownerName", search: true, render: (_: unknown, row: FeedbackSummary) => row.ownerName ?? row.ownerId }] : []),
          { title: t("feedbackStatus"), dataIndex: "status", search: true, valueEnum: Object.fromEntries(statuses.map((item) => [item.value, { text: item.label }])), render: (_: unknown, row: FeedbackSummary) => <Tag color={statusColors[row.status]}>{label(statuses, row.status)}</Tag> },
          { title: t("feedbackPriority"), dataIndex: "priority", search: true, valueEnum: Object.fromEntries(priorities.map((item) => [item.value, { text: item.label }])), render: (_: unknown, row: FeedbackSummary) => <Tag color={priorityColors[row.priority] ?? "default"}>{label(priorities, row.priority)}</Tag> },
          { title: t("feedbackUpdatedAt"), dataIndex: "updatedAt", valueType: "dateTimeRange", render: (_: unknown, row: FeedbackSummary) => dateTime(row.updatedAt, locale) },
          { title: t("actions"), search: false, render: (_: unknown, row: FeedbackSummary) => <TableActions><Button type="link" onClick={() => { setDetailRow(row); setFormError(null); }}>{t("details")}</Button></TableActions> },
        ]}
      />

      <FormDrawer open={createOpen} title={t("createFeedback")} pending={createPending || createDraft.busy} submitDisabled={!canCreate || options.isPending || options.isError || restoredDraftCategory || !categories.length || createDraft.busy || createDraft.conflict} formId="feedback-create" size={620} onClose={resetCreate}>
        <Form id="feedback-create" form={createForm} layout="vertical" onFinish={saveCreate}>
          {canCreate && <DraftActions
            draftState={createDraft}
            disabled={createPending || options.isPending}
            onSave={() => encodeFeedbackDraft({ ...createForm.getFieldsValue(), detailHtml })}
            onRestore={(payload) => {
              const values = decodeFeedbackDraft(payload);
              setInvalidDraft(!values);
              if (!values) return false;
              createForm.resetFields();
              createForm.setFieldsValue({ title: values.title, priority: values.priority, category: values.category });
              setDraftWasRestored(true);
              setRestoredDraftCategory(true);
              setDetailHtml(values.detailHtml);
              return true;
            }}
          />}
          <Form.Item name="title" label={t("feedbackTitle")} rules={[{ required: true, whitespace: true, message: t("feedbackTitleRequired") }, { max: 160, message: t("feedbackTitleTooLong") }]}> 
            <Input maxLength={160} showCount />
          </Form.Item>
          <Form.Item name="category" label={t("feedbackCategory")} rules={[{ required: true, message: t("feedbackCategoryRequired") }]}>
            <Select options={categories.map((item) => ({ value: item.value, label: item.label }))} />
          </Form.Item>
          <Form.Item name="priority" label={t("feedbackPriority")} rules={[{ required: true }]}>
            <Select options={priorities.map((item) => ({ value: item.value, label: item.label }))} />
          </Form.Item>
          <Form.Item label={t("feedbackDetail")} required>
            <RichTextEditor
              value={detailHtml}
              onChange={setDetailHtml}
              disabled={createPending}
              placeholder={t("feedbackEditorHint")}
              imageUploadPath="/feedback/images"
              imageButtonLabel={t("insertImage")}
              imageUploadingLabel={t("imageUploading")}
              imageUploadError={t("imageUploadFailed")}
            />
          </Form.Item>
        </Form>
      </FormDrawer>

      <PlatformDrawer open={Boolean(detailRow)} title={t("feedbackDetails")} size={720} destroyOnHidden onClose={() => setDetailRow(null)} footer={<Space><Button onClick={() => setDetailRow(null)}>{t("close")}</Button>{activeScope === "admin" && canManage && <Button type="primary" onClick={openHandle}>{t("processFeedback")}</Button>}</Space>}>
        {detailQuery.isPending && <Typography.Text type="secondary">{t("loading")}</Typography.Text>}
        <FeedbackBubble id="feedback-detail-error" content={detailQuery.error ? t(messageKeyFor(detailQuery.error, feedbackErrorKeys)) : undefined}/>
        {detailQuery.data && <Space orientation="vertical" size="middle" className="w-full">
          <Typography.Title level={4} className="!mb-0">{detailQuery.data.title}</Typography.Title>
          <Space wrap>
            <Tag>{detailQuery.data.category ? label(categories, detailQuery.data.category) : t("uncategorized")}</Tag>
            <Tag color={statusColors[detailQuery.data.status]}>{label(statuses, detailQuery.data.status)}</Tag>
            <Tag color={priorityColors[detailQuery.data.priority] ?? "default"}>{label(priorities, detailQuery.data.priority)}</Tag>
            <Typography.Text type="secondary">{dateTime(detailQuery.data.createdAt, locale)}</Typography.Text>
          </Space>
          <RichTextPreview html={detailQuery.data.detailHtml} />
          {detailQuery.data.adminReply && <div><Typography.Text strong>{t("feedbackReply")}</Typography.Text><Typography.Paragraph className="!mt-2 whitespace-pre-wrap">{detailQuery.data.adminReply}</Typography.Paragraph></div>}
        </Space>}
      </PlatformDrawer>

      <FormDrawer open={handleOpen} title={t("processFeedback")} pending={handlePending} formId="feedback-handle" submitDisabled={!canManage || !detailQuery.data} onClose={() => { if (!handlePending) setHandleOpen(false); }}>
        <Form id="feedback-handle" form={handleForm} layout="vertical" onFinish={saveHandle}>
            <Form.Item name="status" label={t("feedbackStatus")} rules={[{ required: true }]}><Select options={statuses.map((item) => ({ value: item.value, label: item.label }))} /></Form.Item>
          <Form.Item name="adminReply" label={t("feedbackReply")}><Input.TextArea rows={6} maxLength={4000} showCount /></Form.Item>
        </Form>
      </FormDrawer>
    </>
  );
}
