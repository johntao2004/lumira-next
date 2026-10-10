"use client";

import { Tabs, type TabsProps } from "antd";
import { useTabAnchor } from "../routing/use-tab-anchor";

/** Shared URL-backed tabs for groups whose content does not need the key in a parent query. */
export function AnchorTabs({ anchor = "", enabled = true, defaultActiveKey, items = [], onChange, ...props }: Omit<TabsProps, "activeKey"> & { anchor?: string; enabled?: boolean }) {
  const keys = items.map(item => item.key);
  const fallback = keys.includes(defaultActiveKey ?? "") ? defaultActiveKey! : keys[0] ?? "";
  const [activeKey, setActiveKey] = useTabAnchor(anchor, keys, fallback, enabled);
  return <Tabs {...props} items={items} activeKey={activeKey} onChange={key => { setActiveKey(key); onChange?.(key); }} />;
}
