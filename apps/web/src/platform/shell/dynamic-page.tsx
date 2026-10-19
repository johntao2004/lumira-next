/**
 * 为服务端 PAGE 查找已注册组件，缺少实现时明确显示不可用状态，避免把菜单配置的空壳呈现为成功加载。
 * Resolve registered components for server-defined PAGE entries and show an explicit unavailable state when implementation is missing instead of presenting a menu-only shell as loaded.
 */
"use client";

import { ProCard } from "@ant-design/pro-components";
import { Alert, Space, Typography } from "antd";
import { usePreferences } from "../providers";
import { resolvePageComponent } from "./page-component-registry";

/**
 * 服务端 PAGE 的功能组件未随当前部署提供时，使用安全回退；绝不将缺失组件伪装成可用页面。
 *
 * Safe fallback for a server-owned PAGE whose feature component is not bundled
 * in this deployment. Never present a missing component as a working page.
 */
export function DynamicPage({
  title,
  path,
  componentKey,
}: {
  title: string;
  path: string;
  componentKey?: string | null;
}) {
  const { t } = usePreferences();
  const Registered = resolvePageComponent(componentKey);
  if (Registered) {
    return <Registered title={title} path={path} componentKey={componentKey as string} />;
  }
  return (
    <ProCard>
      <Space orientation="vertical" size={8} className="w-full">
        <Alert type="error" showIcon title={t("navigateHint")} />
        <Typography.Text type="secondary">{path}</Typography.Text>
        {componentKey && <Typography.Text code>{componentKey}</Typography.Text>}
      </Space>
    </ProCard>
  );
}
