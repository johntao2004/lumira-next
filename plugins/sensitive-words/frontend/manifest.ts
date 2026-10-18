import type { FrontendModuleManifest } from "@/platform/modules/types";

export const sensitiveWordsManifest = {
  id: "sensitive-words",
  version: "1.0.10",
  required: false,
  dependencies: ["platform", "dictionaries"],
  routes: [
    {
      id: "sensitive-words",
      label: "sensitiveWords",
      permission: "platform:sensitive-words:access",
      operations: {
        read: "platform:sensitive-words:read",
        create: "platform:sensitive-words:create",
        update: "platform:sensitive-words:update",
        delete: "platform:sensitive-words:delete",
        import: "platform:sensitive-words:import",
        "policy-update": "platform:sensitive-words:policy-update",
      },
      global: true,
      accessScope: "global",
      group: "content-management",
      order: 30,
    },
  ],
} as const satisfies FrontendModuleManifest;
