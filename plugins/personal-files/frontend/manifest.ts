import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";
export const personalFilesManifest = { id: "personal-files", version: "1.0.0", required: false,
  dependencies: ["identity", "files"], routes: [
    { id: "files", label: "myFiles", permission: null, group: "overview", order: 20 },
  ],
} as const satisfies FrontendModuleManifest;
