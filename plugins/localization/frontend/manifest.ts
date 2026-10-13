import type { FrontendModuleManifest } from "@/platform/modules/types";

export const localizationManifest = {
  id: "localization",
  version: "1.0.11",
  required: false,
  dependencies: ["platform"],
  routes: [
    {
      id: "localization",
      label: "localizationManagement",
      permission: "platform:localization:access",
      operations: {
        read: "platform:localization:read",
        create: "platform:localization:create",
        update: "platform:localization:update",
        delete: "platform:localization:delete",
        import: "platform:localization:import",
        publish: "platform:localization:publish",
      },
      global: true,
      accessScope: "global",
      group: "content-management",
      order: 40,
    },
  ],
} as const satisfies FrontendModuleManifest;
