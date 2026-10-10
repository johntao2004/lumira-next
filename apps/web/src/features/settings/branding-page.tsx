"use client";

import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { Button, ConfigProvider, Input, InputNumber, Space, Switch, Typography } from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SettingsSection } from "@/platform/components/settings-section";
import { upload } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";

const textFields = [
  ["companyName", "companyName", 200, false],
  ["footerIcp", "footerIcp", 200, false],
  ["footerPoliceBeian", "footerPoliceBeian", 200, false],
  ["footerCopyright", "footerCopyright", 1000, false],
] as const satisfies readonly (readonly [string, MessageKey, number, boolean])[];

const imageFields = [
  ["websiteFaviconUrl", "websiteFavicon", "FAVICON"],
  ["websiteLogoUrl", "websiteLogo", "LOGO"],
  ["loginBackgroundUrl", "loginBackground", "LOGIN_BACKGROUND"],
] as const satisfies readonly (readonly [string, MessageKey, BrandingImageSlot])[];

type BrandingImageSlot = "FAVICON" | "LOGO" | "LOGIN_BACKGROUND";
type ImageControlProps = {
  value?: string;
  onChange?: (value: string) => void;
  field: string;
  label: string;
  slot: BrandingImageSlot;
  canWrite: boolean;
  onPendingChange: (field: string, pending: boolean) => void;
};
type BrandingAssetResponse = { url: string; sizeBytes: number };

const imageUploadErrors = [
  "imageUploadFailed",
  "uploadRateLimited",
  "forbidden",
  "fileQuotaExceeded",
] as const satisfies readonly MessageKey[];

function BrandingLinkControls({ enabledField, children }: { enabledField: "githubLinkEnabled" | "helpLinkEnabled"; children: ReactNode }) {
  const form = Form.useFormInstance();
  const enabled = Form.useWatch(["data", enabledField], form) ?? form.getFieldValue(["data", enabledField]);
  const { componentDisabled } = ConfigProvider.useConfig();
  return <ConfigProvider componentDisabled={componentDisabled || enabled !== true}>{children}</ConfigProvider>;
}

function BrandingImageField({ value = "", onChange, field, label, slot, canWrite, onPendingChange }: ImageControlProps) {
  const { t } = usePreferences();
  const { componentDisabled } = ConfigProvider.useConfig();
  const input = useRef<HTMLInputElement>(null);
  const generation = useRef(0);
  const mounted = useRef(true);
  const objectUrl = useRef("");
  const requestPending = useRef(false);
  const pendingCallback = useRef(onPendingChange);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<MessageKey | null>(null);
  const [localPreview, setLocalPreview] = useState<{ assetUrl: string; objectUrl: string } | null>(null);

  useEffect(() => { pendingCallback.current = onPendingChange; }, [onPendingChange]);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      generation.current += 1;
      if (requestPending.current) {
        requestPending.current = false;
        pendingCallback.current(field, false);
      }
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    };
  }, []);

  const preview = localPreview?.assetUrl === value ? localPreview.objectUrl : value;
  const disabled = !canWrite || componentDisabled || uploading;

  async function choose(file: File) {
    if (disabled) return;
    const operation = ++generation.current;
    requestPending.current = true;
    setUploading(true);
    setError(null);
    pendingCallback.current(field, true);
    try {
      const response = await upload<BrandingAssetResponse>(
        `/platform/settings/branding/assets?slot=${slot}`,
        file,
      );
      if (!mounted.current || generation.current !== operation) return;
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
      const nextObjectUrl = URL.createObjectURL(file);
      objectUrl.current = nextObjectUrl;
      setLocalPreview({ assetUrl: response.url, objectUrl: nextObjectUrl });
      onChange?.(response.url);
    } catch (failure) {
      if (mounted.current && generation.current === operation)
        setError(messageKeyFor(failure, imageUploadErrors, "imageUploadFailed"));
    } finally {
      if (mounted.current && generation.current === operation) {
        requestPending.current = false;
        setUploading(false);
        pendingCallback.current(field, false);
      }
    }
  }

  function remove() {
    if (disabled) return;
    generation.current += 1;
    if (objectUrl.current) {
      URL.revokeObjectURL(objectUrl.current);
      objectUrl.current = "";
    }
    setLocalPreview(null);
    setError(null);
    onChange?.("");
  }

  return <div data-testid={`branding-image-${field}`} className="branding-image-field">
    {preview && <img className={`branding-image-preview branding-image-preview-${slot.toLowerCase().replaceAll("_", "-")}`} src={preview} alt={label} />}
    <Space size={8} wrap>
      <Button htmlType="button" disabled={disabled} onClick={() => input.current?.click()}>
        {value ? t("replaceImage") : t("uploadImage")}
      </Button>
      {value && <Button htmlType="button" disabled={disabled} onClick={remove}>{t("removeImage")}</Button>}
      {uploading && <Typography.Text type="secondary" role="status">{t("imageUploading")}</Typography.Text>}
    </Space>
    <input
      ref={input}
      className="branding-image-file-input"
      type="file"
      accept="image/png,image/jpeg"
      aria-label={`${label} ${t("uploadImage")}`}
      aria-hidden="true"
      tabIndex={-1}
      hidden
      style={{ display: "none" }}
      disabled={disabled}
      onChange={event => {
        const file = event.currentTarget.files?.[0];
        event.currentTarget.value = "";
        if (file) void choose(file);
      }}
    />
    <Typography.Text type="secondary">{t("brandingImageUploadHint")}</Typography.Text>
    {error && <Typography.Text type="danger" role="alert">{t(error)}</Typography.Text>}
  </div>;
}

