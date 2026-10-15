"use client";

import { useEffect, useRef, useState } from "react";
import { Checkbox, InputNumber, Select, Space, Typography } from "antd";
import type { InputNumberProps } from "antd";
import { useQueryClient } from "@tanstack/react-query";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SettingsSection } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";
import {
  capacityBytes,
  DEFAULT_CAPACITY_UNIT,
  DEFAULT_FINITE_CAPACITY_BYTES,
  DEFAULT_FINITE_FILE_COUNT,
  formatCapacity,
  maximumSafeCapacity,
  normalizeFileQuotaSettings,
  nonNegativeInteger,
  selectCapacityUnit,
  type CapacityUnit,
  type FileQuotaSettings,
} from "./file-quota-conversion";

function formatTwoDecimals(value: string | number | null | undefined): string {
  if (value == null || value === "") return "";
  const text = String(value);
  const match = /^(\d+)(?:\.(\d*))?$/.exec(text);
  if (!match) return text;
  return `${match[1]}.${(match[2] ?? "").slice(0, 2).padEnd(2, "0")}`;
}

function CapacityUnitSelector({ unit, onChange, disabled }: { unit: CapacityUnit; onChange: (unit: CapacityUnit) => void; disabled?: boolean }) {
  const { t } = usePreferences();

  return <Select
    aria-label={t("fileQuotaCapacityUnit")}
    value={unit}
    disabled={disabled ? true : undefined}
    onChange={onChange}
    options={[
      { value: "mb", label: t("fileQuotaMBUnit") },
      { value: "gb", label: t("fileQuotaGBUnit") },
    ]}
    style={{ width: 104 }}
  />;
}

type CapacityInputProps = Omit<
  InputNumberProps<string>,
  "controls" | "formatter" | "max" | "min" | "onChange" | "parser" | "precision" | "stringMode" | "value"
> & {
  value?: number | string | null;
  onChange?: (value: number | null) => void;
  unit: CapacityUnit;
  onUnitChange: (unit: CapacityUnit) => void;
  onEdited: (bytes: number | null) => void;
  onToggleUnlimited: (unlimited: boolean) => number;
};

function CapacityInput({ value, onChange, unit, onUnitChange, onEdited, onToggleUnlimited, style, disabled, onFocus, onBlur, ...inputProps }: CapacityInputProps) {
  const { t } = usePreferences();
  const [draft, setDraft] = useState<string | null>(null);
  const focused = draft !== null;
  const unlimited = nonNegativeInteger(value) === 0;
  const inputDisabled = disabled || (unlimited && !focused) ? true : undefined;

  return <div className="flex flex-col gap-2">
    <Checkbox
      aria-label={t("fileQuotaCapacityUnlimited")}
      checked={unlimited}
      disabled={disabled ? true : undefined}
      onChange={event => onChange?.(onToggleUnlimited(event.target.checked))}
    >{t("fileQuotaCapacityUnlimited")}</Checkbox>
    <Space.Compact block style={{ width: "100%" }}>
      <InputNumber<string>
        {...inputProps}
        value={focused ? draft : formatCapacity(value, unit)}
        stringMode
        precision={2}
        formatter={(displayValue, info) => focused
          ? (info.userTyping ? info.input : String(displayValue ?? ""))
          : formatTwoDecimals(displayValue)}
        min="0"
        max={maximumSafeCapacity(unit)}
        controls={false}
        disabled={inputDisabled}
        onFocus={event => { setDraft(formatCapacity(value, unit)); onFocus?.(event); }}
        onBlur={event => { setDraft(null); onBlur?.(event); }}
        onChange={displayValue => {
          setDraft(displayValue == null ? "" : String(displayValue));
          const bytes = capacityBytes(displayValue, unit);
          onEdited(bytes);
          onChange?.(bytes);
        }}
        style={{ ...style, flex: 1, minWidth: 0, width: 0 }}
      />
      <CapacityUnitSelector unit={unit} disabled={disabled || unlimited ? true : undefined} onChange={onUnitChange} />
    </Space.Compact>
  </div>;
}

type FileCountInputProps = Omit<
  InputNumberProps<string>,
  "controls" | "max" | "min" | "onChange" | "stringMode" | "value"
