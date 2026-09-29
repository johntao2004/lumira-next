"use client";

import { ProCard } from "@ant-design/pro-components";
import { Alert, Space, Typography } from "antd";
import { usePreferences } from "../providers";
import { resolvePageComponent } from "./page-component-registry";

/**
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
