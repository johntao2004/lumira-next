import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const notificationsManifest = {
  id: "notifications",
  version: "1.0.0",
  required: true,
  dependencies: ["platform"],
  routes: [
    { id: "notifications", label: "notificationCenter", permission: "platform:notifications:access", accessScope: "global", operations: { create: "platform:notifications:create" }, global: true, group: "communication", order: 10 },
  ],
} as const satisfies FrontendModuleManifest;
