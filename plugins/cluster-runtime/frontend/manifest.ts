import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";
export const clusterRuntimeManifest = {
  id: "cluster-runtime", version: "1.3.0", required: false,
  dependencies: ["platform"], routes: [],
} as const satisfies FrontendModuleManifest;
