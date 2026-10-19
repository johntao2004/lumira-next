/** 编辑验证码策略并保留冲突草稿。
 * Edit CAPTCHA policy while preserving conflicting drafts. */
"use client";

import { useEffect, useState } from "react";
import { useSettingsDraft } from "@/platform/forms/use-settings-draft";
import { Button, Popconfirm, Alert, Select, Space, Spin, Switch, Tag, Typography } from "antd";
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
import "./authentication-settings-panels.css";
import "./captcha-page.css";

type CaptchaForm = { data: CaptchaSettingsData };

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
const policyGroups: { title: MessageKey; actions: CaptchaAction[] }[] = [
  {
    title: "globalLoginMethods",
    actions: ["PASSWORD_LOGIN", "PASSKEY_LOGIN", "WECHAT_LOGIN", "LOGIN_CODE_SEND", "LOGIN_CODE_VERIFY"],
  },
  {
    title: "registrationSettingsTitle",
    actions: ["REGISTRATION_SEND", "REGISTRATION_VERIFY", "REGISTRATION_COMPLETE"],
  },
  {
    title: "forgotPassword",
    actions: ["PASSWORD_RECOVERY_REQUEST", "PASSWORD_RECOVERY_VERIFY", "PASSWORD_RECOVERY_CONFIRM"],
  },
  {
    title: "captchaPostPrimaryAuth",
    actions: ["POST_PRIMARY_AUTH"],
  },
];
const actions = policyGroups.flatMap(group => group.actions);

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

  const { snapshot, revision, commit: setSnapshot, reload, operationSignal } = useSettingsDraft(query.data,
    () => query.refetch({ throwOnError: true }), () => { form.resetFields(); setSaved(false); });
  // 草稿与首次读到的版本绑定，后台刷新只更新状态提示。
  // Bind drafts to their initial version; background refresh updates status only.
  useEffect(() => {
    if (!snapshot) return;
    form.setFieldsValue({
      data: { ...snapshot.data, actions: completeActions(snapshot.data.actions) },
    });
  }, [form, snapshot, revision]);

  async function save(values: CaptchaForm) {
    if (!canWrite || !snapshot) return;
    const signal = operationSignal();
    if (signal.aborted) return;
    await action.run(async () => {
      const payload: CaptchaSettingsUpdate = {
        version: snapshot.version,
        data: { ...values.data, provider: "LOCAL" },
      };
      const next = await mutate<CaptchaSettings>("/platform/settings/captcha", "PUT", payload, signal);
      if (signal.aborted) return;
      setSnapshot(next);
      client.setQueryData(["settings", "captcha"], next);
      form.setFieldsValue({
        data: { ...next.data, actions: completeActions(next.data.actions) },
      });
      await Promise.all([
        client.invalidateQueries({ queryKey: ["auth-methods"] }),
        client.invalidateQueries({ queryKey: ["session"] }),
      ]);
      if (signal.aborted) return;
      setSaved(true);
    });
  }

  if (query.isPending) return <Spin />;
  if (query.error) return <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />;
  if (!query.data) return null;

  const serviceEnabled = query.data.data.enabled;
  return <div className="captcha-settings-page">
    {serviceEnabled && <Alert className="captcha-settings-readiness" type={query.data.readiness.configured ? "success" : "warning"} showIcon
      title={t(query.data.readiness.configured ? "captchaEnabledReady" : "captchaEnabledNotReady")} />}
    <Form form={form} name="settings-captcha" className="settings-form auth-config-form captcha-settings-form" layout="vertical" disabled={!canWrite || action.pending}
      initialValues={{ data: query.data.data }}
      onValuesChange={() => setSaved(false)} onFinish={save}>
      <div className="auth-config-stack">
        <section className="captcha-service-section">
          <Typography.Paragraph className="auth-config-note" type="secondary">{t("captchaSettingsHint")}</Typography.Paragraph>
          <Form.Item
            className="auth-settings-switch-row"
            name={["data", "enabled"]}
            label={t("captchaEnableService")}
            valuePropName="checked"
            extra={t("captchaServiceEnableHint")}
          >
            <Switch />
          </Form.Item>
          <div className="captcha-service-fields">
            <Form.Item label={t("captchaProvider")}>{t("captchaProviderLocal")}</Form.Item>
            <SettingsEnabledControls>
              <Form.Item name={["data", "type"]} label={t("captchaChallengeType")}>
                <Select options={[
                  { value: "IMAGE_TEXT", label: t("captchaTypeImageText") },
                  { value: "SLIDER", label: t("captchaTypeSlider") },
                ]} />
              </Form.Item>
            </SettingsEnabledControls>
            <Form.Item label={t("captchaAccessibleReadiness")}>
              <Tag color={query.data.readiness.accessibleAlternativeAvailable ? "success" : "default"}>
                {t(query.data.readiness.accessibleAlternativeAvailable ? "captchaAccessibleReady" : "captchaAccessibleNotReady")}
              </Tag>
            </Form.Item>
          </div>
        </section>

        <section className="captcha-policies-section">
          <Typography.Title level={5}>{t("captchaActionPolicies")}</Typography.Title>
          <Typography.Paragraph className="auth-config-note" type="secondary">{t("captchaActionPoliciesHint")}</Typography.Paragraph>
          <SettingsEnabledControls>
            <div role="group" aria-label={t("captchaActionPolicies")} className="captcha-policy-groups">
              {policyGroups.map(group => <section key={group.title} role="group" aria-label={t(group.title)} className="captcha-policy-group">
                {group.actions.length > 1 && <Typography.Text strong className="captcha-policy-group-title">{t(group.title)}</Typography.Text>}
                <div className="captcha-policy-actions">
                  {group.actions.map(name => <Form.Item key={name} name={["data", "actions", name]} noStyle>
                    <CaptchaActionPolicyControl
                      actionLabel={t(actionLabels[name])}
                      options={captchaPolicyModes.map(mode => ({
                        value: mode,
                        label: t(mode === "OFF" ? "captchaPolicyOff" : mode === "RISK" ? "captchaPolicyRisk" : "captchaPolicyAlways"),
                      }))}
                    />
                  </Form.Item>)}
                </div>
              </section>)}
            </div>
          </SettingsEnabledControls>
        </section>
      </div>
      <Popconfirm title={t("reloadSettingsHint")} okText={t("reloadSettings")} cancelText={t("cancel")} onConfirm={() => action.run(reload)} disabled={action.pending}>
        <Button disabled={action.pending}>{t("reloadSettings")}</Button>
      </Popconfirm>
      <FeedbackBubble id="settings-captcha-error" content={action.error ? t(action.error) : undefined} />
      <FeedbackBubble kind="success" id="settings-captcha-saved" content={saved ? t("settingsSaved") : undefined} />
      <Space className="form-actions auth-config-actions" wrap>
        <AsyncButton htmlType="submit" type="primary" pending={action.pending} disabled={!canWrite} label={t("save")} />
      </Space>
    </Form>
  </div>;
}

function CaptchaActionPolicyControl({ value = "OFF", onChange, actionLabel, options }: {
  value?: CaptchaMode;
  onChange?: (value: CaptchaMode) => void;
  actionLabel: string;
  options: { value: CaptchaMode; label: string }[];
}) {
  return <div className="captcha-action-policy-row">
    <span>{actionLabel}</span>
    <Select
      aria-label={actionLabel}
      value={value}
      onChange={mode => onChange?.(mode as CaptchaMode)}
      options={options}
    />
  </div>;
}

function completeActions(source: Partial<Record<CaptchaAction, CaptchaMode>>): Record<CaptchaAction, CaptchaMode> {
  return Object.fromEntries(actions.map(action => [action, source[action] ?? "OFF"])) as Record<CaptchaAction, CaptchaMode>;
}
