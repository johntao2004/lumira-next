/** 从已保存配置编辑 SMTP，并谨慎联动标准端口。
 * Edit saved SMTP settings and conservatively suggest standard ports. */
"use client";

import { useRef, useState } from "react";
import { smtpPortForSecurity } from "@/platform/forms/inferred-defaults";
import { Input, InputNumber, Select, Switch } from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SettingsEnabledControls } from "@/platform/components/settings-enabled-controls";
import { SettingsSecretField } from "@/platform/components/settings-secret-field";
import { SettingsSection, type Snapshot } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";
import { smtpHostValid, smtpMailboxValid, smtpTextValid } from "./smtp-validation";

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
  const authentication = Form.useWatch(["data", "authentication"], form) ?? form.getFieldValue(["data", "authentication"]);
  const security = Form.useWatch(["data", "security"], form) ?? form.getFieldValue(["data", "security"]);
  const portEdited = useRef(false);
  const required = [{ required: enabled === true }];
  const textRule = (max: number, format?: (value: string) => boolean) => ({ validator: async (_: unknown, value: unknown) => {
    const text = value ?? "";
    if (!smtpTextValid(text, max) || (text !== "" && format && !format(text))) throw new Error(t("invalidSmtp"));
  } });
  return <>
    <Form.Item name={["data", "enabled"]} label={t("smtpEnabled")} valuePropName="checked"><Switch /></Form.Item>
    <SettingsEnabledControls>
      <Form.Item name={["data", "host"]} label={t("smtpHost")} rules={[...required, textRule(253, smtpHostValid)]}><Input maxLength={253} autoComplete="off" /></Form.Item>
      <Form.Item name={["data", "port"]} label={t("smtpPort")} rules={[{ required: true, type: "integer", min: 1, max: 65535, message: t("invalidSmtp") }]}><InputNumber min={1} max={65535} precision={0} onChange={() => { portEdited.current = true; }} /></Form.Item>
      <Form.Item name={["data", "security"]} label={t("smtpSecurity")} rules={required}><Select onChange={(next) => {
        const current = form.getFieldValue(["data", "port"]);
        const inferred = smtpPortForSecurity(security, next, current, portEdited.current);
        if (inferred !== current) form.setFieldValue(["data", "port"], inferred);
      }} options={[{ value: "STARTTLS", label: "STARTTLS" }, { value: "TLS", label: "TLS" }]} /></Form.Item>
      <Form.Item name={["data", "authentication"]} label={t("smtpAuthentication")} valuePropName="checked"><Switch /></Form.Item>
      <SettingsEnabledControls name={["data", "authentication"]}>
        <Form.Item name={["data", "username"]} label={t("username")} rules={[{ required: enabled === true && authentication === true, whitespace: true, message: t("invalidSmtp") }, textRule(320)]}><Input maxLength={320} autoComplete="off" /></Form.Item>
      </SettingsEnabledControls>
      <Form.Item name={["data", "fromAddress"]} label={t("smtpFromAddress")} rules={[...required, textRule(254, smtpMailboxValid)]}><Input inputMode="email" autoComplete="email" maxLength={254} /></Form.Item>
      <Form.Item name={["data", "fromName"]} label={t("smtpFromName")} rules={[textRule(120)]}><Input maxLength={120} /></Form.Item>
    </SettingsEnabledControls>
  </>;
}

export function SmtpPage({ canWrite, onSaved }: { canWrite: boolean; onSaved?: () => void | Promise<void> }) {
  const [savedGeneration, setSavedGeneration] = useState(0);
  return <SettingsSection section="smtp" formName="settings-smtp" canWrite={canWrite} showSecondaryActions={false} initialExtras={initialExtras} onSaved={async () => { setSavedGeneration(value => value + 1); await onSaved?.(); }}
    renderExtras={snapshot => <Credentials snapshot={snapshot} />}><SmtpFields key={savedGeneration} /></SettingsSection>;
}
