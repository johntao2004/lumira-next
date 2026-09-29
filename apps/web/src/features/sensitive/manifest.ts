import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const sensitiveWordsManifest = {
  id: "sensitive-words",
  version: "1.0.0",
  required: false,
  dependencies: ["platform", "dictionaries"],
  routes: [
    { id: "sensitive-words", label: "sensitiveWords", permission: "platform:sensitive-words:read", global: true, group: "content-management", order: 30 },
  ],
} as const satisfies FrontendModuleManifest;
