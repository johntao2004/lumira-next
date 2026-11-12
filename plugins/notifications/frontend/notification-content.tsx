"use client";

import { Link } from "@/platform/routing/link";
import { Space, Typography } from "antd";
import { usePreferences } from "@/platform/providers";
import { isMessageKey } from "@/platform/i18n/messages";
import { formatDateTime } from "@/platform/i18n/format";
import { safeNotificationHref } from "./safe-notification-href";

export type NotificationEntry = {
  id: string; kind: string; titleKey: string; bodyKey: string; params: string;
  href: string | null; read: boolean; createdAt: string; expiresAt: string | null;
};

export function notificationParams(value: string): Record<string, string | number> {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    return Object.fromEntries(Object.entries(parsed).filter(([, item]) => typeof item === "string" || typeof item === "number"));
  } catch { return {}; }
}

export function NotificationContent({ title, body, href, createdAt, expiresAt, onNavigate }: {
  title: string; body: string; href?: string | null; createdAt?: string;
  expiresAt?: string | null; onNavigate?: () => void;
}) {
  const { t, locale } = usePreferences();
  const safeHref = safeNotificationHref(href);
  return <Space orientation="vertical" size="small" className="notification-item-content">
    <Typography.Text strong>{title}</Typography.Text>
    <Typography.Text type="secondary" className="notification-body">{body}</Typography.Text>
    {safeHref && <Link to={safeHref} onClick={onNavigate}>{t("notificationGoTo")}</Link>}
    {createdAt && <Typography.Text type="secondary">{formatDateTime(createdAt, locale, { dateStyle: "short", timeStyle: "short" })}</Typography.Text>}
    {expiresAt && <Typography.Text type="secondary">{t("notificationExpiry")}: {formatDateTime(expiresAt, locale, { dateStyle: "short", timeStyle: "short" })}</Typography.Text>}
  </Space>;
}

export function NotificationEntryContent({ entry, onNavigate }: { entry: NotificationEntry; onNavigate?: () => void }) {
  const { t } = usePreferences();
  const values = notificationParams(entry.params);
  const translateKey = (key: string) => isMessageKey(key) ? t(key, values) : key;
  return <NotificationContent title={translateKey(entry.titleKey)} body={translateKey(entry.bodyKey)} href={entry.href} createdAt={entry.createdAt} expiresAt={entry.expiresAt} onNavigate={onNavigate} />;
}
