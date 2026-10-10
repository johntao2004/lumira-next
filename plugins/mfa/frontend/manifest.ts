import type { FrontendModuleManifest } from "@/platform/modules/types";

export const mfaManifest = {
  id: "mfa",
  version: "1.0.5",
  required: false,
  dependencies: ["identity"],
  routes: [
    { id: "mfa-authenticator", label: "mfaAuthenticator", permission: null, group: "overview", order: 30 },
    { id: "mfa-management", label: "mfaManagement", permission: "platform:mfa:manage", global: true, group: "administration", order: 210 },
  ],
} as const satisfies FrontendModuleManifest;
