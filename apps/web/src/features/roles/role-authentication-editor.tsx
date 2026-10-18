"use client";

import { Alert, Card, Checkbox, Radio, Space, Spin, Tag } from "antd";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { api, mutate } from "@/platform/api/client";
import type { AuthenticationSettings, PrimaryMethod, RoleAuthenticationPolicy } from "@/platform/auth/api-types";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { QueryFailure } from "@/platform/components/query-failure";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { usePreferences } from "@/platform/providers";
import type { Role } from "./types";

type Values = {
  loginMode: RoleAuthenticationPolicy["loginMode"];
  methods: PrimaryMethod[];
  mfaMode: RoleAuthenticationPolicy["mfaMode"];
  captchaMode?: RoleAuthenticationPolicy["captchaMode"];
};

const methods: PrimaryMethod[] = ["PASSWORD", "PASSKEY", "EMAIL_OTP", "SMS_OTP", "WECHAT"];
const methodLabel = {
  PASSWORD: "passwordLogin",
  PASSKEY: "passkeyLogin",
  EMAIL_OTP: "emailOtpLogin",
  SMS_OTP: "smsOtpLogin",
  WECHAT: "wechatLogin",
} as const;

export function RoleAuthenticationEditor({ role, canWrite }: { role: Role; canWrite: boolean }) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm<Values>();
  const policy = useQuery({
    queryKey: ["role-authentication-policy", role.id],
    queryFn: ({ signal }) => api<RoleAuthenticationPolicy>(`/platform/roles/${role.id}/authentication-policy`, { signal }),
  });
  const globalPolicy = useQuery({
    queryKey: ["settings", "authentication"],
    queryFn: ({ signal }) => api<AuthenticationSettings>("/platform/settings/authentication", { signal }),
  });
  const capabilities = useQuery({
    queryKey: ["auth-methods"],
    queryFn: ({ signal }) => api<{ capabilities: { captcha: boolean } }>("/auth/methods", { signal }),
    retry: false,
  });
  const action = useSubmitAction({ errorKeys: ["staleRecord", "invalidChangeReason", "authenticationPolicyConflict", "authenticationPolicyUnreachable", "authenticationMethodUnsupported", "captchaUnavailable", "secondFactorUnavailable", "forbidden", "unavailable"] });
  const loginMode = Form.useWatch("loginMode", form);
  const mfaMode = Form.useWatch("mfaMode", form);
  const selectedMethods = Form.useWatch("methods", form) ?? [];

  useEffect(() => {
    if (!policy.data) return;
    form.setFieldsValue({
      loginMode: policy.data.loginMode,
      methods: policy.data.methods,
      mfaMode: policy.data.mfaMode,
      captchaMode: policy.data.captchaMode ?? "INHERIT",
    });
  }, [form, policy.data]);

  if (policy.isPending || globalPolicy.isPending) return <Card size="small" title={t("roleAuthenticationPolicy")}><Spin /></Card>;
  if (policy.error || globalPolicy.error || !policy.data || !globalPolicy.data) return <Card size="small" title={t("roleAuthenticationPolicy")}><QueryFailure title={t("unavailable")} pending={policy.isFetching || globalPolicy.isFetching} onRetry={() => { void policy.refetch(); void globalPolicy.refetch(); }} /></Card>;

  const globalEnabled = new Set(globalPolicy.data.enabledMethods);
  return <Card size="small" title={t("roleAuthenticationPolicy")} className="mt-4">
    <Form
      name="role-authentication-policy"
      form={form}
      layout="vertical"
      disabled={!canWrite || action.pending}
      onFinish={values => void action.run(async () => {
        await mutate<RoleAuthenticationPolicy>(`/platform/roles/${role.id}/authentication-policy`, "PUT", {
          roleVersion: role.version,
          version: policy.data!.version,
          loginMode: values.loginMode,
          methods: values.loginMode === "RESTRICT" ? values.methods : [],
          mfaMode: values.mfaMode,
          ...(capabilities.data?.capabilities.captcha ? { captchaMode: values.captchaMode ?? "INHERIT" } : policy.data!.captchaMode ? { captchaMode: policy.data!.captchaMode } : {}),
        });
        await Promise.all([
          client.invalidateQueries({ queryKey: ["role-authentication-policy", role.id] }),
          client.invalidateQueries({ queryKey: ["roles"] }),
          client.invalidateQueries({ queryKey: ["session"] }),
        ]);
      })}
    >
      <Form.Item name="loginMode" label={t("roleLoginMethods")}>
        <Radio.Group options={[
          { value: "INHERIT", label: t("authenticationInherit") },
          { value: "RESTRICT", label: t("roleRestrictLoginMethods") },
        ]} />
      </Form.Item>
      {loginMode === "RESTRICT" && <Form.Item
        name="methods"
        label={t("roleAllowedLoginMethods")}
        extra={t("roleAllowedLoginMethodsHint")}
        rules={[{ validator: async (_, value: PrimaryMethod[] | undefined) => value?.length ? Promise.resolve() : Promise.reject(new Error(t("authenticationMethodRequired"))) }]}
      >
        <Checkbox.Group className="flex flex-col gap-2">
          {methods.map(method => {
            const selected = selectedMethods.includes(method);
            const enabledGlobally = globalEnabled.has(method);
            return <Checkbox key={method} value={method} disabled={!enabledGlobally && !selected}>
              <Space wrap>
                <span>{t(methodLabel[method])}</span>
                <Tag color={enabledGlobally ? "success" : "default"}>{t(enabledGlobally ? "authenticationGloballyEnabled" : "authenticationGloballyDisabled")}</Tag>
              </Space>
            </Checkbox>;
          })}
        </Checkbox.Group>
      </Form.Item>}
      <Form.Item name="mfaMode" label={t("roleMfaRequirement")}>
        <Radio.Group options={[
          { value: "INHERIT", label: t("authenticationInherit") },
          { value: "REQUIRED", label: t("authenticationRequired") },
        ]} />
      </Form.Item>
      {mfaMode === "REQUIRED" && Number.isSafeInteger(policy.data.affectedAccountCount) && <Form.Item>
        <Alert type="warning" showIcon title={t("roleMfaAffectedAccounts", { count: policy.data.affectedAccountCount })} />
      </Form.Item>}
      {capabilities.data?.capabilities.captcha ? <Form.Item name="captchaMode" label="CAPTCHA">
        <Radio.Group options={[
          { value: "INHERIT", label: t("authenticationInherit") },
          { value: "ALWAYS", label: t("authenticationRequired") },
        ]} />
      </Form.Item> : <Form.Item><Alert type="info" showIcon title={t("roleCaptchaCapabilityUnavailable")} /></Form.Item>}
      <FeedbackBubble id={`role-auth-policy-${role.id}-error`} content={action.errorMessage} />
      <Space className="form-actions" wrap>
        <AsyncButton htmlType="submit" pending={action.pending} disabled={!canWrite} label={t("saveRoleAuthenticationPolicy")} />
      </Space>
    </Form>
  </Card>;
}
