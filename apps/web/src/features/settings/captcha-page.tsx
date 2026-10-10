"use client";

import { useEffect, useState } from "react";
import { Alert, Select, Space, Spin, Switch, Tag, Typography } from "antd";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, mutate } from "@/platform/api/client";
import type { CaptchaAction, CaptchaMode, CaptchaSettings, CaptchaSettingsData, CaptchaSettingsUpdate } from "@/platform/auth/api-types";
import { captchaPolicyModes } from "@/platform/auth/captcha-policy";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { QueryFailure } from "@/platform/components/query-failure";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SettingsEnabledControls } from "@/platform/components/settings-enabled-controls";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";

type CaptchaForm = { data: CaptchaSettingsData };

const actions: CaptchaAction[] = [
  "PASSWORD_LOGIN", "PASSKEY_LOGIN", "WECHAT_LOGIN",
  "LOGIN_CODE_SEND", "LOGIN_CODE_VERIFY",
  "REGISTRATION_SEND", "REGISTRATION_VERIFY", "REGISTRATION_COMPLETE",
  "PASSWORD_RECOVERY_REQUEST", "PASSWORD_RECOVERY_VERIFY", "PASSWORD_RECOVERY_CONFIRM",
  "POST_PRIMARY_AUTH",
];
const actionLabels: Record<CaptchaAction, MessageKey> = {
  PASSWORD_LOGIN: "passwordLogin",
  PASSKEY_LOGIN: "passkeyLogin",
  WECHAT_LOGIN: "wechatLogin",
  LOGIN_CODE_SEND: "captchaActionLoginCodeSend",
  LOGIN_CODE_VERIFY: "captchaActionLoginCodeVerify",
  REGISTRATION_SEND: "captchaActionRegistrationSend",
  REGISTRATION_VERIFY: "captchaActionRegistrationVerify",
  REGISTRATION_COMPLETE: "registrationCreateAccount",
  PASSWORD_RECOVERY_REQUEST: "captchaActionPasswordRecoveryRequest",
  PASSWORD_RECOVERY_VERIFY: "captchaActionPasswordRecoveryVerify",
  PASSWORD_RECOVERY_CONFIRM: "resetPassword",
  POST_PRIMARY_AUTH: "captchaPostPrimaryAuth",
};

const settingsErrors = [
  "staleRecord", "invalidCaptchaSettings", "invalidChangeReason",
  "captchaProviderNotReady", "captchaRequiredProviderReferenced", "forbidden", "unavailable",
] as const satisfies readonly MessageKey[];

