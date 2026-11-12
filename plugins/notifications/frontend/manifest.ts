import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";

export const notificationsManifest = {
  id: "notifications",
  version: "1.0.0",
  required: false,
  dependencies: ["platform", "events"],
  routes: [
    { id: "notifications", label: "notificationCenter", permission: "platform:notifications:access", accessScope: "global", operations: { create: "platform:notifications:create" }, global: true, group: "communication", order: 10 },
  ],
} as const satisfies FrontendModuleManifest;
