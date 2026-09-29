"use client";

import { Button, Typography } from "antd";
import { MinusOutlined, PlusOutlined } from "@ant-design/icons";

export function HierarchyTableLabel({ label, level, parentLabel, hasChildren, expanded, onToggle }: {
  label: string;
  level: number;
  parentLabel: string | null;
  hasChildren: boolean;
  expanded: boolean;
  onToggle: () => void;
}) {
  return <span style={{ display: "inline-flex", alignItems: "center", paddingInlineStart: level * 20 }}>
    {hasChildren
      ? <Button type="text" size="small" aria-label={label} aria-expanded={expanded} icon={expanded ? <MinusOutlined /> : <PlusOutlined />} onClick={onToggle} />
      : <span aria-hidden="true" style={{ width: 32, flex: "0 0 32px" }} />}
    <Typography.Text>{label}</Typography.Text>
    {parentLabel && <Typography.Text type="secondary" style={{ marginInlineStart: 8, fontSize: 12 }}>{parentLabel}</Typography.Text>}
  </span>;
}
