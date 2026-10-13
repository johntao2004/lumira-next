"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Pagination, Space, Spin, Tag, Typography } from "antd";
import { ApiError, api, mutate } from "@/platform/api/client";
import type { AuthorizationContext, CurrentUser, RolePreviewPage } from "@/platform/auth/api-types";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { formatDateTime } from "@/platform/i18n/format";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { AccountSettingsRow, AccountSettingsSection } from "./account-form-surface";

const rolePreviewErrors = ["authorizationContextChanged", "rolePreviewBlocked", "staleRecord", "forbidden", "unavailable"] as const satisfies readonly MessageKey[];

export function AccountRolePreviewPage() {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const [page, setPage] = useState(1);
  const [selectedRole, setSelectedRole] = useState<string | null>(null);
  const session = useQuery({
    queryKey: ["session"],
    queryFn: ({ signal }) => api<CurrentUser>("/auth/me", { signal }),
    staleTime: 0,
  });
  const authorizationContext = session.data?.authorizationContext;
  const supported = authorizationContext?.rolePreviewSupported === true;
  const revisionReady = supported && typeof authorizationContext.revision === "string" && authorizationContext.revision.length > 0;
  const state = useQuery({
    queryKey: ["role-preview"],
    queryFn: ({ signal }) => api<AuthorizationContext>("/account/role-preview", { signal }),
    enabled: revisionReady,
    staleTime: 0,
  });
  const candidates = useQuery({
    queryKey: ["role-preview-roles", page],
    queryFn: ({ signal }) => api<RolePreviewPage>(`/account/role-preview/roles?page=${page}&pageSize=20`, { signal }),
    enabled: revisionReady,
    staleTime: 0,
  });
  const submit = useSubmitAction({ errorKeys: rolePreviewErrors });
  const current = state.data ?? authorizationContext ?? null;

  async function selectRole(role: RolePreviewPage["items"][number]) {
    const expectedRevision = current?.revision;
    if (!expectedRevision || selectedRole) return;
    setSelectedRole(role.id);
    await submit.run(async () => {
      const updated = await mutate<AuthorizationContext>("/account/role-preview", "POST", {
        roleId: role.id,
        roleVersion: role.version,
        authorizationContextRevision: expectedRevision,
      });
      client.setQueryData(["session"], (previous: CurrentUser | undefined) => previous ? { ...previous, authorizationContext: updated } : previous);
      await client.invalidateQueries();
      return true;
    });
    setSelectedRole(null);
  }

  async function exitPreview() {
    const expectedRevision = current?.revision;
    if (!expectedRevision || selectedRole) return;
    setSelectedRole("__exit__");
    await submit.run(async () => {
      await mutate<void>("/account/role-preview", "DELETE", { authorizationContextRevision: expectedRevision });
      await client.invalidateQueries();
      return true;
    });
    setSelectedRole(null);
  }

  if (session.isPending) return <Spin />;
  if (session.error) return <Alert type="error" showIcon message={t("unavailable")} action={<Button type="link" onClick={() => void session.refetch()}>{t("retry")}</Button>} />;
  if (!supported) return <Alert type="info" showIcon message={t("rolePreviewNotAvailable")} />;
  if (!revisionReady) return <Alert type="warning" showIcon message={t("authorizationContextRefreshing")} action={<Button type="link" onClick={() => void session.refetch()}>{t("retry")}</Button>} />;

  const displayState = state.data ?? authorizationContext;
  const activePreview = displayState?.preview ?? null;
  return <div className="account-settings-page">
    <AccountSettingsSection>
      {state.isPending ? <Spin /> : state.error ? <Alert type="error" showIcon message={t("unavailable")} action={<Button type="link" onClick={() => void state.refetch()}>{t("retry")}</Button>} /> : activePreview ? <Space direction="vertical" className="w-full">
        <Alert
          showIcon
          type={activePreview.state === "BLOCKED" ? "warning" : "info"}
          message={t(activePreview.state === "BLOCKED" ? "rolePreviewBlocked" : "rolePreviewActive", { role: activePreview.roleName })}
          description={<Space direction="vertical" size={2}>
            <Typography.Text>{t("rolePreviewActor", { name: session.data.displayName })}</Typography.Text>
            {activePreview.limitedByActor && <Typography.Text type="secondary">{t("rolePreviewLimitedByActor")}</Typography.Text>}
            <Typography.Text type="secondary">{t("rolePreviewExpires", { time: formatDateTime(activePreview.expiresAt, locale) })}</Typography.Text>
          </Space>}
        />
        <Space wrap>
          {activePreview.state === "BLOCKED" && <Button onClick={() => { setPage(1); void candidates.refetch(); }}>{t("rolePreviewChoose")}</Button>}
          <Button danger loading={selectedRole === "__exit__"} disabled={Boolean(selectedRole)} onClick={() => void exitPreview()}>{t("rolePreviewExit")}</Button>
        </Space>
      </Space> : <Typography.Paragraph type="secondary">{t("rolePreviewChooseHint")}</Typography.Paragraph>}
      <FeedbackBubble id="role-preview-error" content={submit.error ? t(submit.error) : undefined} />
    </AccountSettingsSection>

    <AccountSettingsSection title={t("rolePreviewChoose")}>
      {candidates.isPending ? <Spin /> : candidates.error ? <Alert type="error" showIcon message={candidateError(candidates.error, t)} action={<Button type="link" onClick={() => void candidates.refetch()}>{t("retry")}</Button>} /> : candidates.data?.items.length ? <>
        {candidates.data.items.map(role => <AccountSettingsRow
          key={role.id}
          label={<Space wrap><Typography.Text strong>{role.name}</Typography.Text><Tag>{role.code}</Tag></Space>}
          value={<Space direction="vertical" size={2}>
            <Typography.Text type="secondary">{t("dataScope")}: {role.dataScope}</Typography.Text>
            {role.limitedByActor && <Typography.Text type="secondary">{t("rolePreviewLimitedByActor")}</Typography.Text>}
          </Space>}
          action={<Button
            type="primary"
            disabled={Boolean(selectedRole) || (activePreview?.state === "ACTIVE" && activePreview.roleId === role.id)}
            loading={selectedRole === role.id}
            onClick={() => void selectRole(role)}
          >{t(activePreview?.roleId === role.id ? "rolePreviewActive" : "rolePreviewStart", { role: role.name })}</Button>}
        />)}
        <Pagination className="mt-4" current={candidates.data.page} pageSize={candidates.data.pageSize} total={candidates.data.total} showSizeChanger={false} onChange={setPage} />
      </> : <Typography.Text type="secondary">{t("rolePreviewNoRoles")}</Typography.Text>}
    </AccountSettingsSection>
  </div>;
}

function candidateError(error: unknown, t: ReturnType<typeof usePreferences>["t"]) {
  if (error instanceof ApiError && error.message === "rolePreviewBlocked") return t("rolePreviewBlocked");
  if (error instanceof ApiError && error.message === "authorizationContextChanged") return t("authorizationContextChanged");
  return t(error instanceof ApiError && error.status === 403 ? "forbidden" : "unavailable");
}
