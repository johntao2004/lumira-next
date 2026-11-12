"use client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";

import { Alert, Button, Card, DatePicker, Input, Select, Space, Typography, App, type DatePickerProps } from "antd";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { FormDrawer } from "@/platform/components/form-drawer";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { QueryFailure } from "@/platform/components/query-failure";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { api, mutate } from "@/platform/api/client";
import { usePreferences } from "@/platform/providers";
import { ResourceSelect } from "@/platform/components/resource-select";
import type { MessageKey } from "@/platform/i18n/messages";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import { NotificationContent, NotificationEntryContent, notificationParams } from "./notification-content";
import { safeNotificationHref } from "./safe-notification-href";
import { EventTraceDrawer } from "@/platform/events/event-trace-drawer";
import type { EventTraceTarget } from "@/platform/events/event-trace-drawer";

type AdminNotification = {
  id: string;
  recipientId: string;
  ownerUsername: string;
  ownerDisplayName: string;
  kind: string;
  titleKey: string;
  bodyKey: string;
  params: string;
  href: string | null;
  read: boolean;
  createdAt: string;
  expiresAt: string | null;
};
type Draft = { scope: "ALL" | "USERS" | "ROLES"; userIds?: string[]; roleIds?: string[]; title: string; body: string; href?: string; expiresAt?: Exclude<DatePickerProps["value"], unknown[]> };
type NotificationRole = { id: string; code: string; name: string };
type BroadcastResponse = { broadcastId: string; recipientCount: number; operationId?: string; auditOperationId?: string | null; eventIds?: string[] };
type PublishedTrace = { target: EventTraceTarget | null; limitedPermission: boolean; requiresAudit: boolean; operationId: string | null };
const notificationErrorKeys = ["notificationInvalid", "notificationNoRecipients", "notificationTooManyRecipients", "notificationRecipientNotFound", "notificationRoleNotFound", "notificationInvalidExpiry"] as const satisfies readonly MessageKey[];

