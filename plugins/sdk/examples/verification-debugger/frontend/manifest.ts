import type { FrontendModuleManifest } from "@/platform/modules/types";

export const verificationDebuggerManifest = {
  id: "verification-debugger",
  version: "1.0.5",
  required: false,
  dependencies: ["platform"],
  routes: [
    {
      id: "verification-debugger",
      label: "verificationDebuggerTitle",
      permission: "platform:verification-debugger:access",
      operations: {
        read: "platform:verification-debugger:read",
        control: "platform:verification-debugger:control",
        fixture: "platform:verification-debugger:fixture",
      },
      accessScope: "global",
      global: true,
      group: "operations",
      order: 950,
    },
  ],
} as const satisfies FrontendModuleManifest;
