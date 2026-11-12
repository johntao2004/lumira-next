import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const platformManifest = {
  id: "platform",
  version: "1.0.0",
  required: true,
  dependencies: ["identity"],
  routes: [
    { id: "home", label: "home", permission: null, group: "overview", order: 10 },
    { id: "modules", label: "modules", permission: "platform:modules:access", accessScope: "global", operations: { install: "platform:modules:install", update: "platform:modules:update", uninstall: "platform:modules:uninstall" }, group: "administration", order: 10 },
  ],
} as const satisfies FrontendModuleManifest;
