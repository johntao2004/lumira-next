"use client";

import { ConfigProvider } from "antd";
import type { ReactNode } from "react";
import { PlatformForm as Form } from "./platform-form";

/** Keep dependent settings locked while respecting form permissions and saving. */
export function SettingsEnabledControls({
  children,
  name = ["data", "enabled"],
}: {
  children: ReactNode;
  name?: string | (string | number)[];
}) {
  const form = Form.useFormInstance();
  const enabled = Form.useWatch(name, form) ?? form.getFieldValue(name);
  const { componentDisabled } = ConfigProvider.useConfig();
  return <ConfigProvider componentDisabled={componentDisabled || enabled !== true}>{children}</ConfigProvider>;
}
