import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const navigationManifest = {
  id: "navigation",
  version: "1.0.0",
  required: true,
  dependencies: ["platform"],
  routes: [
    { id: "navigation", label: "navigationManagement", permission: "platform:navigation:read", global: true, group: "administration", order: 260 },
  ],
} as const satisfies FrontendModuleManifest;
