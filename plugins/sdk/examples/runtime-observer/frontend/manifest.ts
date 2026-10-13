import type { FrontendModuleManifest } from "@/platform/modules/types";

export const runtimeObserverManifest = {
  id: "runtime-observer",
  version: "1.0.0",
  required: false,
  dependencies: ["platform"],
  routes: [
    {
      id: "runtime-observer",
      label: "runtimeObserverTitle",
      permission: "platform:runtime-observer:access",
      operations: {
        read: "platform:runtime-observer:read",
      },
      accessScope: "global",
      global: true,
      group: "operations",
      order: 951,
    },
  ],
} as const satisfies FrontendModuleManifest;
