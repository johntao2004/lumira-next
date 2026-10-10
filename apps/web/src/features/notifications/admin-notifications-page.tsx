"use client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";

import { Button, Card, DatePicker, Input, Modal, Select, Typography, App, type DatePickerProps } from "antd";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { FormDrawer } from "@/platform/components/form-drawer";
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
const notificationErrorKeys = ["notificationInvalid", "notificationNoRecipients", "notificationTooManyRecipients", "notificationRecipientNotFound", "notificationRoleNotFound", "notificationInvalidExpiry"] as const satisfies readonly MessageKey[];

export function AdminNotificationsPage({ canWrite }: { canWrite: boolean }) {
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [open, setOpen] = useState(false);
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
  });
  const roles = useQuery({
    queryKey: ["notification-recipient-roles"],
    queryFn: ({ signal }) => api<NotificationRole[]>("/platform/notifications/roles", { signal }),
    enabled: open && scope === "ROLES",
  });

  const publish = (values: Draft) => run(async () => {
      if(!canWrite)return false;
      await mutate("/platform/notifications", "POST", {
        ...values,
        userIds: values.scope === "USERS" ? values.userIds ?? [] : [],
        roleIds: values.scope === "ROLES" ? values.roleIds ?? [] : [],
        href: values.href?.trim() || null,
        expiresAt: values.expiresAt?.toISOString() ?? null,
      });
      message.success(t("notificationPublished"));
      setOpen(false);
      form.resetFields();
      await Promise.all([
        client.invalidateQueries({ queryKey: ["admin-notifications"] }),
        client.invalidateQueries({ queryKey: ["notifications"] }),
      ]);
  });

  return <>
    <ResourceTable<AdminNotification> 
      result={list.data}
      loading={list.isFetching}
      error={list.error}
      request={request}
      onChange={setRequest}
      onRetry={() => void list.refetch()}
      actions={canWrite ? <Button type="primary" onClick={() => { form.resetFields(); form.setFieldsValue({ scope: "ALL" }); setOpen(true); }}>{t("publishNotification")}</Button> : undefined}
      columns={[
        { title: t("notificationRecipients"), dataIndex: "ownerUsername", search: true, render: (_: unknown, item: AdminNotification) => `${item.ownerDisplayName || item.ownerUsername} (${item.ownerUsername})` },
        { title: t("notificationTitle"), dataIndex: "title", search: true, render: (_: unknown, item: AdminNotification) => <Typography.Text strong>{String(notificationParams(item.params).title ?? item.titleKey)}</Typography.Text> },
        { title: t("notificationBody"), dataIndex: "body", render: (_: unknown, item: AdminNotification) => <Typography.Text ellipsis={{ tooltip: true }}>{String(notificationParams(item.params).body ?? item.bodyKey)}</Typography.Text> },
        { title: t("status"), dataIndex: "read", search: true, valueEnum: { true: { text: t("read") }, false: { text: t("unread") } }, render: (_: unknown, item: AdminNotification) => item.read ? t("read") : t("unread") },
        { title: t("eventTime"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_: unknown, item: AdminNotification) => formatDateTime(item.createdAt, locale, { dateStyle: "medium", timeStyle: "short" }) },
        { title: t("actions"), key: "actions", valueType: "option", render: (_, item) => <Button type="link" size="small" onClick={() => setPreview(item)}>{t("notificationPreview")}</Button> },
      ]}
    />
    <FormDrawer open={open} title={t("publishNotification")} pending={pending} formId="notification-editor" size={560} onClose={() => setOpen(false)}>
      <Form id="notification-editor" name="notification-editor" form={form} layout="vertical" dateFormatter={false} initialValues={{ scope: "ALL" }} onFinish={(values) => void publish(values)} disabled={pending || !canWrite}>
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
    <Modal open={!!preview} title={t("notificationPreview")} footer={null} onCancel={() => setPreview(null)}>
      {preview && <NotificationEntryContent entry={{ ...preview, href: safeNotificationHref(preview.href) }} onNavigate={() => setPreview(null)} />}
    </Modal>
  </>;
}
