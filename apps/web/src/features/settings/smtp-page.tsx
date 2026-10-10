"use client";

import { Input, InputNumber, Select, Switch } from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SettingsEnabledControls } from "@/platform/components/settings-enabled-controls";
import { SettingsSecretField } from "@/platform/components/settings-secret-field";
import { SettingsSection, type Snapshot } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";

const initialExtras = { credentials: { action: "KEEP" } };

function Credentials({ snapshot }: { snapshot: Snapshot<object> }) {
  const { t } = usePreferences();
  return <SettingsEnabledControls name={["data", "authentication"]}>
    <SettingsSecretField label={t("password")} encryptionConfigured={snapshot.encryptionConfigured === true}
      keepHint={snapshot.passwordConfigured ? t("smtpPasswordKeepHint") : undefined} />
  </SettingsEnabledControls>;
}

function SmtpFields() {
  const { t } = usePreferences();
  const form = Form.useFormInstance();
  const enabled = Form.useWatch(["data", "enabled"], form) ?? form.getFieldValue(["data", "enabled"]);
  const required = [{ required: enabled === true }];
  return <>
    <Form.Item name={["data", "enabled"]} label={t("smtpEnabled")} valuePropName="checked"><Switch /></Form.Item>
    <SettingsEnabledControls>
      <Form.Item name={["data", "host"]} label={t("smtpHost")}><Input maxLength={253} autoComplete="off" /></Form.Item>
      <Form.Item name={["data", "port"]} label={t("smtpPort")} rules={required}><InputNumber min={1} max={65535} precision={0} /></Form.Item>
      <Form.Item name={["data", "security"]} label={t("smtpSecurity")} rules={required}><Select options={[{ value: "STARTTLS", label: "STARTTLS" }, { value: "TLS", label: "TLS" }]} /></Form.Item>
      <Form.Item name={["data", "authentication"]} label={t("smtpAuthentication")} valuePropName="checked"><Switch /></Form.Item>
      <SettingsEnabledControls name={["data", "authentication"]}>
        <Form.Item name={["data", "username"]} label={t("username")}><Input maxLength={320} autoComplete="off" /></Form.Item>
      </SettingsEnabledControls>
      <Form.Item name={["data", "fromAddress"]} label={t("smtpFromAddress")}><Input maxLength={254} /></Form.Item>
      <Form.Item name={["data", "fromName"]} label={t("smtpFromName")}><Input maxLength={120} /></Form.Item>
    </SettingsEnabledControls>
  </>;
}

export function SmtpPage({ canWrite }: { canWrite: boolean }) {
  return <SettingsSection section="smtp" formName="settings-smtp" canWrite={canWrite} showSecondaryActions={false} initialExtras={initialExtras}
    renderExtras={snapshot => <Credentials snapshot={snapshot} />}><SmtpFields /></SettingsSection>;
}
