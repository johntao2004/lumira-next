import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const settingsManifest = {
  id: "settings",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "dictionaries"],
  routes: [
    { id: "branding", label: "personalizationSettings", tabLabel: "branding", permission: "platform:settings:read", group: "administration", order: 100 },
    { id: "smtp", label: "messageChannels", tabLabel: "smtpSettings", permission: "platform:settings:read", group: "administration", order: 110 },
    { id: "sms", label: "messageChannels", tabLabel: "smsSettings", permission: "platform:settings:read", group: "administration", order: 120 },
    { id: "profile-fields", label: "profileFields", permission: "platform:settings:read", group: "administration", order: 130 },
    { id: "agreements", label: "agreements", permission: "platform:settings:read", group: "administration", order: 140 },
    { id: "watermark", label: "watermark", permission: "platform:settings:read", group: "administration", order: 150 },
    { id: "maintenance", label: "maintenance", permission: "platform:settings:read", group: "administration", order: 160 },
    { id: "security", label: "authenticationSecurity", tabLabel: "securitySettings", permission: "platform:settings:read", group: "administration", order: 170 },
    { id: "authentication", label: "authenticationMethods", permission: "platform:settings:read", group: "administration", order: 175 },
    { id: "passkey", label: "passkeySettings", permission: "platform:settings:read", group: "administration", order: 180 },
    { id: "wechat", label: "wechatSettings", permission: "platform:settings:read", group: "administration", order: 185 },
    { id: "captcha", label: "captchaSettingsTitle", permission: "platform:settings:read", group: "administration", order: 190 },
    { id: "registration", label: "registrationSettingsTitle", permission: "platform:settings:read", group: "administration", order: 191 },
    { id: "file-retention", label: "fileRetention", permission: "platform:settings:read", hidden: true, group: "administration", order: 195 },
  ],
  tabGroups: [
    { id: "personalization", routes: ["branding", "watermark", "agreements", "maintenance"], anchor: "branding", collapse: "when-all-visible" },
    { id: "message-channels", routes: ["smtp", "sms"], anchor: "smtp", collapse: "when-all-visible" },
    { id: "authentication-security", routes: ["security", "authentication", "passkey", "wechat", "captcha", "registration"], anchor: "security", collapse: "when-anchor-visible" },
  ],
} as const satisfies FrontendModuleManifest;
