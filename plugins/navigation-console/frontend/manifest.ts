import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";

export const navigationManifest = {
  id: "navigation-console",
  version: "1.0.0",
  required: false,
  dependencies: ["platform", "navigation"],
  routes: [
    { id: "navigation", label: "navigationManagement", permission: "platform:navigation:access", accessScope: "global", operations: { create: "platform:navigation:create", update: "platform:navigation:update", delete: "platform:navigation:delete" }, global: true, group: "administration", order: 60 },
  ],
} as const satisfies FrontendModuleManifest;
