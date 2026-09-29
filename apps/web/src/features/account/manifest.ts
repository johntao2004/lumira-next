import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

/** Identity owns account drawers and auth capabilities, even without a dashboard page. */
export const identityManifest = {
  id: "identity",
  version: "1.0.0",
  required: true,
  dependencies: [],
  routes: [],
} as const satisfies FrontendModuleManifest;
