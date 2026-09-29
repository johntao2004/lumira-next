"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import { InfoCircleOutlined } from "@ant-design/icons";
import { Alert, Input, InputNumber, Select, Switch, Tag, Tooltip } from "antd";
import { SettingsSection, type Snapshot } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";

const initialExtras = { credentials: { action: "KEEP" } };

function HelpLabel({ label, hint }: { label: string; hint: string }) {
  return (
    <span className="settings-help-label">
      <span>{label}</span>
      <Tooltip title={hint} placement="top" trigger={["hover", "focus"]}>
        <span className="settings-help-label-icon" tabIndex={0} role="img" aria-label={hint}>
          <InfoCircleOutlined />
        </span>
      </Tooltip>
    </span>
  );
}

function Credentials({ snapshot }: { snapshot: Snapshot<{ credentialConfigured: boolean; encryptionConfigured: boolean }> }) {
  const { t } = usePreferences();
  const form = Form.useFormInstance();
  const action = Form.useWatch(["credentials", "action"], form);
  return <>
    <p><Tag color={snapshot.credentialConfigured ? "green" : "default"}>{t(snapshot.credentialConfigured ? "smsConfigured" : "smsNotConfigured")}</Tag></p>
    {!snapshot.encryptionConfigured && <Alert type="warning" title={t("secretKeyUnavailable")} className="mb-4" />}
    <Form.Item name={["credentials", "action"]} label={<HelpLabel label={t("smsCredentialAction")} hint={t("smsCredentialHint")} />} rules={[{ required: true }]}>
      <Select aria-label={t("smsCredentialAction")} onChange={() => form.setFieldValue(["credentials", "value"], undefined)} options={[
        { value: "KEEP", label: t("smsKeep") }, { value: "REPLACE", label: t("smsReplace"), disabled: !snapshot.encryptionConfigured }, { value: "CLEAR", label: t("smsClear") },
      ]} />
    </Form.Item>
    {action === "REPLACE" && <Form.Item name={["credentials", "value"]} label={t("password")} preserve={false} rules={[{ required: true, message: t("invalidSmsCredentials") }]}> 
      <Input.Password autoComplete="new-password" maxLength={4096} />
    </Form.Item>}
  </>;
}

export function SmsPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  return <SettingsSection section="sms" canWrite={canWrite} initialExtras={initialExtras}
    renderExtras={snapshot => <Credentials snapshot={snapshot as Snapshot<{ credentialConfigured: boolean; encryptionConfigured: boolean }>} />}>
    <Form.Item name={["data", "enabled"]} label={t("smsEnabled")} valuePropName="checked"><Switch /></Form.Item>
    <Form.Item name={["data", "provider"]} label={<HelpLabel label={t("smsProvider")} hint={t("smsConfigHint")} />} rules={[{ required: true }]}><Select options={[{ value: "webhook", label: t("smsProviderWebhook") }]} /></Form.Item>
    <Form.Item name={["data", "endpoint"]} label={t("smsEndpoint")}><Input maxLength={2000} autoComplete="off" /></Form.Item>
    <Form.Item name={["data", "sender"]} label={t("smsSender")} rules={[{ required: true }]}><Input maxLength={64} /></Form.Item>
    <Form.Item name={["data", "template"]} label={t("smsTemplate")} rules={[{ required: true }]}><Input.TextArea maxLength={500} autoSize={{ minRows: 2, maxRows: 5 }} /></Form.Item>
    <Form.Item name={["data", "timeoutSeconds"]} label={t("smsTimeout")} rules={[{ required: true }]}><InputNumber min={1} max={30} precision={0} /></Form.Item>
  </SettingsSection>;
}
