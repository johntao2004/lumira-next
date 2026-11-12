import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";

export const auditManifest = {
  id: "audit-console",
  version: "1.0.0",
  required: false,
  dependencies: ["platform"],
  routes: [
    { id: "audit", label: "audit", permission: "platform:audit:access", accessScope: "global", global: true, group: "operations", order: 30 },
  ],
} as const satisfies FrontendModuleManifest;
