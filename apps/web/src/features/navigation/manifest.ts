import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const navigationManifest = {
  id: "navigation",
  version: "1.0.0",
  required: true,
  dependencies: ["platform"],
  routes: [
    { id: "navigation", label: "navigationManagement", permission: "platform:navigation:access", accessScope: "global", operations: { create: "platform:navigation:create", update: "platform:navigation:update", delete: "platform:navigation:delete" }, global: true, group: "administration", order: 60 },
  ],
} as const satisfies FrontendModuleManifest;