> & {
  value?: number | string | null;
  onChange?: (value: string | null) => void;
  onEdited: (files: number | null) => void;
  onToggleUnlimited: (unlimited: boolean) => number;
};

function FileCountInput({ value, onChange, onEdited, onToggleUnlimited, disabled, style, ...inputProps }: FileCountInputProps) {
  const { t } = usePreferences();
  const count = nonNegativeInteger(value);
  const unlimited = count === 0;

  return <div className="flex flex-col gap-2">
    <Checkbox
      aria-label={t("fileQuotaFileCountUnlimited")}
      checked={unlimited}
      disabled={disabled ? true : undefined}
      onChange={event => onChange?.(String(onToggleUnlimited(event.target.checked)))}
    >{t("fileQuotaFileCountUnlimited")}</Checkbox>
    <InputNumber<string>
      {...inputProps}
      value={value == null ? value : String(value)}
      stringMode
      min="0"
      max={String(Number.MAX_SAFE_INTEGER)}
      controls={false}
      disabled={disabled || unlimited ? true : undefined}
      onChange={next => {
        const files = nonNegativeInteger(next);
        onEdited(files);
        onChange?.(next);
      }}
      style={{ ...style, width: "100%" }}
    />
  </div>;
}

function FileQuotaFields() {
  const { t } = usePreferences();
  const form = Form.useFormInstance();
  const rawBytes = Form.useWatch(["data", "maximumBytes"], form);
  const rawFiles = Form.useWatch(["data", "maximumFiles"], form);
  const [unit, setUnit] = useState<CapacityUnit>(DEFAULT_CAPACITY_UNIT);
  const autoUnitSelected = useRef(false);
  const lastFiniteBytes = useRef(DEFAULT_FINITE_CAPACITY_BYTES);
  const lastFiniteFiles = useRef(DEFAULT_FINITE_FILE_COUNT);

  useEffect(() => {
    const bytes = nonNegativeInteger(rawBytes);
    if (bytes === null) return;
    if (bytes > 0) lastFiniteBytes.current = bytes;
    if (!autoUnitSelected.current) {
      setUnit(selectCapacityUnit(bytes));
      autoUnitSelected.current = true;
    }
  }, [rawBytes]);

  useEffect(() => {
    const files = nonNegativeInteger(rawFiles);
    if (files !== null && files > 0) lastFiniteFiles.current = files;
  }, [rawFiles]);

  function toggleCapacityUnlimited(unlimited: boolean) {
    const current = nonNegativeInteger(rawBytes);
    if (unlimited) {
      if (current !== null && current > 0) lastFiniteBytes.current = current;
      return 0;
    }
    return lastFiniteBytes.current > 0 ? lastFiniteBytes.current : DEFAULT_FINITE_CAPACITY_BYTES;
  }

  function toggleFileCountUnlimited(unlimited: boolean) {
    const current = nonNegativeInteger(rawFiles);
    if (unlimited) {
      if (current !== null && current > 0) lastFiniteFiles.current = current;
      return 0;
    }
    return lastFiniteFiles.current > 0 ? lastFiniteFiles.current : DEFAULT_FINITE_FILE_COUNT;
  }

  return <>
    <Form.Item
      name={["data", "maximumBytes"]}
      label={t("fileQuotaStorageLimit")}
      extra={t("fileQuotaCapacityHint")}
      rules={[{
        validator: (_, value) => nonNegativeInteger(value) === null
          ? Promise.reject(new Error(t("invalidFileQuota")))
          : Promise.resolve(),
      }]}
    >
      <CapacityInput
        unit={unit}
        onUnitChange={setUnit}
        onEdited={bytes => { if (bytes !== null && bytes > 0) lastFiniteBytes.current = bytes; }}
        onToggleUnlimited={toggleCapacityUnlimited}
      />
    </Form.Item>
    <Form.Item name={["data", "maximumFiles"]} label={t("fileQuotaFileCountLimit")} extra={t("fileQuotaMaximumFilesHint")} rules={[{
      validator: (_, value) => nonNegativeInteger(value) === null
        ? Promise.reject(new Error(t("invalidFileQuota")))
        : Promise.resolve(),
    }]}>
      <FileCountInput
        onEdited={files => { if (files !== null && files > 0) lastFiniteFiles.current = files; }}
        onToggleUnlimited={toggleFileCountUnlimited}
      />
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
