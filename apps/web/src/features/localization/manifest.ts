import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const localizationManifest = {
  id: "localization",
  version: "1.0.0",
  required: false,
  dependencies: ["platform"],
  routes: [
    { id: "localization", label: "localizationManagement", permission: "platform:localization:read", global: true, group: "administration", order: 200 },
  ],
} as const satisfies FrontendModuleManifest;