export function BrandingPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  const [uploadingFields, setUploadingFields] = useState<Record<string, boolean>>({});
  const saveBlocked = Object.values(uploadingFields).some(Boolean);
  const onPendingChange = (field: string, pending: boolean) =>
    setUploadingFields(current => ({ ...current, [field]: pending }));

  return <SettingsSection section="branding" canWrite={canWrite} saveBlocked={saveBlocked} showSecondaryActions={false}>
    <Form.Item
      name={["data", "websiteName"]}
      label={t("websiteName")}
      rules={[{ required: true, whitespace: true, message: t("invalidBranding") }]}
    >
      <Input maxLength={200} />
    </Form.Item>
    <Form.Item name={["data", "githubLinkEnabled"]} label={t("githubLinkEnabled")} valuePropName="checked"><Switch /></Form.Item>
    <BrandingLinkControls enabledField="githubLinkEnabled">
      <Form.Item name={["data", "githubLinkUrl"]} label={t("githubLink")}>
        <Input maxLength={2000} />
      </Form.Item>
    </BrandingLinkControls>
    <Form.Item name={["data", "helpLinkEnabled"]} label={t("helpLinkEnabled")} valuePropName="checked"><Switch /></Form.Item>
    <BrandingLinkControls enabledField="helpLinkEnabled">
      <Form.Item name={["data", "helpLinkUrl"]} label={t("helpLink")}>
        <Input maxLength={2000} />
      </Form.Item>
    </BrandingLinkControls>
    {imageFields.map(([field, label, slot]) => <Form.Item key={field} name={["data", field]} label={t(label)}>
      <BrandingImageField
        field={field}
        label={t(label)}
        slot={slot}
        canWrite={canWrite}
        onPendingChange={onPendingChange}
      />
    </Form.Item>)}
    {textFields.map(([field, label, max, required]) => <Form.Item
      key={field}
      name={["data", field]}
      label={t(label)}
      rules={required ? [{ required: true, whitespace: true, message: t("invalidBranding") }] : []}
    >
      <Input maxLength={max} />
    </Form.Item>)}
    <Form.Item name={["data", "copyrightStartYear"]} label={t("copyrightStartYear")} rules={[{ required: true}]}>
      <InputNumber min={1900} max={9999} precision={0} />
    </Form.Item>
  </SettingsSection>;
}
