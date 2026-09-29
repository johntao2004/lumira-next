"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import {Divider, InputNumber, Switch, Typography} from "antd";
import { SettingsSection } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";
import { MAX_PASSWORD_BYTES } from "@/platform/auth/password-contract";

/** Settings mirror the backend SecuritySettings record; server validation remains authoritative. */
export function SecurityPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  return (
    <SettingsSection section="security" canWrite={canWrite} requireChangeReason>
      <Typography.Paragraph type="secondary">{t("securitySettingsHint")}</Typography.Paragraph>
      <Divider>{t("securityPasswordPolicy")}</Divider>
      <Form.Item
        name={["data", "passwordMinLength"]}
        label={t("passwordMinLength")}
        rules={[{ required: true, type: "number", min: 8, max: MAX_PASSWORD_BYTES }]}
      >
        <InputNumber min={8} max={MAX_PASSWORD_BYTES} precision={0} />
      </Form.Item>
      <Form.Item name={["data", "passwordRequireUppercase"]} label={t("passwordRequireUppercase")} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name={["data", "passwordRequireLowercase"]} label={t("passwordRequireLowercase")} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name={["data", "passwordRequireSpecialCharacter"]} label={t("passwordRequireSpecialCharacter")} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name={["data", "passwordAllowConsecutiveCharacters"]} label={t("passwordAllowConsecutiveCharacters")} valuePropName="checked">
        <Switch />
      </Form.Item>

      <Divider>{t("securityLoginLimits")}</Divider>
      <Form.Item name={["data", "loginAccountPerMinute"]} label={t("loginAccountPerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
        <InputNumber min={1} max={10000} precision={0} />
      </Form.Item>
      <Form.Item name={["data", "loginSourcePerMinute"]} label={t("loginSourcePerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
        <InputNumber min={1} max={10000} precision={0} />
      </Form.Item>
      <Form.Item name={["data", "recoveryAccountPerMinute"]} label={t("recoveryAccountPerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
        <InputNumber min={1} max={10000} precision={0} />
      </Form.Item>
      <Form.Item name={["data", "recoverySourcePerMinute"]} label={t("recoverySourcePerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
        <InputNumber min={1} max={10000} precision={0} />
      </Form.Item>

      <Divider>{t("securityVerification")}</Divider>
      <Form.Item name={["data", "verificationCodeExpireSeconds"]} label={t("verificationExpireSeconds")} rules={[{ required: true, type: "number", min: 60, max: 3600 }]}>
        <InputNumber min={60} max={3600} precision={0} />
      </Form.Item>
      <Form.Item name={["data", "verificationCodeCooldownSeconds"]} label={t("verificationCooldownSeconds")} rules={[{ required: true, type: "number", min: 1, max: 3600 }]}>
        <InputNumber min={1} max={3600} precision={0} />
      </Form.Item>
      <Form.Item name={["data", "verificationRequestsPerMinute"]} label={t("verificationRequestsPerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
        <InputNumber min={1} max={10000} precision={0} />
      </Form.Item>
      <Form.Item name={["data", "verificationDailyLimit"]} label={t("verificationDailyLimit")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
        <InputNumber min={1} max={10000} precision={0} />
      </Form.Item>
      <Form.Item name={["data", "verificationFailureLimit"]} label={t("verificationFailureLimit")} rules={[{ required: true, type: "number", min: 1, max: 20 }]}>
        <InputNumber min={1} max={20} precision={0} />
      </Form.Item>
    </SettingsSection>
  );
}
