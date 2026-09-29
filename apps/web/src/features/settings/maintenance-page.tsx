"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { Input, Switch, Typography } from "antd";
import { SettingsSection } from "@/platform/components/settings-section";
import { ResourcePicker } from "@/platform/components/resource-picker";
import { usePreferences } from "@/platform/providers";

export function MaintenancePage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();

  return <SettingsSection section="maintenance" canWrite={canWrite}>
    <Typography.Paragraph type="secondary">{t("maintenanceContentHint")}</Typography.Paragraph>
    <Form.Item name={["data", "title"]} label={t("maintenanceTitle")}>
      <Input maxLength={200} />
    </Form.Item>
    <Form.Item name={["data", "message"]} label={t("maintenanceMessage")}>
      <Input.TextArea rows={4} maxLength={2000} />
    </Form.Item>
    <Form.Item name={["data", "allowedRoleIds"]} label={t("maintenanceRoles")} extra={t("maintenanceRolesHint")}>
      <ResourcePicker endpoint="/platform/settings/maintenance/roles" />
    </Form.Item>
    <Form.Item name={["data", "enabled"]} label={t("enabled")} valuePropName="checked">
      <Switch />
    </Form.Item>
  </SettingsSection>;
}
