"use client";

import { Button, Input, Select, Space, Typography, App } from "antd";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { FormDrawer } from "@/platform/components/form-drawer";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { api, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import { usePreferences } from "@/platform/providers";
import type { UserSummary } from "@/platform/directory/api-types";
import type { MessageKey } from "@/platform/i18n/messages";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";

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
type Draft = { scope: "ALL" | "USERS"; userIds?: string[]; title: string; body: string; href?: string; expiresAt?: string };
const notificationErrorKeys = ["notificationInvalid", "notificationNoRecipients", "notificationTooManyRecipients", "notificationRecipientNotFound", "notificationInvalidExpiry"] as const satisfies readonly MessageKey[];

function readParams(value: string) {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    return parsed as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function AdminNotificationsPage({ canWrite }: { canWrite: boolean }) {
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [form] = Form.useForm<Draft>();
  const scope = Form.useWatch("scope", form) ?? "ALL";
  const list = useQuery({
    queryKey: ["admin-notifications", request],
    queryFn: ({ signal }) => api<PageResult<AdminNotification>>(`/platform/notifications?${listRequestParams(request)}`, { signal }),
  });
  const users = useQuery({
    queryKey: ["notification-recipients"],
    queryFn: ({ signal }) => api<PageResult<UserSummary>>("/platform/users?page=1&pageSize=100&search=", { signal }),
    enabled: open && scope === "USERS",
  });

  const publish = async (values: Draft) => {
    setPending(true);
    try {
      await mutate("/platform/notifications", "POST", {
        ...values,
        userIds: values.scope === "USERS" ? values.userIds ?? [] : [],
        href: values.href?.trim() || null,
        expiresAt: values.expiresAt ? new Date(values.expiresAt).toISOString() : null,
      });
      message.success(t("notificationPublished"));
      setOpen(false);
      form.resetFields();
      await client.invalidateQueries({ queryKey: ["admin-notifications"] });
    } catch (error) {
      message.error(t(messageKeyFor(error, notificationErrorKeys, "notificationInvalid")));
    } finally {
      setPending(false);
    }
  };

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
        { title: t("notificationTitle"), dataIndex: "title", search: true, render: (_: unknown, item: AdminNotification) => <Typography.Text strong>{String(readParams(item.params).title ?? item.titleKey)}</Typography.Text> },
        { title: t("notificationBody"), dataIndex: "body", render: (_: unknown, item: AdminNotification) => <Typography.Text ellipsis={{ tooltip: true }}>{String(readParams(item.params).body ?? item.bodyKey)}</Typography.Text> },
        { title: t("status"), dataIndex: "read", search: true, valueEnum: { true: { text: t("read") }, false: { text: t("unread") } }, render: (_: unknown, item: AdminNotification) => item.read ? t("read") : t("unread") },
        { title: t("eventTime"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_: unknown, item: AdminNotification) => formatDateTime(item.createdAt, locale, { dateStyle: "medium", timeStyle: "short" }) },
      ]}
    />
    <FormDrawer open={open} title={t("publishNotification")} pending={pending} formId="notification-editor" size={560} onClose={() => setOpen(false)}>
      <Form id="notification-editor" form={form} layout="vertical" initialValues={{ scope: "ALL" }} onFinish={(values) => void publish(values)} disabled={pending || !canWrite}>
        <Form.Item name="scope" label={t("notificationTarget")} rules={[{ required: true }]}>
          <Select options={[{ value: "ALL", label: t("notificationAllUsers") }, { value: "USERS", label: t("notificationSelectedUsers") }]} />
        </Form.Item>
        {scope === "USERS" && <Form.Item name="userIds" label={t("notificationRecipients")} rules={[{ required: true, type: "array", min: 1 }]}>
          <Select mode="multiple" showSearch optionFilterProp="label" loading={users.isFetching} options={users.data?.items.map((user) => ({ value: user.id, label: `${user.displayName} (${user.username})` })) ?? []} />
        </Form.Item>}
        <Form.Item name="title" label={t("notificationTitle")} rules={[{ required: true, whitespace: true, max: 200 }]}><Input maxLength={200} /></Form.Item>
        <Form.Item name="body" label={t("notificationBody")} rules={[{ required: true, whitespace: true, max: 4_000 }]}><Input.TextArea rows={6} maxLength={4_000} showCount /></Form.Item>
        <Form.Item name="href" label={t("notificationLink")} rules={[{ pattern: /^\/(?!\/)[^\s]*$/, message: t("notificationInvalid") }]}><Input placeholder="/dashboard/home" maxLength={512} /></Form.Item>
        <Form.Item name="expiresAt" label={t("notificationExpiry")}><Input type="datetime-local" /></Form.Item>
      </Form>
    </FormDrawer>
  </>;
}
