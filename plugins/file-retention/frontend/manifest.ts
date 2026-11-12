import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";
export const fileRetentionManifest = {
  id: "file-retention",
  version: "1.0.0",
  required: false,
  dependencies: ["files", "settings"],
  routes: [
    { id: "file-retention", label: "fileRetention", permission: "platform:file-retention:access", accessScope: "global", operations: { update: "platform:file-retention:update" }, hidden: true, group: "administration", order: 195 },
  ],
} as const satisfies FrontendModuleManifest;
