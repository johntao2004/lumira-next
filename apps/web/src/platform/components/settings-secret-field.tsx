"use client";

import { Alert, Input } from "antd";
import { usePreferences } from "../providers";
import { PlatformForm as Form } from "./platform-form";
import { SettingsEnabledControls } from "./settings-enabled-controls";

/** Empty input preserves the stored secret; only a newly entered value replaces it. */
export function SettingsSecretField({ label, encryptionConfigured, keepHint }: {
  label: string;
  encryptionConfigured: boolean;
  keepHint?: string;
}) {
  const { t } = usePreferences();
  const form = Form.useFormInstance();
  const password = Form.useWatch(["credentials", "value"], form);
  return <SettingsEnabledControls>
    {!encryptionConfigured && <Alert type="warning" title={t("secretKeyUnavailable")} className="mb-4" />}
    <Form.Item name={["credentials", "action"]} hidden><Input /></Form.Item>
    <Form.Item name={["credentials", "value"]} label={label} normalize={(value: string) => value || undefined} extra={keepHint}>
      <Input.Password aria-label={label} autoComplete="new-password" maxLength={4096} visibilityToggle={Boolean(password)}
        disabled={!encryptionConfigured ? true : undefined}
        onChange={event => form.setFieldValue(["credentials", "action"], event.target.value ? "REPLACE" : "KEEP")} />
    </Form.Item>
  </SettingsEnabledControls>;
}
