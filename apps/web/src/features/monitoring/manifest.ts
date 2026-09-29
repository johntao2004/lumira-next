import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const monitoringManifest = {
  id: "monitoring",
  version: "1.0.0",
  required: true,
  dependencies: ["platform", "communication", "events", "notifications", "directory"],
  routes: [
    { id: "monitoring", label: "monitoring", permission: "platform:audit:read", group: "operations", order: 10 },
  ],
} as const satisfies FrontendModuleManifest;
