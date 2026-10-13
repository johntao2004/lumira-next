"use client";

import { useState } from "react";
import { Alert, Button, Space, Spin, Tag, Typography } from "antd";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/platform/api/client";
import type { AuthenticationPolicy, PrimaryMethod } from "@/platform/auth/api-types";
import { QueryFailure } from "@/platform/components/query-failure";
import { frontendRouteOwner, renderFrontendPage } from "@/platform/modules/registry";
import { usePluginRuntime } from "@/platform/modules/plugin-runtime";
import { usePreferences } from "@/platform/providers";
import { AccountSettingsRow, AccountSettingsSection } from "./account-form-surface";
import { EmailDrawer } from "./email-drawer";
import { PasskeysDrawer } from "./passkeys-drawer";
import { PhoneDrawer } from "./phone-drawer";
import { SecurityEventsDrawer } from "./security-events-drawer";

const methodKeys: Record<PrimaryMethod, "passwordLogin" | "passkeyLogin" | "emailOtpLogin" | "smsOtpLogin" | "wechatLogin"> = {
  PASSWORD: "passwordLogin",
  PASSKEY: "passkeyLogin",
  EMAIL_OTP: "emailOtpLogin",
  SMS_OTP: "smsOtpLogin",
  WECHAT: "wechatLogin",
};

export function AccountSecurityPage() {
  const { t } = usePreferences();
  const [emailOpen, setEmailOpen] = useState(false);
  const [phoneOpen, setPhoneOpen] = useState(false);
  const [passkeyOpen, setPasskeyOpen] = useState(false);
  const [securityEventsOpen, setSecurityEventsOpen] = useState(false);
  const plugins = usePluginRuntime();
  const query = useQuery({
    queryKey: ["authentication-policy"],
    queryFn: ({ signal }) => api<AuthenticationPolicy>("/account/authentication-policy", { signal }),
  });
  const mfaInstalled = plugins.data?.some(plugin => plugin.id === "mfa" && plugin.enabled && plugin.status === "RUNNING") ?? false;
  const hasMfaPage = mfaInstalled && frontendRouteOwner("mfa-authenticator") === "mfa";
  const mfaPage = hasMfaPage ? renderFrontendPage("mfa-authenticator", {
    authorities: [],
    hasGlobal: () => false,
    hasRouteAccess: () => false,
    hasRouteOperation: () => false,
    hasOperation: () => false,
  }) : null;

  return <>
    <div className="account-settings-page">
      <AccountSettingsSection title={t("authenticationSecurity")}>
        <AccountSettingsRow label={t("accountEmail")} action={<Button onClick={() => setEmailOpen(true)}>{t("edit")}</Button>} />
        <AccountSettingsRow label={t("accountPhone")} action={<Button onClick={() => setPhoneOpen(true)}>{t("edit")}</Button>} />
        <AccountSettingsRow label={t("passkeyCredentials")} action={<Button onClick={() => setPasskeyOpen(true)}>{t("edit")}</Button>} />
        <AccountSettingsRow label={t("securityEvents")} action={<Button onClick={() => setSecurityEventsOpen(true)}>{t("details")}</Button>} />
      </AccountSettingsSection>

      <AccountSettingsSection title={t("accountAuthenticationPolicy")}>
        {query.isPending ? <Spin /> : query.error ? <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} /> : query.data ? <>
          <AccountSettingsRow label={t("authenticationAllowedMethods")} value={methodTags(query.data.enabledMethods, t)} />
          <AccountSettingsRow label={t("authenticationUsableMethods")} value={methodTags(query.data.usableMethods, t)} />
          <AccountSettingsRow label={t("authenticationMfaRequirement")} value={<Tag color={query.data.mfaRequired ? "warning" : "default"}>{t(query.data.mfaRequired ? "mfaRequired" : "mfaNotRequired")}</Tag>} />
          <AccountSettingsRow label={t("authenticationRequirementSources")} value={<Space wrap>{query.data.requirementSources.length ? query.data.requirementSources.map((source, index) => <Tag key={`${source.kind}:${source.roleId ?? ""}:${index}`}>{t(source.kind === "GLOBAL" ? "authSourceGlobal" : source.kind === "ROLE" ? "authSourceRole" : source.kind === "SELF_ENABLED" ? "authSourceSelfEnabled" : "authSourceRecoveryPending")}{source.roleId ? ` · ${source.roleId}` : ""}</Tag>) : <Typography.Text type="secondary">—</Typography.Text>}</Space>} />
          <AccountSettingsRow label={t("authenticationAuthenticatorStatus")} value={authenticatorLabel(query.data.authenticator, t)} />
          <AccountSettingsRow label={t("mfaRecoveryRemaining")} value={query.data.authenticator.recoveryCodesRemaining ?? "—"} />
        </> : null}
      </AccountSettingsSection>

      <AccountSettingsSection className="account-settings-mfa">
        {hasMfaPage && mfaPage ? mfaPage : <Alert type="info" showIcon title={t("mfaProviderUnavailableShort")} />}
      </AccountSettingsSection>
    </div>
    <EmailDrawer open={emailOpen} onClose={() => setEmailOpen(false)} />
    <PhoneDrawer open={phoneOpen} onClose={() => setPhoneOpen(false)} />
    <PasskeysDrawer open={passkeyOpen} onClose={() => setPasskeyOpen(false)} />
    <SecurityEventsDrawer open={securityEventsOpen} onClose={() => setSecurityEventsOpen(false)} />
  </>;
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
