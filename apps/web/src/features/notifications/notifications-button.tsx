"use client";

import { useState } from "react";
import { Link } from "@umijs/max";
import { Badge, Button, Empty, Listy, Popover, Space, Spin, Typography } from "antd";
import { BellOutlined, CheckOutlined } from "@ant-design/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, mutate } from "@/platform/api/client";
import { usePreferences } from "@/platform/providers";
import { isMessageKey } from "@/platform/i18n/messages";
import type { PageResult } from "@/platform/api/pagination";
import { formatDateTime } from "@/platform/i18n/format";

type Entry = { id: string; kind: string; titleKey: string; bodyKey: string; params: string; href: string | null; read: boolean; createdAt: string; expiresAt: string | null };

function params(value: string): Record<string, string | number> {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    return Object.fromEntries(Object.entries(parsed as Record<string, unknown>).filter(([, item]) => typeof item === "string" || typeof item === "number")) as Record<string, string | number>;
  } catch { return {}; }
}

export function NotificationsButton() {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const count = useQuery({ queryKey: ["notifications", "unread-count"], queryFn: () => api<number>("/account/notifications/unread-count"), refetchInterval: 15_000 });
  const list = useQuery({ queryKey: ["notifications", "inbox"], queryFn: () => api<PageResult<Entry>>("/account/notifications?page=1&pageSize=8&unread=false"), enabled: open, staleTime: 0 });
  const markRead = useMutation({ mutationFn: (id: string) => mutate(`/account/notifications/${id}/read`, "POST"), onSuccess: () => { void client.invalidateQueries({ queryKey: ["notifications"] }); } });
  const markAll = useMutation({ mutationFn: () => mutate("/account/notifications/read-all", "POST"), onSuccess: () => { void client.invalidateQueries({ queryKey: ["notifications"] }); } });
  const translateKey = (key: string, values: Record<string, string | number>) => {
    return isMessageKey(key) ? t(key, values) : key;
  };
  const content = <div className="notification-popover">
    <div className="notification-popover-header">
      <Typography.Text strong>{t("notifications")}</Typography.Text>
      <Button type="link" size="small" icon={<CheckOutlined />} disabled={markAll.isPending || !(count.data ?? 0)} onClick={() => markAll.mutate()}>{t("markAllRead")}</Button>
    </div>
    {list.isPending ? <div className="grid place-content-center p-6"><Spin /></div> : list.error ? <Typography.Text type="secondary">{t(list.error instanceof ApiError && list.error.status === 403 ? "forbidden" : "unavailable")}</Typography.Text> : !list.data?.items.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("noNotifications")} /> : <Listy items={list.data.items} rowKey="id" virtual={false} itemRender={(entry) => {
      const values = params(entry.params);
      const body = <Space orientation="vertical" size={0} className={entry.read ? "notification-item-read" : ""}><Typography.Text strong={!entry.read}>{translateKey(entry.titleKey, values)}</Typography.Text><Typography.Text type="secondary">{translateKey(entry.bodyKey, values)}</Typography.Text><Typography.Text type="secondary" className="notification-time">{formatDateTime(entry.createdAt, locale, { dateStyle: "short", timeStyle: "short" })}</Typography.Text></Space>;
      return <div className={entry.read ? "notification-item-read" : "notification-item-unread"} onClick={() => { if (!entry.read) markRead.mutate(entry.id); }}><div className="notification-item-content">{entry.href ? <Link to={entry.href}>{body}</Link> : body}</div></div>;
    }} />}
  </div>;
  return <Popover open={open} onOpenChange={setOpen} trigger="click" placement="bottomRight" content={content} title={null} overlayClassName="notification-popover-overlay">
    <Badge count={count.data ?? 0} overflowCount={99} size="small"><Button className="notification-action-button" type="text" aria-label={t("notifications")} icon={<BellOutlined />} /></Badge>
  </Popover>;
}
