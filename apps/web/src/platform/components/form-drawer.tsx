"use client";
import { DrawerForm } from "@ant-design/pro-components";
import { Button, Space } from "antd";
import { useLayoutEffect, useRef } from "react";
import { usePreferences } from "../providers";
import { AsyncButton } from "./async-button";

export function FormDrawer({ open, title, pending, submitDisabled = false, size = 480, formId, onClose, children }: {
  open: boolean; title: string; pending: boolean; submitDisabled?: boolean; size?: number; formId: string; onClose: () => void; children: React.ReactNode;
}) {
  const { t } = usePreferences();
  const width = size > 480 ? 720 : 480;
  const launcher = useRef<HTMLElement | null>(null);
  useLayoutEffect(() => {
    if (open) {
      launcher.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      return;
    }
    const frame = requestAnimationFrame(() => {
      if (launcher.current?.isConnected && launcher.current.checkVisibility()) {
        launcher.current.focus({ preventScroll: true });
      }
    });
    return () => cancelAnimationFrame(frame);
  }, [open]);
  return <DrawerForm
    open={open}
    title={title}
    width={width}
    component={false}
    onOpenChange={next => { if (!next && !pending) onClose(); }}
    onFinish={async () => false}
    drawerProps={{
      rootClassName: "platform-form-drawer",
      closable: { disabled: pending },
      destroyOnHidden: true,
      onClose: () => { if (!pending) onClose(); },
      styles: { wrapper: { maxWidth: "100%" } },
    }}
    submitter={{
      render: () => <Space className="form-actions">
        <Button onClick={onClose} disabled={pending}>{t("cancel")}</Button>
        <AsyncButton type="primary" form={formId} htmlType="submit" disabled={submitDisabled} pending={pending} label={t("save")} />
      </Space>,
    }}
  >
    {children}
  </DrawerForm>;
}
