import type { FrontendModuleManifest } from "../../platform/modules/types.ts";
export const navigationManifest = {
 id: "navigation", version: "1.0.0", required: true, dependencies: ["platform"], routes: [],
} as const satisfies FrontendModuleManifest;
