"use client";

import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Spin, Tag, Typography } from "antd";
import { api } from "@/platform/api/client";
import type { UserSummary } from "@/platform/directory/api-types";
import { usePreferences } from "@/platform/providers";
import { profileFieldRuns, type ProfileFieldDefinition } from "@/platform/profile-fields/types";
import { formatQuotaLimitBytes, formatQuotaLimitCount } from "@/platform/i18n/format";

import type { ProfileValuesSnapshot } from "./user-editor";

function profileValue(field: ProfileFieldDefinition, snapshot: ProfileValuesSnapshot): string {
  const value = snapshot.values[field.key];
  if (value === undefined || value === null || value.trim() === "") return "—";

  const selection = snapshot.dictionarySelections[field.key];
  if (selection?.itemId === value && selection.label.trim() !== "") return selection.label;

  const option = field.options?.find(candidate => typeof candidate === "string" ? candidate === value : candidate.value === value);
  if (typeof option === "string") return option;
  if (option) return option.label;
  return value;
}

function DisplayValue({ value, preserveLines = false }: { value: string; preserveLines?: boolean }) {
  return <Typography.Text style={{ display: "block", whiteSpace: preserveLines ? "pre-wrap" : "normal", overflowWrap: "anywhere" }}>{value}</Typography.Text>;
}

function RetryNotice({ retry, pending }: { retry: () => void; pending: boolean }) {
  const { t } = usePreferences();
  return <Alert type="error" showIcon message={t("unavailable")} action={<Button type="link" size="small" loading={pending} onClick={retry}>{t("retry")}</Button>} />;
}

export function UserDetails({ user }: { user: UserSummary }) {
  const { t, locale } = usePreferences();
  const detail = useQuery({
    queryKey: ["user", user.id],
    queryFn: ({ signal }) => api<UserSummary>(`/platform/users/${user.id}`, { signal }),
    staleTime: 0,
  });
  const profileValues = useQuery({
    queryKey: ["user-profile-values", user.id],
    queryFn: ({ signal }) => api<ProfileValuesSnapshot>(`/platform/users/${user.id}/profile-values`, { signal }),
    staleTime: 0,
  });
  const visibleDetail = detail.data?.id === user.id ? detail.data : undefined;

  return <div className="flex min-w-0 flex-col gap-4 py-2">
    <section className="min-w-0">
      <Typography.Title level={5} className="mb-2">{t("userBasicInformation")}</Typography.Title>
      {detail.error && <RetryNotice retry={() => { void detail.refetch(); }} pending={detail.isFetching} />}
      {detail.isPending && !detail.data
        ? <Spin />
        : visibleDetail && <Descriptions size="small" bordered column={1} items={[
          { key: "username", label: t("username"), children: <DisplayValue value={visibleDetail.username} /> },
          { key: "displayName", label: t("name"), children: <DisplayValue value={visibleDetail.displayName} /> },
          { key: "enabled", label: t("status"), children: <Tag color={visibleDetail.enabled ? "success" : "default"}>{t(visibleDetail.enabled ? "enabled" : "disabled")}</Tag> },
        ]} />}
    </section>

    <section className="min-w-0">
      {visibleDetail && <Descriptions size="small" title={t("details")} bordered column={1} items={[
        { key: "id", label: t("id"), children: <DisplayValue value={visibleDetail.id} /> },
        { key: "version", label: t("version"), children: <DisplayValue value={String(visibleDetail.version)} /> },
      ]} />}
    </section>

    {visibleDetail && <section className="min-w-0">
      <Descriptions size="small" title={t("legacyQuotaRoleLabel")} bordered column={1} items={[
        { key: "fileQuotaBytesOverride", label: t("fileQuotaStorageLimit"), children: <DisplayValue value={formatQuotaLimitBytes(visibleDetail.fileQuotaBytesOverride ?? null,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})} /> },
        { key: "fileQuotaFilesOverride", label: t("fileQuotaFileCountLimit"), children: <DisplayValue value={formatQuotaLimitCount(visibleDetail.fileQuotaFilesOverride ?? null,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})} /> },
      ]} />
    </section>}

    <section className="min-w-0">
      <Typography.Title level={5} className="mb-2">{t("profile")}</Typography.Title>
      {profileValues.error && <RetryNotice retry={() => { void profileValues.refetch(); }} pending={profileValues.isFetching} />}
      {profileValues.isPending && !profileValues.data
        ? <Spin />
        : profileValues.data
          ? profileValues.data.fields.length === 0
            ? <Typography.Text type="secondary">{t("noCustomProfileFields")}</Typography.Text>
            : <div className="flex min-w-0 flex-col gap-3">{profileFieldRuns(profileValues.data.fields).map((run, index) => run.kind === "group"
              ? <div className="min-w-0" key={`${run.key}-${index}`}>
                  <Typography.Title level={5} className="mb-2">{run.label}</Typography.Title>
                  <Descriptions size="small" bordered column={1} items={run.fields.map(field => ({ key: field.key, label: field.label, children: <DisplayValue value={profileValue(field, profileValues.data!)} preserveLines={field.type === "TEXTAREA"} /> }))} />
                </div>
              : <Descriptions key={`${run.field.key}-${index}`} size="small" bordered column={1} items={[{ key: run.field.key, label: run.field.label, children: <DisplayValue value={profileValue(run.field, profileValues.data!)} preserveLines={run.field.type === "TEXTAREA"} /> }]} />,
            )}</div>
          : null}
    </section>
  </div>;
}
