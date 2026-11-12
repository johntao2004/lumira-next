import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";

export const monitoringManifest = {
  id: "monitoring",
  version: "1.0.0",
  required: false,
  dependencies: ["platform", "communication", "events", "notifications", "identity"],
  routes: [
    { id: "monitoring", label: "monitoring", permission: "platform:monitoring:access", accessScope: "global", group: "operations", order: 10 },
    { id: "api-docs", label: "apiDocs", permission: "platform:api-docs:access", accessScope: "global", global: true, group: "operations", order: 20 },
  ],
} as const satisfies FrontendModuleManifest;
