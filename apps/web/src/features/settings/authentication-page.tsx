"use client";

import { Alert, Space, Spin, Switch, Tag, Typography } from "antd";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { api, mutate } from "@/platform/api/client";
import type { AuthenticationSettings, PrimaryMethod } from "@/platform/auth/api-types";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { QueryFailure } from "@/platform/components/query-failure";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";
import { PlatformForm as Form } from "@/platform/components/platform-form";

type Values = Pick<AuthenticationSettings, "enabledMethods" | "mfaRequired">;

const methodLabel = {
  PASSWORD: "passwordLogin",
  PASSKEY: "passkeyLogin",
  EMAIL_OTP: "emailOtpLogin",
  SMS_OTP: "smsOtpLogin",
  WECHAT: "wechatLogin",
} as const;

const methodOrder: PrimaryMethod[] = ["PASSWORD", "PASSKEY", "EMAIL_OTP", "SMS_OTP", "WECHAT"];

type MethodSwitchGroupProps = {
  value?: PrimaryMethod[];
  onChange?: (value: PrimaryMethod[]) => void;
  id?: string;
  disabled?: boolean;
  availableMethods?: PrimaryMethod[];
};

function MethodSwitchGroup({ value, onChange, id, disabled, availableMethods }: MethodSwitchGroupProps) {
  const { t } = usePreferences();
  const enabledMethods = new Set(value ?? []);

  return <div id={id} role="group" aria-label={t("globalLoginMethods")} className="flex flex-col gap-3">
    {methodOrder.map(method => (
      <Space key={method} wrap size={8} align="center">
        <Switch
          checked={enabledMethods.has(method)}
          disabled={disabled}
          aria-label={t(methodLabel[method])}
          onChange={checked => {
            const next = new Set(value ?? []);
            if (checked) next.add(method);
            else next.delete(method);
            onChange?.(methodOrder.filter(candidate => next.has(candidate)));
          }}
        />
        <span>{t(methodLabel[method])}</span>
        {!availableMethods
          ? <Tag>{t("loginMethodAvailabilityUnknown")}</Tag>
          : availableMethods.includes(method)
            ? <Tag color="success">{t("loginMethodProviderReady")}</Tag>
            : <Tag>{t("loginMethodProviderPending")}</Tag>}
      </Space>
    ))}
  </div>;
}

export function AuthenticationPage({ canWrite }: { canWrite: boolean }) {
  const query = useQuery({
    queryKey: ["settings", "authentication"],
    queryFn: ({ signal }) => api<AuthenticationSettings>("/platform/settings/authentication", { signal }),
  });
  const { t } = usePreferences();

  if (!query.data) return query.error
    ? <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />
    : <Spin />;

  return <AuthenticationEditor
    key={`${query.data.version}:${query.data.revision}`}
    initial={query.data}
    canWrite={canWrite}
  />;
}

function AuthenticationEditor({ initial, canWrite }: {
  initial: AuthenticationSettings;
  canWrite: boolean;
}) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm<Values>();
  const action = useSubmitAction({ errorKeys: ["staleRecord", "invalidChangeReason", "authenticationPolicyConflict", "authenticationPolicyUnreachable", "secondFactorUnavailable", "forbidden", "unavailable"] });
  const mfaRequired = Form.useWatch("mfaRequired", form);

  useEffect(() => {
    form.setFieldsValue({
      enabledMethods: initial.enabledMethods,
      mfaRequired: initial.mfaRequired,
    });
  }, [form, initial]);

  return <Form form={form} layout="vertical" disabled={!canWrite || action.pending} onFinish={(values) => void action.run(async () => {
    const result = await mutate<AuthenticationSettings>("/platform/settings/authentication", "PUT", {
      version: initial.version,
      enabledMethods: values.enabledMethods,
      mfaRequired: values.mfaRequired,
    });
    client.setQueryData(["settings", "authentication"], result);
    await Promise.all([
      client.invalidateQueries({ queryKey: ["auth-methods"] }),
      client.invalidateQueries({ queryKey: ["session"] }),
    ]);
  })}>
    <Typography.Title level={5}>{t("globalLoginMethods")}</Typography.Title>
    <Typography.Paragraph type="secondary">{t("globalLoginMethodsHint")}</Typography.Paragraph>
    <Form.Item name="enabledMethods" rules={[{ type: "array", min: 1, required: true, message: t("authenticationMethodRequired") }]}>
      <MethodSwitchGroup availableMethods={initial.availableMethods} />
    </Form.Item>
    <Form.Item name="mfaRequired" label={t("globalMfaRequired")} valuePropName="checked" extra={t("globalMfaRequiredHint")}>
      <Switch />
    </Form.Item>
    {mfaRequired === true && Number.isSafeInteger(initial.affectedAccountCount) && <Alert className="mb-4" type="warning" showIcon title={t("globalMfaAffectedAccounts", { count: initial.affectedAccountCount })} />}
    <FeedbackBubble id="authentication-settings-error" content={action.errorMessage} />
    <Space className="form-actions" wrap>
      <AsyncButton htmlType="submit" type="primary" pending={action.pending} disabled={!canWrite} label={t("save")} />
    </Space>
  </Form>;
}
