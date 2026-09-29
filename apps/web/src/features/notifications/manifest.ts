import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const notificationsManifest = {
  id: "notifications",
  version: "1.0.0",
  required: true,
  dependencies: ["platform"],
  routes: [
    { id: "notifications", label: "notificationCenter", permission: "platform:notifications:read", global: true, group: "operations", order: 20 },
  ],
} as const satisfies FrontendModuleManifest;
