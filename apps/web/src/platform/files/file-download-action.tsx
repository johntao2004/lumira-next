"use client";

import { Button, Popconfirm, App } from "antd";
import { useEffect, useRef } from "react";
import { apiBlob } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import { currentSessionRevision } from "@/platform/auth/session-lifecycle";
import { downloadBlob } from "@/platform/files/download-blob";
import { usePreferences } from "@/platform/providers";

/** The request is created only after the user confirms the download prompt. */
export function FileDownloadAction({ href, fileName, label, confirmation, confirmText, cancelText, disabled = false }: {
  href: string;
  fileName: string;
  label: string;
  confirmation: string;
  confirmText: string;
  cancelText: string;
  disabled?: boolean;
}) {
  const pending = useRef(false);
  const controller = useRef<AbortController | null>(null);
  const { t } = usePreferences();
  const { message } = App.useApp();

  useEffect(() => () => {
    controller.current?.abort();
    controller.current = null;
  }, []);

  async function startDownload() {
    if (disabled || pending.current) return;
    pending.current = true;
    const request = new AbortController();
    const revision = currentSessionRevision();
    controller.current = request;
    try {
      const blob = await apiBlob(href.replace(/^\/api(?=\/)/, ""), { signal: request.signal });
      request.signal.throwIfAborted();
      if (revision !== currentSessionRevision()) return;
      downloadBlob(blob, fileName);
    } catch (error) {
      if (request.signal.aborted || revision !== currentSessionRevision()) return;
      void message.error(t(messageKeyFor(error, ["forbidden"])));
    } finally {
      if (controller.current === request) controller.current = null;
      pending.current = false;
    }
  }

  return <Popconfirm
    title={confirmation}
    okText={confirmText}
    cancelText={cancelText}
    onConfirm={startDownload}
    // Escape the fixed table cell's stacking and overflow boundaries.
    getPopupContainer={() => document.body}
  >
    <Button type="link" disabled={disabled}>{label}</Button>
  </Popconfirm>;
}
