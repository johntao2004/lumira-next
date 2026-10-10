"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import {Divider, Input, InputNumber, Switch} from "antd";
import { SettingsSection } from "@/platform/components/settings-section";
import { SettingsEnabledControls } from "@/platform/components/settings-enabled-controls";
import { usePreferences } from "@/platform/providers";

/** Relying-party policy stays versioned through the shared settings editor. */
export function PasskeyPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  return <SettingsSection section="passkey" canWrite={canWrite} showSecondaryActions={false}>
    <Form.Item name={["data", "enabled"]} label={t("passkeyEnabled")} extra={t("passkeySettingsHint")} valuePropName="checked">
      <Switch />
    </Form.Item>
    <SettingsEnabledControls>
    <Form.Item name={["data", "passwordlessEnabled"]} label={t("passkeyPasswordlessEnabled")} valuePropName="checked">
      <Switch />
    </Form.Item>
    <Form.Item name={["data", "selfBindingEnabled"]} label={t("passkeySelfBindingEnabled")} valuePropName="checked">
      <Switch />
    </Form.Item>
    <Divider>{t("passkeyRpSettings")}</Divider>
    <Form.Item name={["data", "rpId"]} label={t("passkeyRpId")} rules={[{ required: true, whitespace: true, max: 253, message: t("invalidPasskeySettings") }]}>
      <Input maxLength={253} autoComplete="off" />
    </Form.Item>
    <Form.Item name={["data", "rpName"]} label={t("passkeyRpName")} rules={[{ required: true, whitespace: true, max: 255, message: t("invalidPasskeySettings") }]}>
      <Input maxLength={255} autoComplete="off" />
    </Form.Item>
    <Form.Item
      name={["data", "allowedOrigins"]}
      label={t("passkeyAllowedOrigins")}
      extra={t("passkeyAllowedOriginsHint")}
      rules={[{ required: true, message: t("invalidPasskeySettings") }]}
      getValueProps={(value: string[] | undefined) => ({ value: (value ?? []).join("\n") })}
      getValueFromEvent={(event) => event.target.value.split("\n").map((value: string) => value.trim()).filter(Boolean)}
    >
      <Input.TextArea rows={4} maxLength={16_384} autoComplete="off" />
    </Form.Item>
    <Form.Item name={["data", "challengeTtlSeconds"]} label={t("passkeyChallengeTtl")} rules={[{ required: true, type: "number", min: 30, max: 600, message: t("invalidPasskeySettings") }]}>
      <InputNumber min={30} max={600} precision={0} addonAfter={t("seconds")} />
    </Form.Item>
    </SettingsEnabledControls>
  </SettingsSection>;
}
