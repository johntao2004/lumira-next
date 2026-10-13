import type { FrontendModuleManifest } from "@/platform/modules/types";

export const mfaManifest = {
  id: "mfa",
  version: "1.0.9",
  required: false,
  dependencies: ["identity"],
  routes: [
    { id: "mfa-authenticator", label: "mfaAuthenticator", permission: null, hidden: true, group: "user-management", order: 30 },
    {
      id: "mfa-management",
      label: "mfaManagement",
      permission: "platform:mfa:management-access",
      operations: {
        read: "platform:mfa:management-read",
        reset: "platform:mfa:management-reset",
      },
      global: true,
      accessScope: "global",
      scopedOperations: ["reset"],
      group: "user-management",
      order: 120,
    },
  ],
} as const satisfies FrontendModuleManifest;
