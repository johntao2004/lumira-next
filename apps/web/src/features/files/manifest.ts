import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const filesManifest = {
  id: "files",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "settings"],
  routes: [
    { id: "files", label: "myFiles", permission: null, group: "overview", order: 20 },
    { id: "files-all", label: "allFiles", permission: "platform:files-all:access", accessScope: "global", operations: { delete: "platform:files-all:delete", restore: "platform:files-all:restore", purge: "platform:files-all:purge", preview: "platform:files-all:preview", download: "platform:files-all:download", "update-quota": "platform:files-all:update-quota" }, global: true, group: "content-management", order: 10 },
  ],
} as const satisfies FrontendModuleManifest;
