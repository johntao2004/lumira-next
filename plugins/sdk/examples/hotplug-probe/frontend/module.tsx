import { Typography } from "antd";
import type { FrontendModule } from "@/platform/modules/types";
import { hotplugProbeManifest } from "./manifest";

export const probeFrontendModule = {
  ...hotplugProbeManifest,
  pages: {
    "hotplug-probe": () => <Typography.Title level={4}>全新测试插件已在线加载</Typography.Title>,
  },
} satisfies FrontendModule;
