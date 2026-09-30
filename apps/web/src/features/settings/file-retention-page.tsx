"use client";

import { Alert, InputNumber, Modal, Select, Typography } from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SettingsSection } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";

type RetentionValues = { mode: "AUTOMATIC" | "MANUAL"; retentionDays: number };

export function FileRetentionPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  async function confirmPolicy(values: Record<string, unknown>) {
    const data = values.data as Partial<RetentionValues> | undefined;
    if (data?.mode !== "AUTOMATIC") return true;
    return new Promise<boolean>(resolve => {
      Modal.confirm({
        centered: true,
        title: t("fileRetentionConfirmTitle"),
        content: t("fileRetentionConfirm", { days: data.retentionDays ?? 30 }),
        okText: t("confirm"),
        cancelText: t("cancel"),
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
  }

  return <SettingsSection section="file-retention" canWrite={canWrite} confirmBeforeSave={confirmPolicy}>
    <Typography.Paragraph type="secondary">{t("fileRetentionHint")}</Typography.Paragraph>
    <Form.Item name={["data", "mode"]} label={t("fileRetentionMode")} rules={[{ required: true }]}>
      <Select options={[
        { value: "AUTOMATIC", label: t("fileRetentionAutomatic") },
        { value: "MANUAL", label: t("fileRetentionManual") },
      ]} />
    </Form.Item>
    <Form.Item noStyle shouldUpdate={(previous, current) => previous.data?.mode !== current.data?.mode}>
      {({ getFieldValue }) => <>
        <Form.Item name={["data", "retentionDays"]} label={t("fileRetentionDays")} rules={[{ required: true, type: "number", min: 1, max: 365 }]}>
          <InputNumber min={1} max={365} precision={0} disabled={getFieldValue(["data", "mode"]) === "MANUAL"} />
        </Form.Item>
        {getFieldValue(["data", "mode"]) === "AUTOMATIC" && <Alert type="warning" showIcon message={t("fileRetentionExistingWarning")} />}
      </>}
    </Form.Item>
  </SettingsSection>;
}
