import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const settingsManifest = {
  id: "settings",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "dictionaries"],
  routes: [
    { id: "branding", label: "siteSettings", tabLabel: "branding", permission: "platform:branding:access", accessScope: "global", operations: { update: "platform:branding:update" }, group: "administration", order: 20 },
    { id: "smtp", label: "messageChannels", tabLabel: "smtpSettings", permission: "platform:smtp:access", accessScope: "global", operations: { update: "platform:smtp:update", test: "platform:smtp:test" }, group: "communication", order: 20 },
    { id: "sms", label: "messageChannels", tabLabel: "smsSettings", permission: "platform:sms:access", accessScope: "global", operations: { update: "platform:sms:update", test: "platform:sms:test" }, group: "communication", order: 30 },
    { id: "profile-fields", label: "profileFields", permission: "platform:profile-fields:access", accessScope: "global", operations: { update: "platform:profile-fields:update", readDictionaries: "platform:profile-fields:read-dictionaries" }, group: "user-management", order: 50 },
    { id: "agreements", label: "agreements", permission: "platform:agreements:access", accessScope: "global", operations: { update: "platform:agreements:update" }, group: "administration", order: 40 },
    { id: "watermark", label: "watermark", permission: "platform:watermark:access", accessScope: "global", operations: { update: "platform:watermark:update" }, group: "administration", order: 30 },
    { id: "maintenance", label: "maintenance", permission: "platform:maintenance:access", accessScope: "global", operations: { update: "platform:maintenance:update" }, group: "administration", order: 50 },
    { id: "security", label: "authenticationSecurity", tabLabel: "securitySettings", permission: "platform:security:access", accessScope: "global", operations: { update: "platform:security:update" }, group: "user-management", order: 60 },
    { id: "authentication", label: "authenticationMethods", permission: "platform:authentication:access", accessScope: "global", operations: { update: "platform:authentication:update" }, group: "user-management", order: 70 },
    { id: "passkey", label: "passkeySettings", permission: "platform:passkey:access", accessScope: "global", operations: { update: "platform:passkey:update" }, group: "user-management", order: 80 },
    { id: "wechat", label: "wechatSettings", permission: "platform:wechat:access", accessScope: "global", operations: { update: "platform:wechat:update" }, group: "user-management", order: 90 },
    { id: "captcha", label: "captchaSettingsTitle", permission: "platform:captcha:access", accessScope: "global", operations: { update: "platform:captcha:update" }, group: "user-management", order: 100 },
    { id: "registration", label: "registrationSettingsTitle", permission: "platform:registration:access", accessScope: "global", operations: { update: "platform:registration:update", readRoles: "platform:registration:read-roles", assignDefaultRole: "platform:registration:assign-default-role" }, group: "user-management", order: 110 },
    { id: "file-retention", label: "fileRetention", permission: "platform:file-retention:access", accessScope: "global", operations: { update: "platform:file-retention:update" }, hidden: true, group: "administration", order: 195 },
  ],
  tabGroups: [
    { id: "personalization", routes: ["branding", "watermark", "agreements", "maintenance"], anchor: "branding", collapse: "when-all-visible" },
    { id: "message-channels", routes: ["smtp", "sms"], anchor: "smtp", collapse: "when-all-visible" },
    { id: "authentication-security", routes: ["security", "authentication", "passkey", "wechat", "captcha", "registration"], anchor: "security", collapse: "when-anchor-visible" },
  ],
} as const satisfies FrontendModuleManifest;
