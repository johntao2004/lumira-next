import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const settingsManifest = {
  id: "settings",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "dictionaries"],
  routes: [
    { id: "branding", label: "personalizationSettings", permission: "platform:settings:read", group: "administration", order: 100 },
    { id: "smtp", label: "messageChannels", permission: "platform:settings:read", group: "administration", order: 110 },
    { id: "sms", label: "smsSettings", permission: "platform:settings:read", group: "administration", order: 120 },
    { id: "profile-fields", label: "profileFields", permission: "platform:settings:read", group: "administration", order: 130 },
    { id: "agreements", label: "agreements", permission: "platform:settings:read", group: "administration", order: 140 },
    { id: "watermark", label: "watermark", permission: "platform:settings:read", group: "administration", order: 150 },
    { id: "maintenance", label: "maintenance", permission: "platform:settings:read", group: "administration", order: 160 },
    { id: "security", label: "authenticationSecurity", permission: "platform:settings:read", group: "administration", order: 170 },
    { id: "passkey", label: "passkeySettings", permission: "platform:settings:read", group: "administration", order: 180 },
    { id: "file-retention", label: "fileRetention", permission: "platform:settings:read", group: "administration", order: 190 },
  ],
} as const satisfies FrontendModuleManifest;
