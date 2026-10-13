"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import { useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, App, Button, Input, Select, Space, Tag, Typography } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api, ApiError, getAuthorizationContext, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import { FormDrawer } from "@/platform/components/form-drawer";
import {
  hasRichTextContent,
  RichTextEditor,
  RichTextPreview,
  sanitizeRichText,
} from "@/platform/components/rich-text-editor";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { formatDateTime } from "@/platform/i18n/format";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
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
  canUpdateStatus: boolean;
  canReply: boolean;
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

export function FeedbackPage({ canRead, canCreate, canUpdateStatus, canReply }: { canRead: boolean; canCreate: boolean; canUpdateStatus: boolean; canReply: boolean }) {
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [createOpen, setCreateOpen] = useState(false);
  const [detailRow, setDetailRow] = useState<FeedbackSummary | null>(null);
  const [handleOpen, setHandleOpen] = useState(false);
  const [detailHtml, setDetailHtml] = useState("");
  const [formError, setFormError] = useState<MessageKey | null>(null);
  const [handleError, setHandleError] = useState<MessageKey | null>(null);
  const [createPending, setCreatePending] = useState(false);
  const [handlePending, setHandlePending] = useState(false);
  const [createForm] = Form.useForm<{ title: string; priority: string; category: string }>();
  const [handleForm] = Form.useForm<{ status: string; adminReply?: string }>();

  const options = useQuery({
    queryKey: ["feedback-options"],
    enabled: canRead || canCreate,
    queryFn: ({ signal }) => api<FeedbackCatalog>("/feedback/options", { signal }),
  });
  const choices = options.data;
  const categories = Array.isArray(choices?.categories) ? choices.categories : EMPTY_CHOICES;
  const statuses = Array.isArray(choices?.statuses) ? choices.statuses : EMPTY_CHOICES;
  const priorities = Array.isArray(choices?.priorities) ? choices.priorities : EMPTY_CHOICES;
  const defaultPriority = choices?.policy?.defaultPriority ?? "NORMAL";
  const categoryPolicyUnavailable = options.isError || Boolean(choices && categories.length === 0);
  const listQuery = useQuery({
    queryKey: ["feedback", request],
    enabled: canRead,
    queryFn: ({ signal }) => {
      const params = listRequestParams(request, {
        status: request.filters?.status ?? "",
        priority: request.filters?.priority ?? "",
        category: request.filters?.category ?? "",
      });
      return api<PageResult<FeedbackSummary>>(
        `/feedback/manage?${params.toString()}`,
        { signal },
      );
    },
  });
  const detailQuery = useQuery({
    queryKey: ["feedback-detail", detailRow?.id],
    enabled: Boolean(detailRow) && canRead,
    queryFn: ({ signal }) => {
      const row = detailRow;
      if (!row) throw new Error("feedbackNotFound");
      return api<FeedbackDetail>(
        `/feedback/manage/${row.id}`,
        { signal },
      );
    },
  });

  useEffect(() => {
    if (createOpen && choices?.policy?.defaultPriority) {
      createForm.setFieldValue("priority", choices.policy.defaultPriority);
      if (!createForm.getFieldValue("category")) createForm.setFieldValue("category", categories[0]?.value);
    }
  }, [categories, choices?.policy?.defaultPriority, createForm, createOpen]);

  const label = (items: Choice[] | undefined, value: string) =>
    items?.find((item) => item.value === value)?.label ?? value;
  const resetCreate = () => {
    setCreateOpen(false);
    setDetailHtml("");
    setFormError(null);
    createForm.resetFields();
  };
  const openCreate = () => {
    setFormError(null);
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
      void message.success(t("feedbackSubmitted"));
      resetCreate();
      await refresh();
    } catch (error) {
      setFormError(messageKeyFor(error, feedbackErrorKeys));
    } finally {
      setCreatePending(false);
    }
  };

  const canUpdateCurrent = canRead && canUpdateStatus && detailQuery.data?.canUpdateStatus === true;
  const canReplyCurrent = canRead && canReply && detailQuery.data?.canReply === true;
  const canHandleCurrent = canUpdateCurrent || canReplyCurrent;

  const openHandle = () => {
    if (!detailQuery.data || !canHandleCurrent) return;
    handleForm.setFieldsValue({ status: detailQuery.data.status, adminReply: detailQuery.data.adminReply ?? "" });
    setFormError(null);
    setHandleError(null);
    setHandleOpen(true);
  };
  const saveHandle = async (values: { status: string; adminReply?: string }) => {
    if (!detailQuery.data || handlePending || !canHandleCurrent) return;
    const statusChanged = canUpdateCurrent && values.status !== detailQuery.data.status;
    const replyChanged = canReplyCurrent && (values.adminReply ?? "") !== (detailQuery.data.adminReply ?? "");
    if (!statusChanged && !replyChanged) {
      setHandleOpen(false);
      return;
    }
    setHandlePending(true);
    setHandleError(null);
    try {
      const result = await mutate<FeedbackDetail>(`/feedback/manage/${detailQuery.data.id}`, "PUT", {
        version: detailQuery.data.version,
        ...(statusChanged ? { status: values.status } : {}),
        ...(replyChanged ? { adminReply: values.adminReply ?? "" } : {}),
      });
      client.setQueryData(["feedback-detail", detailQuery.data.id], result);
      setHandleOpen(false);
      void message.success(t("feedbackUpdated"));
      await refresh();
    } catch (error) {
      setHandleError(messageKeyFor(error, feedbackErrorKeys));
      if (error instanceof ApiError && error.status === 409) await detailQuery.refetch();
    } finally {
      setHandlePending(false);
    }
  };

  return (
    <>
      <FeedbackBubble id="feedback-form-error" content={formError ? t(formError) : undefined}/>
      <Typography.Paragraph type="secondary" className="mb-4">{t("feedbackHint")}</Typography.Paragraph>
      {categoryPolicyUnavailable && <Alert type="warning" showIcon message={t("feedbackCategoriesUnavailable")} className="mb-4" />}
      {!canRead && canCreate && <div className="mb-4 flex justify-end"><Button type="primary" onClick={openCreate}>{t("createFeedback")}</Button></div>}
      {canRead && <ResourceTable<FeedbackSummary>
        result={listQuery.data}
        loading={listQuery.isFetching}
        error={listQuery.error}
        request={request}
        onChange={setRequest}
        onRetry={() => void listQuery.refetch()}
        searchInitialValues={{
          title: request.filters?.title,
          ownerName: request.filters?.ownerName,
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
            {canCreate && <Button type="primary" onClick={openCreate}>{t("createFeedback")}</Button>}
          </Space>
        }
        columns={[
          { title: t("feedbackTitle"), dataIndex: "title", search: true },
          { title: t("feedbackCategory"), dataIndex: "category", search: true, valueEnum: Object.fromEntries(categories.map((item) => [item.value, { text: item.label }])), render: (_: unknown, row: FeedbackSummary) => <Tag>{row.category ? label(categories, row.category) : t("uncategorized")}</Tag> },
          { title: t("feedbackOwner"), dataIndex: "ownerName", search: true, render: (_: unknown, row: FeedbackSummary) => row.ownerName ?? row.ownerId },
          { title: t("feedbackStatus"), dataIndex: "status", search: true, valueEnum: Object.fromEntries(statuses.map((item) => [item.value, { text: item.label }])), render: (_: unknown, row: FeedbackSummary) => <Tag color={statusColors[row.status]}>{label(statuses, row.status)}</Tag> },
          { title: t("feedbackPriority"), dataIndex: "priority", search: true, valueEnum: Object.fromEntries(priorities.map((item) => [item.value, { text: item.label }])), render: (_: unknown, row: FeedbackSummary) => <Tag color={priorityColors[row.priority] ?? "default"}>{label(priorities, row.priority)}</Tag> },
          { title: t("feedbackUpdatedAt"), dataIndex: "updatedAt", valueType: "dateTimeRange", render: (_: unknown, row: FeedbackSummary) => dateTime(row.updatedAt, locale) },
          { title: t("actions"), key: "actions", search: false, render: (_: unknown, row: FeedbackSummary) => <TableActions><Button type="link" onClick={() => { setDetailRow(row); setFormError(null); }}>{t("details")}</Button></TableActions> },
        ]}
      />}

      <FormDrawer open={createOpen} title={t("createFeedback")} pending={createPending} submitDisabled={!canCreate || options.isPending || options.isError || !categories.length} formId="feedback-create" size={620} onClose={resetCreate}>
        <Form id="feedback-create" name="feedback-create" form={createForm} layout="vertical" onFinish={saveCreate}>
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

      <PlatformDrawer open={Boolean(detailRow)} title={t("feedbackDetails")} size={720} destroyOnHidden onClose={() => setDetailRow(null)} footer={<Space><Button onClick={() => setDetailRow(null)}>{t("close")}</Button>{canHandleCurrent && <Button type="primary" onClick={openHandle}>{t("processFeedback")}</Button>}</Space>}>
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
          <FeedbackRichTextPreview html={detailQuery.data.detailHtml} />
          {detailQuery.data.adminReply && <div><Typography.Text strong>{t("feedbackReply")}</Typography.Text><Typography.Paragraph className="!mt-2 whitespace-pre-wrap">{detailQuery.data.adminReply}</Typography.Paragraph></div>}
        </Space>}
      </PlatformDrawer>

      <FormDrawer open={handleOpen} title={t("processFeedback")} pending={handlePending} formId="feedback-handle" submitDisabled={!canHandleCurrent || !detailQuery.data} onClose={() => { if (!handlePending) setHandleOpen(false); }}>
        <FeedbackBubble id="feedback-handle-error" content={handleError ? t(handleError) : undefined}/>
        <Form id="feedback-handle" name="feedback-handle" form={handleForm} layout="vertical" onFinish={saveHandle}>
          <Form.Item name="status" label={t("feedbackStatus")} rules={[{ required: true }]}><Select disabled={!canUpdateCurrent || handlePending} options={statuses.map((item) => ({ value: item.value, label: item.label }))} /></Form.Item>
          <Form.Item name="adminReply" label={t("feedbackReply")}><Input.TextArea disabled={!canReplyCurrent || handlePending} rows={6} maxLength={4000} showCount /></Form.Item>
        </Form>
      </FormDrawer>
    </>
  );
}

