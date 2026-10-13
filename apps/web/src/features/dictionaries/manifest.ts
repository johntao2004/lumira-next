import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const dictionariesManifest = {
  id: "dictionaries",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform"],
  routes: [
    { id: "dictionaries", label: "dictionaries", permission: "platform:dictionaries:access", accessScope: "global", operations: { create: "platform:dictionaries:create", update: "platform:dictionaries:update", delete: "platform:dictionaries:delete", import: "platform:dictionaries:import", export: "platform:dictionaries:export" }, group: "content-management", order: 20 },
  ],
} as const satisfies FrontendModuleManifest;
