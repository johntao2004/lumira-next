import type { ReactNode } from "react";
import type { MessageKey } from "@/platform/i18n/messages";
import { formatDateTime } from "@/platform/i18n/format";
import type { ProColumns } from "@/platform/components/resource-table";
import { formatFileSizeMb } from "./file-size";

type FileTableTranslate = (key: MessageKey) => string;
type FileIdentity = { originalName: string; sizeBytes: number | null };

export function fileNameColumn<T extends Pick<FileIdentity, "originalName">>({
  t,
  renderName,
}: {
  t: FileTableTranslate;
  renderName: (file: T) => ReactNode;
}): ProColumns<T> {
  return { title: t("fileName"), dataIndex: "originalName", search: true, render: (_name, file) => renderName(file) };
}

export function fileSizeColumn<T extends Pick<FileIdentity, "sizeBytes">>(t: FileTableTranslate, locale: string): ProColumns<T> {
  return { title: t("fileSize"), dataIndex: "sizeBytes", valueType: "digit", render: (_size, file) => formatFileSizeMb(file.sizeBytes, locale) };
}

/** Shared, presentation-only identity columns. Owner/admin APIs and row actions stay local to each page. */
export function fileIdentityColumns<T extends FileIdentity>({
  t,
  locale,
  renderName,
}: {
  t: FileTableTranslate;
  locale: string;
  renderName: (file: T) => ReactNode;
}): ProColumns<T>[] {
  return [fileNameColumn<T>({ t, renderName }), fileSizeColumn<T>(t, locale)];
}

export function fileCreatedAtColumn<T extends { createdAt: string }>(t: FileTableTranslate, locale: string): ProColumns<T> {
  return { title: t("eventTime"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_createdAt, file) => formatDateTime(file.createdAt, locale, { dateStyle: "medium", timeStyle: "short" }) };
}

export function fileDeletedAtColumn<T extends { trashedAt: string | null }>(t: FileTableTranslate, locale: string): ProColumns<T> {
  return { title: t("fileDeletedAt"), dataIndex: "trashedAt", valueType: "dateTimeRange", render: (_trashedAt, file) => file.trashedAt ? formatDateTime(file.trashedAt, locale, { dateStyle: "medium", timeStyle: "short" }) : "—" };
}