function FeedbackRichTextPreview({ html }: { html: string | null | undefined }) {
  const authorization = getAuthorizationContext();
  const revision = authorization?.revision ?? null;
  const previewActive = authorization?.preview?.state === "ACTIVE";
  const [previewHtml, setPreviewHtml] = useState("");
  const managedHtml = useMemo(() => {
    if (typeof DOMParser === "undefined") {
      return sanitizeRichText(html ?? "").replace(
        /(src=["'])\/api\/feedback\/images\/([0-9a-f-]{36})(["'])/gi,
        "$1/api/feedback/manage/images/$2$3",
      );
    }
    const document = new DOMParser().parseFromString(sanitizeRichText(html ?? ""), "text/html");
    document.body.querySelectorAll("img[src]").forEach(image => {
      const src = image.getAttribute("src") ?? "";
      if (/^\/api\/feedback\/images\/[0-9a-f-]{36}$/i.test(src)) {
        image.setAttribute("src", src.replace("/api/feedback/images/", "/api/feedback/manage/images/"));
      }
    });
    return document.body.innerHTML;
  }, [html]);

  useEffect(() => {
    if (!previewActive || !revision) {
      setPreviewHtml("");
      return;
    }

    const controller = new AbortController();
    const objectUrls: string[] = [];
    setPreviewHtml("");
    void (async () => {
      const document = new DOMParser().parseFromString(managedHtml, "text/html");
      const images = Array.from(document.body.querySelectorAll("img[src]"));
      await Promise.all(images.slice(0, 20).map(async image => {
        const src = image.getAttribute("src") ?? "";
        if (!/^\/api\/feedback\/manage\/images\/[0-9a-f-]{36}$/i.test(src)) {
          image.remove();
          return;
        }
        try {
          const response = await fetch(src, {
            credentials: "same-origin",
            cache: "no-store",
            signal: controller.signal,
            headers: { "X-Lumira-Authorization-Context-Revision": revision },
          });
          if (!response.ok || response.headers.get("Content-Type")?.split(";", 1)[0] !== "image/png") {
            image.remove();
            return;
          }
          const url = URL.createObjectURL(await response.blob());
          objectUrls.push(url);
          image.setAttribute("src", url);
        } catch {
          image.remove();
        }
      }));
      images.slice(20).forEach(image => image.remove());
      if (!controller.signal.aborted) setPreviewHtml(document.body.innerHTML);
    })();

    return () => {
      controller.abort();
      objectUrls.forEach(url => URL.revokeObjectURL(url));
    };
  }, [managedHtml, previewActive, revision]);

  if (!previewActive) return <RichTextPreview html={managedHtml} />;
  return <div className="rich-text-preview" dangerouslySetInnerHTML={{ __html: previewHtml }} />;
}
