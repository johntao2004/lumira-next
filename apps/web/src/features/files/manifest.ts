import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const filesManifest = {
  id: "files",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "settings"],
  routes: [],
} as const satisfies FrontendModuleManifest;
