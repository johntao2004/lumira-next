"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import { InputNumber, Switch, Typography } from "antd";
import { SettingsSection } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";
import { MAX_PASSWORD_BYTES } from "@/platform/auth/password-contract";
import "./security-page.css";

/** Settings mirror the backend SecuritySettings record; server validation remains authoritative. */
export function SecurityPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  return (
    <SettingsSection section="security" className="security-settings" canWrite={canWrite} showSecondaryActions={false}>
      <div className="security-policy-grid">
        <section className="security-policy-panel" aria-label={t("securityPasswordPolicy")}>
          <Typography.Title level={5}>{t("securityPasswordPolicy")}</Typography.Title>
          <div className="security-policy-fields security-policy-single-column">
            <Form.Item
              className="security-policy-field"
              name={["data", "passwordMinLength"]}
              label={t("passwordMinLength")}
              rules={[{ required: true, type: "number", min: 8, max: MAX_PASSWORD_BYTES }]}
            >
              <InputNumber min={8} max={MAX_PASSWORD_BYTES} precision={0} />
            </Form.Item>
            <Form.Item className="security-policy-field security-policy-switch-field" name={["data", "passwordRequireUppercase"]} label={t("passwordRequireUppercase")} valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item className="security-policy-field security-policy-switch-field" name={["data", "passwordRequireLowercase"]} label={t("passwordRequireLowercase")} valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item className="security-policy-field security-policy-switch-field" name={["data", "passwordRequireSpecialCharacter"]} label={t("passwordRequireSpecialCharacter")} valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item className="security-policy-field security-policy-switch-field" name={["data", "passwordAllowConsecutiveCharacters"]} label={t("passwordAllowConsecutiveCharacters")} valuePropName="checked">
              <Switch />
            </Form.Item>
          </div>
        </section>
        <section className="security-policy-panel" aria-label={t("securityLoginLimits")}>
          <Typography.Title level={5}>{t("securityLoginLimits")}</Typography.Title>
          <div className="security-policy-fields security-policy-single-column">
            <Form.Item className="security-policy-field" name={["data", "loginAccountPerMinute"]} label={t("loginAccountPerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
              <InputNumber min={1} max={10000} precision={0} />
            </Form.Item>
            <Form.Item className="security-policy-field" name={["data", "loginSourcePerMinute"]} label={t("loginSourcePerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
              <InputNumber min={1} max={10000} precision={0} />
            </Form.Item>
            <Form.Item className="security-policy-field" name={["data", "recoveryAccountPerMinute"]} label={t("recoveryAccountPerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
              <InputNumber min={1} max={10000} precision={0} />
            </Form.Item>
            <Form.Item className="security-policy-field" name={["data", "recoverySourcePerMinute"]} label={t("recoverySourcePerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
              <InputNumber min={1} max={10000} precision={0} />
            </Form.Item>
          </div>
        </section>
        <section className="security-policy-panel security-policy-verification" aria-label={t("securityVerification")}>
          <Typography.Title level={5}>{t("securityVerification")}</Typography.Title>
          <div className="security-policy-fields">
            <Form.Item className="security-policy-field" name={["data", "verificationCodeExpireSeconds"]} label={t("verificationExpireSeconds")} rules={[{ required: true, type: "number", min: 60, max: 3600 }]}>
              <InputNumber min={60} max={3600} precision={0} />
            </Form.Item>
            <Form.Item className="security-policy-field" name={["data", "verificationCodeCooldownSeconds"]} label={t("verificationCooldownSeconds")} rules={[{ required: true, type: "number", min: 1, max: 3600 }]}>
              <InputNumber min={1} max={3600} precision={0} />
            </Form.Item>
            <Form.Item className="security-policy-field" name={["data", "verificationRequestsPerMinute"]} label={t("verificationRequestsPerMinute")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
              <InputNumber min={1} max={10000} precision={0} />
            </Form.Item>
            <Form.Item className="security-policy-field" name={["data", "verificationDailyLimit"]} label={t("verificationDailyLimit")} rules={[{ required: true, type: "number", min: 1, max: 10000 }]}>
              <InputNumber min={1} max={10000} precision={0} />
            </Form.Item>
            <Form.Item className="security-policy-field" name={["data", "verificationFailureLimit"]} label={t("verificationFailureLimit")} rules={[{ required: true, type: "number", min: 1, max: 20 }]}>
              <InputNumber min={1} max={20} precision={0} />
            </Form.Item>
          </div>
        </section>
      </div>
    </SettingsSection>
  );
}
