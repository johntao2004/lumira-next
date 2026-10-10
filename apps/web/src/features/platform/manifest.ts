import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const platformManifest = {
  id: "platform",
  version: "1.0.0",
  required: true,
  dependencies: ["identity"],
  routes: [
    { id: "home", label: "home", permission: null, group: "overview", order: 10 },
    { id: "modules", label: "modules", permission: "platform:modules:read", group: "administration", order: 10 },
    { id: "ai-console", label: "aiConsole", permission: "platform:settings:read", group: "ai-management", order: 10 },
    { id: "ai-models", label: "aiModels", permission: "platform:settings:read", group: "ai-management", order: 20 },
  ],
} as const satisfies FrontendModuleManifest;
