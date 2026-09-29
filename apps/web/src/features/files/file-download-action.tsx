"use client";

import { Button, Popconfirm } from "antd";
import { useRef } from "react";

/** The request is created only after the user confirms the download prompt. */
export function FileDownloadAction({ href, label, confirmation, confirmText, cancelText }: {
  href: string;
  label: string;
  confirmation: string;
  confirmText: string;
  cancelText: string;
}) {
  const downloadStarted = useRef(false);

  function startDownload() {
    if (downloadStarted.current) return;
    downloadStarted.current = true;
    const link = document.createElement("a");
    link.href = href;
    link.download = "";
    document.body.append(link);
    link.click();
    link.remove();
  }

  return <Popconfirm
    title={confirmation}
    okText={confirmText}
    cancelText={cancelText}
    onConfirm={startDownload}
    onOpenChange={open => { if (open) downloadStarted.current = false; }}
    getPopupContainer={trigger => trigger.parentElement ?? document.body}
  >
    <Button type="link">{label}</Button>
  </Popconfirm>;
}
