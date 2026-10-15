"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import { Input, InputNumber, Switch, Typography } from "antd";
import { SettingsSection } from "@/platform/components/settings-section";
import { SettingsEnabledControls } from "@/platform/components/settings-enabled-controls";
import { usePreferences } from "@/platform/providers";

/** Relying-party policy stays versioned through the shared settings editor. */
export function PasskeyPage({ canWrite, onSaved }: { canWrite: boolean; onSaved?: () => void | Promise<void> }) {
  const { t } = usePreferences();
  return <SettingsSection section="passkey" className="passkey-settings" canWrite={canWrite} showSecondaryActions={false} onSaved={onSaved}>
    <section className="auth-settings-section">
      <Typography.Title level={5}>{t("passkeySettings")}</Typography.Title>
      <Typography.Paragraph type="secondary">{t("passkeySettingsHint")}</Typography.Paragraph>
      <Form.Item name={["data", "enabled"]} label={t("passkeyEnabled")} valuePropName="checked" className="auth-settings-switch-row">
        <Switch />
      </Form.Item>
      <SettingsEnabledControls>
        <div className="auth-settings-grid">
          <Form.Item name={["data", "passwordlessEnabled"]} label={t("passkeyPasswordlessEnabled")} valuePropName="checked" className="auth-settings-switch-row">
            <Switch />
          </Form.Item>
          <Form.Item name={["data", "selfBindingEnabled"]} label={t("passkeySelfBindingEnabled")} valuePropName="checked" className="auth-settings-switch-row">
            <Switch />
          </Form.Item>
        </div>
      </SettingsEnabledControls>
    </section>
    <SettingsEnabledControls>
      <section className="auth-settings-section">
        <Typography.Title level={5}>{t("passkeyRpSettings")}</Typography.Title>
        <div className="auth-settings-grid">
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
            className="auth-settings-wide"
          >
            <Input.TextArea rows={4} maxLength={16_384} autoComplete="off" />
          </Form.Item>
          <Form.Item name={["data", "challengeTtlSeconds"]} label={t("passkeyChallengeTtl")} rules={[{ required: true, type: "integer", min: 30, max: 600, message: t("invalidPasskeySettings") }]}>
            <InputNumber min={30} max={600} precision={0} addonAfter={t("seconds")} />
          </Form.Item>
        </div>
      </section>
    </SettingsEnabledControls>
  </SettingsSection>;
}
