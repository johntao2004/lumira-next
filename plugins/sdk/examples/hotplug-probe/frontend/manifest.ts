import type { FrontendModuleManifest } from "@/platform/modules/types";

export const hotplugProbeManifest = {
  id: "hotplug-probe",
  version: "1.0.0",
  required: false,
  dependencies: ["platform"],
  routes: [
    {
      id: "hotplug-probe",
      label: "plugin",
      permission: null,
      global: false,
      group: "operations",
      order: 900,
    },
  ],
} as const satisfies FrontendModuleManifest;
