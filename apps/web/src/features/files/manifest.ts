import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const filesManifest = {
  id: "files",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "settings"],
  routes: [
    { id: "files", label: "myFiles", permission: null, group: "overview", order: 20 },
    { id: "files-all", label: "allFiles", permission: "platform:files:read", global: true, group: "content-management", order: 10 },
  ],
} as const satisfies FrontendModuleManifest;
