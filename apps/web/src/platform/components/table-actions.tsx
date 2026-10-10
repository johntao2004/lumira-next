"use client";

import { Space } from "antd";
import type { ReactNode } from "react";

// Auto table layout expands this minimal track to the widest visible action
// content. A truthy width also bypasses ProTable's 200px fixed-column default.
export const tableActionColumn = {
  fixed: "right" as const,
  width: 1,
  className: "table-action-column",
};

/** Keep actions compact and consistent across every table. */
export function TableActions({ children, wrap }: { children: ReactNode; wrap?: boolean }) {
  return <Space className="table-actions" size={[2, 8]} wrap={wrap}>{children}</Space>;
}
