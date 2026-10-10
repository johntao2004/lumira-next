"use client";

import { Alert, Card, Descriptions, Space, Spin, Tag, Typography } from "antd";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/platform/api/client";
import type { AuthenticationPolicy, PrimaryMethod } from "@/platform/auth/api-types";
import { QueryFailure } from "@/platform/components/query-failure";
import { frontendRouteOwner, renderFrontendPage } from "@/platform/modules/registry";
import { usePluginRuntime } from "@/platform/modules/plugin-runtime";
import { usePreferences } from "@/platform/providers";

const methodKeys: Record<PrimaryMethod, "passwordLogin" | "passkeyLogin" | "emailOtpLogin" | "smsOtpLogin" | "wechatLogin"> = {
  PASSWORD: "passwordLogin",
  PASSKEY: "passkeyLogin",
  EMAIL_OTP: "emailOtpLogin",
  SMS_OTP: "smsOtpLogin",
  WECHAT: "wechatLogin",
};

export function AccountSecurityPage() {
  const { t } = usePreferences();
  const plugins = usePluginRuntime();
  const query = useQuery({
    queryKey: ["authentication-policy"],
    queryFn: ({ signal }) => api<AuthenticationPolicy>("/account/authentication-policy", { signal }),
  });
  const mfaInstalled = plugins.data?.some(plugin => plugin.id === "mfa" && plugin.enabled && plugin.status === "RUNNING") ?? false;
  const hasMfaPage = mfaInstalled && frontendRouteOwner("mfa-authenticator") === "mfa";
  const mfaPage = hasMfaPage ? renderFrontendPage("mfa-authenticator", { authorities: [], hasGlobal: () => false }) : null;

  return <Space direction="vertical" size="large" className="w-full">
    <Card title={t("accountAuthenticationPolicy")}>
      {query.isPending ? <Spin /> : query.error ? <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} /> : query.data ? <>
        <Descriptions column={{ xs: 1, md: 2 }} items={[
          { key: "allowed", label: t("authenticationAllowedMethods"), children: methodTags(query.data.enabledMethods, t) },
          { key: "usable", label: t("authenticationUsableMethods"), children: methodTags(query.data.usableMethods, t) },
          { key: "required", label: t("authenticationMfaRequirement"), children: <Tag color={query.data.mfaRequired ? "warning" : "default"}>{t(query.data.mfaRequired ? "mfaRequired" : "mfaNotRequired")}</Tag> },
          { key: "sources", label: t("authenticationRequirementSources"), children: <Space wrap>{query.data.requirementSources.length ? query.data.requirementSources.map((source, index) => <Tag key={`${source.kind}:${source.roleId ?? ""}:${index}`}>{t(source.kind === "GLOBAL" ? "authSourceGlobal" : source.kind === "ROLE" ? "authSourceRole" : source.kind === "SELF_ENABLED" ? "authSourceSelfEnabled" : "authSourceRecoveryPending")}{source.roleId ? ` · ${source.roleId}` : ""}</Tag>) : <Typography.Text type="secondary">—</Typography.Text>}</Space> },
          { key: "provider", label: t("authenticationAuthenticatorStatus"), children: authenticatorLabel(query.data.authenticator, t) },
          { key: "recovery", label: t("mfaRecoveryRemaining"), children: query.data.authenticator.recoveryCodesRemaining ?? "—" },
        ]} />
      </> : null}
    </Card>

    {hasMfaPage && mfaPage ? mfaPage : <Alert type="info" showIcon title={t("mfaProviderUnavailableShort")} />}
  </Space>;
}

function methodTags(methods: string[], t: ReturnType<typeof usePreferences>["t"]) {
  if (!methods.length) return <Typography.Text type="secondary">—</Typography.Text>;
  return <Space wrap>{methods.map(method => <Tag key={method}>{methodKeys[method as PrimaryMethod] ? t(methodKeys[method as PrimaryMethod]) : method}</Tag>)}</Space>;
}

function authenticatorLabel(value: AuthenticationPolicy["authenticator"], t: ReturnType<typeof usePreferences>["t"]) {
  if (!value.available) return <Tag>{t("authenticationProviderUnavailable")}</Tag>;
  if (value.configured === null) return <Tag>{t("authenticationProviderStateUnknown")}</Tag>;
  return <Tag color={value.configured ? "success" : "default"}>{t(value.configured ? "mfaConfigured" : "mfaUnbound")}</Tag>;
}
