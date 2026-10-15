"use client";

import { Input, InputNumber, Select, Space, Typography } from "antd";
import type { InputNumberProps } from "antd";
import { useQueryClient } from "@tanstack/react-query";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SettingsSection } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";
import {
  capacityBytes,
  convertCapacityValue,
  DEFAULT_CAPACITY_UNIT,
  normalizeFileQuotaSettings,
  nonNegativeInteger,
  type CapacityUnit,
  type FileQuotaSettings,
} from "./file-quota-conversion";

function CapacityUnitSelector({ unit }: { unit: CapacityUnit }) {
  const { t } = usePreferences();
  const form = Form.useFormInstance();

  function changeUnit(nextUnit: CapacityUnit) {
    if (nextUnit === unit) return;
    const field = ["data", "maximumBytes"];
    const converted = convertCapacityValue(form.getFieldValue(field), unit, nextUnit);
    if (converted === null) {
      void form.validateFields([field]).catch(() => undefined);
      return;
    }
    form.setFieldsValue({ data: {
      maximumBytes: converted,
      maximumBytesUnit: nextUnit,
    } });
    void form.validateFields([field]).catch(() => undefined);
  }

  return <Select
    aria-label={t("fileQuotaCapacityUnit")}
    value={unit}
    onChange={changeUnit}
    options={[
      { value: "bytes", label: t("fileQuotaByteUnit") },
      { value: "gib", label: t("fileQuotaGiBUnit") },
    ]}
    style={{ width: 104 }}
  />;
}

type CapacityInputProps = Omit<
  InputNumberProps<string>,
  "addonAfter" | "controls" | "max" | "min" | "stringMode" | "style"
> & { unit: CapacityUnit };

function CapacityInput({ unit, ...inputProps }: CapacityInputProps) {
  return <Space.Compact block style={{ width: "100%" }}>
    <InputNumber<string>
      {...inputProps}
      stringMode
      min="0"
      max={unit === "bytes" ? String(Number.MAX_SAFE_INTEGER) : undefined}
      controls={false}
      style={{ flex: 1, minWidth: 0, width: 0 }}
    />
    <CapacityUnitSelector unit={unit} />
  </Space.Compact>;
}

function FileQuotaFields() {
  const { t } = usePreferences();
  const form = Form.useFormInstance();
  const unit = (Form.useWatch(["data", "maximumBytesUnit"], form) as CapacityUnit | undefined) ?? DEFAULT_CAPACITY_UNIT;

  return <>
    <Form.Item name={["data", "maximumBytesUnit"]} initialValue={DEFAULT_CAPACITY_UNIT} hidden>
      <Input />
    </Form.Item>
    <Form.Item
      name={["data", "maximumBytes"]}
      label={t("fileQuotaStorageLimit")}
      extra={t(unit === "gib" ? "fileQuotaCapacityGiBHint" : "fileQuotaCapacityBytesHint")}
      dependencies={[["data", "maximumBytesUnit"]]}
      rules={[{
        validator: (_, value) => capacityBytes(value, unit) === null
          ? Promise.reject(new Error(t("invalidFileQuota")))
          : Promise.resolve(),
      }]}
    >
      <CapacityInput unit={unit} />
    </Form.Item>
    <Form.Item name={["data", "maximumFiles"]} label={t("fileQuotaFileCountLimit")} extra={t("fileQuotaMaximumFilesHint")} rules={[{
      validator: (_, value) => nonNegativeInteger(value) === null
        ? Promise.reject(new Error(t("invalidFileQuota")))
        : Promise.resolve(),
    }]}>
      <InputNumber stringMode min={0} max={Number.MAX_SAFE_INTEGER} controls={false} style={{ width: "100%" }} />
    </Form.Item>
  </>;
}

export function FileQuotaSettingsPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  const client = useQueryClient();

  async function onSaved() {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["settings", "file-quota"] }),
      client.invalidateQueries({ queryKey: ["user-file-quota-preview"] }),
      client.invalidateQueries({ queryKey: ["files", "usage"] }),
    ]);
  }

  return <SettingsSection<FileQuotaSettings>
    section="file-quota"
    formName="settings-file-quota"
    canWrite={canWrite}
    showSecondaryActions={false}
    onSaved={onSaved}
    prepareSubmission={({ formValues }) => {
      const data = formValues.data as Record<string, unknown> | undefined;
      const normalized = normalizeFileQuotaSettings(data ?? {});
      if (!normalized) throw new Error("invalidFileQuota");
      return { data: normalized };
    }}
  >
    <Typography.Paragraph type="secondary">{t("fileQuotaSystemDefaultHint")}</Typography.Paragraph>
    <FileQuotaFields />
  </SettingsSection>;
}
