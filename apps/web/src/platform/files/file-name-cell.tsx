"use client";

import { Button, Space } from "antd";
import type { ReactNode } from "react";

export function FileNameCell({ name, previewable = false, onPreview, suffix }: {
  name: string;
  previewable?: boolean;
  onPreview?: () => void;
  suffix?: ReactNode;
}) {
  const content = previewable
    ? <Button type="link" className="file-preview-link" style={{ whiteSpace: "normal", height: "auto", paddingInline: 0, textAlign: "left", overflowWrap: "anywhere" }} onClick={onPreview}>{name}</Button>
    : <span style={{ overflowWrap: "anywhere" }}>{name}</span>;
  return suffix
    ? <Space wrap size={4} align="center">{content}{suffix}</Space>
    : content;
}
