"use client";

import { history, useLocation } from "@umijs/max";
import { useEffect } from "react";
import { Tabs } from "antd";
import type { ReactNode } from "react";
import type { MessageKey } from "../i18n/messages";
import { usePreferences } from "../providers";
import { useTabAnchor } from "../routing/use-tab-anchor";
import { updateFragment } from "../routing/tab-anchor";

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
  const pathTab = tabs.find((tab) => tab.path === pathname) ?? tabs[0];
  const [activeKey] = useTabAnchor("", tabs.map(tab => tab.key), pathTab?.key ?? "");
  const activeTab = tabs.find(tab => tab.key === activeKey) ?? pathTab;

  useEffect(() => {
    if (activeTab && activeTab.path !== pathname && window.location.pathname === pathname) {
      history.replace(`${activeTab.path}${window.location.search}${updateFragment(window.location.hash, { tab: activeTab.key })}`);
    }
  }, [activeTab?.path, activeTab?.key, pathname]);

  if (!activeTab) return null;
  if (tabs.length === 1) return <>{activeTab.children}</>;

  return (
    <Tabs
      activeKey={activeTab.key}
      destroyOnHidden={!preserveContent}
      onChange={(key) => {
        const next = tabs.find((tab) => tab.key === key);
        if (next && next.path !== pathname) history.push(`${next.path}${window.location.search}${updateFragment(window.location.hash, { tab: next.key })}`);
      }}
      items={tabs.map((tab) => ({
        key: tab.key,
        label: t(tab.label),
        children: tab.children,
      }))}
    />
  );
}
