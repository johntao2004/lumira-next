"use client";

import { Button, Space } from "antd";
import type { ReactNode } from "react";
import { usePreferences } from "../providers";
import { AsyncButton } from "./async-button";

/** Render inside the Drawer footer, outside its scrolling body. */
export function DrawerActions({ children }: { children: ReactNode }) {
  return <Space wrap className="form-actions" style={{ width: "100%", justifyContent: "flex-end" }}>{children}</Space>;
}

export function DrawerFormActions({ formId, onClose, pending, submitDisabled = false, submitLabel }: {
  formId: string; onClose: () => void; pending: boolean; submitDisabled?: boolean; submitLabel?: string;
}) {
  const { t } = usePreferences();
  return <DrawerActions>
    <Button onClick={onClose} disabled={pending}>{t("cancel")}</Button>
    <AsyncButton type="primary" form={formId} htmlType="submit" disabled={submitDisabled} pending={pending} label={submitLabel ?? t("save")} />
  </DrawerActions>;
}
