"use client";

import { useEffect, useState } from "react";
import { Badge, Button, Empty, Flex, Listy, Pagination, Popover, Spin, Tabs, Typography } from "antd";
import { BellOutlined, CheckOutlined } from "@ant-design/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, isRolePreviewReadOnly, mutate } from "@/platform/api/client";
import { usePreferences } from "@/platform/providers";
import type { PageResult } from "@/platform/api/pagination";
import { NotificationEntryContent, type NotificationEntry } from "./notification-content";
import { safeNotificationHref } from "./safe-notification-href";
import { useFragmentParams, useTabAnchor } from "@/platform/routing/use-tab-anchor";

export function NotificationsButton() {
  const { t } = usePreferences();
  const previewReadOnly = isRolePreviewReadOnly();
  const client = useQueryClient();
  const [fragment, updateFragment] = useFragmentParams();
  const open = fragment.get("inbox") === "open";
  const setOpen = (next: boolean) => updateFragment({ inbox: next ? "open" : null });
  const [readState, setReadState] = useTabAnchor("notifications", ["unread", "read"], "unread", open);
  const [page, setPage] = useState(1);
  useEffect(() => { setPage(1); }, [readState]);
  const pageSize = 8;
  const count = useQuery({ queryKey: ["notifications", "unread-count"], queryFn: () => api<number>("/account/notifications/unread-count"), refetchInterval: 5_000, refetchOnWindowFocus: true });
  const list = useQuery({ queryKey: ["notifications", "inbox", readState, page], queryFn: () => api<PageResult<NotificationEntry>>(`/account/notifications?page=${page}&pageSize=${pageSize}&read=${readState === "read"}`), enabled: open, staleTime: 0, refetchInterval: 5_000, refetchOnWindowFocus: true });
  const refresh = () => {
    setPage(1);
    void client.invalidateQueries({ queryKey: ["notifications"] });
    void client.invalidateQueries({ queryKey: ["admin-notifications"] });
  };
  const markRead = useMutation({ mutationFn: (id: string) => mutate(`/account/notifications/${id}/read`, "POST"), onSuccess: refresh });
  const markAll = useMutation({ mutationFn: () => mutate("/account/notifications/read-all", "POST"), onSuccess: refresh });
  const markReadIfAllowed = (id: string) => { if (!previewReadOnly) markRead.mutate(id); };
  const markAllIfAllowed = () => { if (!previewReadOnly) markAll.mutate(); };
  const title = <Flex align="center" justify="space-between" gap="small">
    <Typography.Text strong>{t("notifications")}</Typography.Text>
    {!previewReadOnly && <Button type="link" size="small" icon={<CheckOutlined />} disabled={markAll.isPending || !(count.data ?? 0)} onClick={markAllIfAllowed}>{t("markAllRead")}</Button>}
  </Flex>;
  const inbox = <>
    <div className="notification-inbox">
    {list.isPending ? <Flex align="center" justify="center" style={{ padding: 24 }}><Spin /></Flex> : list.error ? <Typography.Text type="secondary">{t(list.error instanceof ApiError && list.error.status === 403 ? "forbidden" : "unavailable")}</Typography.Text> : !list.data?.items.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("noNotifications")} /> : <Listy items={list.data.items} rowKey="id" virtual={false} itemRender={(entry) => {
      return <Flex align="center" justify="space-between" gap="middle">
        <Flex flex={1} align="flex-start" gap="small" style={{ minWidth: 0 }}>
          <Badge dot={!entry.read} style={{ flexShrink: 0 }}><BellOutlined /></Badge>
          <NotificationEntryContent entry={{ ...entry, href: safeNotificationHref(entry.href) }} onNavigate={() => { if (!entry.read) markReadIfAllowed(entry.id); setOpen(false); }} />
        </Flex>
        {!previewReadOnly && !entry.read && <Button type="link" size="small" style={{ flexShrink: 0 }} disabled={markRead.isPending} onClick={() => markReadIfAllowed(entry.id)}>{t("notificationMarkRead")}</Button>}
      </Flex>;
    }} />}
    </div>
    {!list.error && <Flex justify="flex-end"><Pagination simple size="small" hideOnSinglePage current={page} pageSize={pageSize} total={list.data?.total ?? 0} onChange={setPage} /></Flex>}
  </>;
  const content = <div className="notification-popover">
    <Tabs size="small" styles={{ header: { marginBottom: 0 } }} activeKey={readState} destroyOnHidden onChange={(key) => { setReadState(key === "read" ? "read" : "unread"); setPage(1); }} items={[
      { key: "unread", label: t("unread"), children: inbox },
      { key: "read", label: t("read"), children: inbox },
    ]} />
  </div>;
  return <Popover open={open} onOpenChange={setOpen} trigger="click" placement="bottom" content={content} title={title}>
    <Button className="notification-action-button" type="text" aria-label={t("notifications")} icon={<Badge count={count.data ?? 0} overflowCount={999} size="small" offset={[2, 0]}><BellOutlined /></Badge>} />
  </Popover>;
}
