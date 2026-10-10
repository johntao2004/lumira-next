"use client";

import { PlatformForm as Form } from "@/platform/components/platform-form";
import { Input, InputNumber, Select, Switch } from "antd";
import { SettingsSection } from "@/platform/components/settings-section";
import { SettingsEnabledControls } from "@/platform/components/settings-enabled-controls";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";

const textNumbers = [
  ["fontSize", "watermarkFontSize", 8, 72],
] as const satisfies readonly (readonly [string, MessageKey, number, number])[];

const layoutNumbers = [
  ["rotate", "watermarkRotate", -180, 180],
  ["gapX", "watermarkGapX", 0, 500],
  ["gapY", "watermarkGapY", 0, 500],
  ["offsetX", "watermarkOffsetX", 0, 500],
  ["offsetY", "watermarkOffsetY", 0, 500],
  ["zIndex", "watermarkZIndex", 0, 9999],
] as const satisfies readonly (readonly [string, MessageKey, number, number])[];

function WatermarkFields() {
  const { t } = usePreferences();
  const form = Form.useFormInstance();
  const mode = Form.useWatch(["data", "mode"], form) ?? "TEXT";
  const textMode = mode !== "IMAGE";

  const validateLines = (_: unknown, lines: string[] = []) => lines.length > 6 || lines.some(line => line.length > 200)
    ? Promise.reject(new Error(t("invalidWatermark")))
    : Promise.resolve();

  return <>
    <Form.Item name={["data", "enabled"]} label={t("enabled")} valuePropName="checked">
      <Switch />
    </Form.Item>
    <SettingsEnabledControls>
    <Form.Item name={["data", "mode"]} label={t("watermarkMode")}>
      <Select options={[
        { value: "TEXT", label: t("watermarkModeFixed") },
        { value: "PERSONALIZED_TEXT", label: t("watermarkModePersonalized") },
        { value: "IMAGE", label: t("watermarkModeImage") },
      ]} />
    </Form.Item>
    {mode === "TEXT" && <Form.Item
      name={["data", "textLines"]}
      label={t("watermarkText")}
      rules={[{ validator: validateLines }]}
      getValueProps={(value: string[] = []) => ({ value: value.join("\n") })}
      getValueFromEvent={event => event.target.value.split("\n")}
    >
      <Input.TextArea className="watermark-text-lines" rows={4} maxLength={1205} showCount />
    </Form.Item>}
    {mode === "PERSONALIZED_TEXT" && <Form.Item
      name={["data", "personalizedTextLines"]}
      label={t("watermarkTemplate")}
      extra="{{username}} · {{nickname}} · {{realName}} · {{userId}}"
      rules={[{ validator: validateLines }]}
      getValueProps={(value: string[] = []) => ({ value: value.join("\n") })}
      getValueFromEvent={event => event.target.value.split("\n")}
    >
      <Input.TextArea className="watermark-text-lines" rows={4} maxLength={1205} showCount />
    </Form.Item>}
    {mode === "IMAGE" && <Form.Item name={["data", "imageUrl"]} label={t("watermarkImage")}>
      <Input maxLength={2000} />
    </Form.Item>}

    <div className="settings-responsive-grid">
      {textMode && <>
        <Form.Item name={["data", "fontColor"]} label={t("watermarkColor")}>
          <Input type="color" />
        </Form.Item>
        <Form.Item name={["data", "fontWeight"]} label={t("watermarkWeight")}>
          <Select options={[
            "normal", "bold", "100", "200", "300", "400", "500", "600", "700", "800", "900",
          ].map(value => ({ value, label: value }))} />
        </Form.Item>
        {textNumbers.map(([field, label, min, max]) => <Form.Item
          key={field}
          name={["data", field]}
          label={t(label)}
          rules={[{ required: true }]}
        >
          <InputNumber min={min} max={max} precision={0} addonAfter="px" />
        </Form.Item>)}
      </>}
      {layoutNumbers.map(([field, label, min, max]) => <Form.Item
        key={field}
        name={["data", field]}
        label={t(label)}
        rules={[{ required: true }]}
      >
        <InputNumber min={min} max={max} precision={0} addonAfter={field === "rotate" ? "°" : undefined} />
      </Form.Item>)}
      <Form.Item name={["data", "opacity"]} label={t("watermarkOpacity")} rules={[{ required: true }]}>
        <InputNumber min={0} max={1} step={0.05} />
      </Form.Item>
    </div>
    </SettingsEnabledControls>
  </>;
}

export function WatermarkPage({ canWrite }: { canWrite: boolean }) {
  return <SettingsSection section="watermark" canWrite={canWrite} showSecondaryActions={false}>
    <WatermarkFields />
  </SettingsSection>;
}
