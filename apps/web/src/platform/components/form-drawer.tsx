"use client";
import { DrawerForm } from "@ant-design/pro-components";
import { useLayoutEffect, useRef } from "react";
import { DrawerFormActions } from "./drawer-actions";

export function FormDrawer({ open, title, pending, submitDisabled = false, size = 480, formId, onClose, children }: {
  open: boolean; title: string; pending: boolean; submitDisabled?: boolean; size?: number; formId: string; onClose: () => void; children: React.ReactNode;
}) {
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
      render: () => <DrawerFormActions formId={formId} onClose={onClose} pending={pending} submitDisabled={submitDisabled} />,
    }}
  >
    {children}
  </DrawerForm>;
}
