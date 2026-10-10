import type { FrontendModuleManifest } from "@/platform/modules/types";

export const diffuseAvatarManifest = {
  id: "diffuse-avatar",
  version: "1.0.1",
  required: false,
  dependencies: ["identity", "files"],
  routes: [],
} as const satisfies FrontendModuleManifest;
