"use client";

import { history, useLocation } from "@umijs/max";
import { Tabs } from "antd";
import type { ReactNode } from "react";
import type { MessageKey } from "../i18n/messages";
import { usePreferences } from "../providers";

export type RouteTab = {
  key: string;
  path: string;
  label: MessageKey;
  children: ReactNode;
};

/** Keep related pages in one workspace while preserving each tab's deep link. */
export function RouteTabs({ tabs, preserveContent = false }: { tabs: readonly RouteTab[]; preserveContent?: boolean }) {
  const { pathname } = useLocation();
  const { t } = usePreferences();
  const activeTab = tabs.find((tab) => tab.path === pathname) ?? tabs[0];

  if (!activeTab) return null;
  if (tabs.length === 1) return <>{activeTab.children}</>;

  return (
    <Tabs
      activeKey={activeTab.key}
      destroyOnHidden={!preserveContent}
      onChange={(key) => {
        const next = tabs.find((tab) => tab.key === key);
        if (next && next.path !== pathname) history.push(next.path);
      }}
      items={tabs.map((tab) => ({
        key: tab.key,
        label: t(tab.label),
        children: tab.children,
      }))}
    />
  );
}
