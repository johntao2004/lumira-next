"use client";

import { Space } from "antd";
import type { ReactNode } from "react";

/** Keep actions compact and consistent across every table. */
export function TableActions({ children, wrap }: { children: ReactNode; wrap?: boolean }) {
  return <Space className="table-actions" size={[2, 8]} wrap={wrap}>{children}</Space>;
}