export function CaptchaPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm<CaptchaForm>();
  const [saved, setSaved] = useState(false);
  const query = useQuery({
    queryKey: ["settings", "captcha"],
    queryFn: ({ signal }) => api<CaptchaSettings>("/platform/settings/captcha", { signal }),
  });
  const action = useSubmitAction({
    errorKeys: settingsErrors,
    onError: async key => { if (key === "staleRecord") await query.refetch(); },
  });

  useEffect(() => {
    if (!query.data) return;
    form.setFieldsValue({
      data: { ...query.data.data, actions: completeActions(query.data.data.actions) },
    });
  }, [form, query.data]);

  async function save(values: CaptchaForm) {
    if (!canWrite || !query.data) return;
    await action.run(async () => {
      const payload: CaptchaSettingsUpdate = {
        version: query.data!.version,
        data: { ...values.data, provider: "LOCAL" },
      };
      const next = await mutate<CaptchaSettings>("/platform/settings/captcha", "PUT", payload);
      client.setQueryData(["settings", "captcha"], next);
      form.setFieldsValue({
        data: { ...next.data, actions: completeActions(next.data.actions) },
      });
      await Promise.all([
        client.invalidateQueries({ queryKey: ["auth-methods"] }),
        client.invalidateQueries({ queryKey: ["session"] }),
      ]);
      setSaved(true);
    });
  }

  if (query.isPending) return <Spin />;
  if (query.error) return <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />;
  if (!query.data) return null;

  const serviceEnabled = query.data.data.enabled;
  return <>
    {serviceEnabled && <Alert className="mb-4" type={query.data.readiness.configured ? "success" : "warning"} showIcon
      title={t(query.data.readiness.configured ? "captchaEnabledReady" : "captchaEnabledNotReady")} />}
    <Typography.Paragraph type="secondary">{t("captchaSettingsHint")}</Typography.Paragraph>
    <Form form={form} name="settings-captcha" className="settings-form" layout="vertical" disabled={!canWrite || action.pending}
      initialValues={{ data: query.data.data }}
      onValuesChange={() => setSaved(false)} onFinish={save}>
      <Form.Item label={t("captchaProvider")}>{t("captchaProviderLocal")}</Form.Item>
      <Form.Item name={["data", "enabled"]} label={t("captchaEnableService")} valuePropName="checked" extra={t("captchaServiceEnableHint")}>
        <Switch />
      </Form.Item>
      <SettingsEnabledControls>
      <Form.Item name={["data", "type"]} label={t("captchaChallengeType")}>
        <Select options={[
          { value: "IMAGE_TEXT", label: t("captchaTypeImageText") },
          { value: "SLIDER", label: t("captchaTypeSlider") },
        ]} />
      </Form.Item>
      <Form.Item label={t("captchaAccessibleReadiness")}>
        <Tag color={query.data.readiness.accessibleAlternativeAvailable ? "success" : "default"}>
          {t(query.data.readiness.accessibleAlternativeAvailable ? "captchaAccessibleReady" : "captchaAccessibleNotReady")}
        </Tag>
      </Form.Item>
      </SettingsEnabledControls>

      <Typography.Title level={5}>{t("captchaActionPolicies")}</Typography.Title>
      <Typography.Paragraph type="secondary">{t("captchaActionPoliciesHint")}</Typography.Paragraph>
      <SettingsEnabledControls>
        <div role="group" aria-label={t("captchaActionPolicies")} className="flex flex-col gap-3">
          {actions.map(name => <Form.Item key={name} name={["data", "actions", name]} noStyle>
            <CaptchaActionPolicyControl
              actionLabel={t(actionLabels[name])}
              options={captchaPolicyModes.map(mode => ({
                value: mode,
                label: t(mode === "OFF" ? "captchaPolicyOff" : mode === "RISK" ? "captchaPolicyRisk" : "captchaPolicyAlways"),
              }))}
            />
          </Form.Item>)}
        </div>
      </SettingsEnabledControls>
      <FeedbackBubble id="settings-captcha-error" content={action.error ? t(action.error) : undefined} />
      <FeedbackBubble kind="success" id="settings-captcha-saved" content={saved ? t("settingsSaved") : undefined} />
      <Space className="form-actions" wrap style={{ marginBlockStart: 16 }}>
        <AsyncButton htmlType="submit" type="primary" pending={action.pending} disabled={!canWrite} label={t("save")} />
      </Space>
    </Form>
  </>;
}

function CaptchaActionPolicyControl({ value = "OFF", onChange, actionLabel, options }: {
  value?: CaptchaMode;
  onChange?: (value: CaptchaMode) => void;
  actionLabel: string;
  options: { value: CaptchaMode; label: string }[];
}) {
  return <Space wrap size={8} align="center">
    <span>{actionLabel}</span>
    <Select
      aria-label={actionLabel}
      value={value}
      onChange={mode => onChange?.(mode as CaptchaMode)}
      options={options}
      style={{ width: 160 }}
    />
  </Space>;
}

function completeActions(source: Partial<Record<CaptchaAction, CaptchaMode>>): Record<CaptchaAction, CaptchaMode> {
  return Object.fromEntries(actions.map(action => [action, source[action] ?? "OFF"])) as Record<CaptchaAction, CaptchaMode>;
}