export function AdminNotificationsPage({ canCreate, canTraceEvents = false, canTraceOperations = false }: { canCreate: boolean; canTraceEvents?: boolean; canTraceOperations?: boolean }) {
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [open, setOpen] = useState(false);
  const [publishedTrace, setPublishedTrace] = useState<PublishedTrace | null>(null);
  const [traceTarget, setTraceTarget] = useState<EventTraceTarget | null>(null);
  const {pending,run}=useSubmitAction({errorKeys:notificationErrorKeys,fallback:"notificationInvalid",onError:key=>{void message.error(t(key));}});
  const [preview, setPreview] = useState<AdminNotification | null>(null);
  const [form] = Form.useForm<Draft>();
  const scope = Form.useWatch("scope", form) ?? "ALL";
  const title = Form.useWatch("title", form);
  const body = Form.useWatch("body", form);
  const href = Form.useWatch("href", form);
  const expiresAt = Form.useWatch("expiresAt", form);
  const list = useQuery({
    queryKey: ["admin-notifications", request],
    queryFn: ({ signal }) => api<PageResult<AdminNotification>>(`/platform/notifications?${listRequestParams(request)}`, { signal }),
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  });
  const roles = useQuery({
    queryKey: ["notification-recipient-roles"],
    queryFn: ({ signal }) => api<NotificationRole[]>("/platform/notifications/roles", { signal }),
    enabled: open && scope === "ROLES",
  });

  const publish = (values: Draft) => run(async () => {
      if(!canCreate)return false;
      const published = await mutate<BroadcastResponse>("/platform/notifications", "POST", {
        ...values,
        userIds: values.scope === "USERS" ? values.userIds ?? [] : [],
        roleIds: values.scope === "ROLES" ? values.roleIds ?? [] : [],
        href: values.href?.trim() || null,
        expiresAt: values.expiresAt?.toISOString() ?? null,
      });
      message.success(t("notificationQueued"));
      if (canTraceEvents) {
        const firstEventId = published.eventIds?.[0];
        const target: EventTraceTarget | null = canTraceOperations && published.auditOperationId
          ? { kind: "operation", auditId: published.auditOperationId }
          : firstEventId ? { kind: "event", eventId: firstEventId } : null;
        const operationId = published.operationId ?? published.broadcastId ?? null;
        const requiresAudit = !canTraceOperations && !firstEventId && Boolean(operationId);
        setPublishedTrace(target || requiresAudit ? {
          target,
          limitedPermission: Boolean(target && target.kind === "event" && published.auditOperationId && !canTraceOperations),
          requiresAudit,
          operationId,
        } : null);
      } else setPublishedTrace(null);
      setOpen(false);
      form.resetFields();
      await Promise.all([
        client.invalidateQueries({ queryKey: ["admin-notifications"] }),
        client.invalidateQueries({ queryKey: ["notifications"] }),
      ]);
  });

  return <>
    {publishedTrace && <Alert
      style={{ marginBottom: 16 }}
      type="success"
      showIcon
      closable
      onClose={() => setPublishedTrace(null)}
      message={publishedTrace.requiresAudit ? <Space direction="vertical" size={4}>
        <Typography.Text>{t("eventTraceRequiresAudit")}</Typography.Text>
        {publishedTrace.operationId && <Typography.Text type="secondary">{t("eventTraceOperationId")}: <Typography.Text code copyable style={{ overflowWrap: "anywhere" }}>{publishedTrace.operationId}</Typography.Text></Typography.Text>}
      </Space> : t("notificationQueued")}
      action={publishedTrace.target ? <Button type="link" size="small" data-testid="event-trace-notification" onClick={() => setTraceTarget(publishedTrace.target)}>{t(publishedTrace.limitedPermission ? "eventTraceOpenFirstEvent" : "eventTraceOpen")}</Button> : undefined}
    />}
    <ResourceTable<AdminNotification> 
      result={list.data}
      loading={list.isFetching}
      error={list.error}
      request={request}
      onChange={setRequest}
      onRetry={() => void list.refetch()}
      actions={canCreate ? <Button type="primary" onClick={() => { form.resetFields(); form.setFieldsValue({ scope: "ALL" }); setOpen(true); }}>{t("publishNotification")}</Button> : undefined}
      columns={[
        { title: t("serialNumber"), key: "serialNumber", width: 80, search: false, render: (_, __, index) => (request.page - 1) * request.pageSize + index + 1 },
        { title: t("notificationRecipients"), dataIndex: "ownerUsername", search: true, render: (_: unknown, item: AdminNotification) => `${item.ownerDisplayName || item.ownerUsername} (${item.ownerUsername})` },
        { title: t("notificationTitle"), dataIndex: "title", search: true, render: (_: unknown, item: AdminNotification) => <Typography.Text strong>{String(notificationParams(item.params).title ?? item.titleKey)}</Typography.Text> },
        { title: t("notificationBody"), dataIndex: "body", width: 360, ellipsis: { showTitle: false }, render: (_: unknown, item: AdminNotification) => <Typography.Text ellipsis={{ tooltip: true }} style={{ display: "block", maxWidth: 340 }}>{String(notificationParams(item.params).body ?? item.bodyKey)}</Typography.Text> },
        { title: t("status"), dataIndex: "read", search: true, valueEnum: { true: { text: t("read") }, false: { text: t("unread") } }, render: (_: unknown, item: AdminNotification) => item.read ? t("read") : t("unread") },
        { title: t("eventTime"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_: unknown, item: AdminNotification) => formatDateTime(item.createdAt, locale, { dateStyle: "medium", timeStyle: "short" }) },
        { title: t("actions"), key: "actions", valueType: "option", render: (_, item) => <Button type="link" size="small" onClick={() => setPreview(item)}>{t("notificationPreview")}</Button> },
      ]}
    />
    <FormDrawer open={open} title={t("publishNotification")} pending={pending} formId="notification-editor" size={560} onClose={() => setOpen(false)}>
      <Form id="notification-editor" name="notification-editor" form={form} layout="vertical" dateFormatter={false} initialValues={{ scope: "ALL" }} onFinish={(values) => void publish(values)} disabled={pending || !canCreate}>
        <Form.Item name="scope" label={t("notificationTarget")} rules={[{ required: true }]}>
          <Select onChange={() => form.setFieldsValue({ userIds: [], roleIds: [] })} options={[{ value: "ALL", label: t("notificationAllUsers") }, { value: "USERS", label: t("notificationSelectedUsers") }, { value: "ROLES", label: t("notificationSelectedRoles") }]} />
        </Form.Item>
        {scope === "USERS" && <Form.Item name="userIds" label={t("notificationRecipients")} rules={[{ required: true, type: "array", min: 1 }]}>
          <ResourceSelect endpoint="/platform/notifications/recipients" multiple max={10_000} optionLabel={(user) => `${user.displayName || user.username || user.id}${user.username ? ` (${user.username})` : ""}`} />
        </Form.Item>}
        {scope === "ROLES" && <>
          {roles.error && <QueryFailure title={t("notificationRolesUnavailable")} pending={roles.isFetching} onRetry={() => void roles.refetch()} />}
          <Form.Item name="roleIds" label={t("notificationRoleRecipients")} rules={[{ required: true, type: "array", min: 1 }]}>
            <Select mode="multiple" showSearch optionFilterProp="label" loading={roles.isFetching} notFoundContent={roles.isFetching ? undefined : t("notificationNoRoles")} options={roles.data?.map((role) => ({ value: role.id, label: `${role.name} (${role.code})` })) ?? []} />
          </Form.Item>
        </>}
        <Form.Item name="title" label={t("notificationTitle")} rules={[{ required: true, whitespace: true, max: 200 }]}><Input maxLength={200} /></Form.Item>
        <Form.Item name="body" label={t("notificationBody")} rules={[{ required: true, whitespace: true, max: 4_000 }]}><Input.TextArea rows={6} maxLength={4_000} showCount /></Form.Item>
        <Form.Item name="href" label={t("notificationLink")} rules={[{ validator: (_, value: unknown) => {
          if (value === undefined || value === null || value === "") return Promise.resolve();
          if (typeof value === "string" && !/[\u0000-\u001f\u007f-\u009f]/.test(value) && value.trim() === "") return Promise.resolve();
          if (typeof value === "string" && safeNotificationHref(value.trim())) return Promise.resolve();
          return Promise.reject(new Error(t("notificationInvalid")));
        } }]}><Input placeholder="/dashboard/home" maxLength={512} /></Form.Item>
        <Form.Item name="expiresAt" label={t("notificationExpiry")} rules={[{ validator: (_, value: Draft["expiresAt"]) => !value || (value.isValid() && value.valueOf() > Date.now()) ? Promise.resolve() : Promise.reject(new Error(t("notificationInvalidExpiry"))) }]}>
          <DatePicker showTime format="YYYY-MM-DD HH:mm" inputReadOnly style={{ width: "100%" }} disabledDate={(date) => date.endOf("day").valueOf() < Date.now()} />
        </Form.Item>
        <Card size="small" title={t("notificationPreview")} className="notification-preview">
          <NotificationContent title={title?.trim() || t("notificationTitle")} body={body?.trim() || t("notificationBody")} href={safeNotificationHref(href?.trim())} expiresAt={expiresAt?.isValid() ? expiresAt.toISOString() : null} onNavigate={() => setOpen(false)} />
        </Card>
      </Form>
    </FormDrawer>
    <PlatformDrawer open={!!preview} placement="right" size={520} title={t("notificationPreview")} onClose={() => setPreview(null)} destroyOnHidden>
      {preview && <NotificationEntryContent entry={{ ...preview, href: safeNotificationHref(preview.href) }} onNavigate={() => setPreview(null)} />}
    </PlatformDrawer>
    {traceTarget && <EventTraceDrawer target={traceTarget} limitedPermission={Boolean(publishedTrace?.limitedPermission && traceTarget.kind === "event")} onClose={() => setTraceTarget(null)} />}
  </>;
}
