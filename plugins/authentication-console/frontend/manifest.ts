import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";
export const authenticationConsoleManifest = {
  id: "authentication-console",
  version: "1.0.0",
  required: false,
  dependencies: ["identity","settings","directory"],
  routes: [
    { id: "security", label: "authenticationSecurity", tabLabel: "securitySettings", permission: "platform:security:access", accessScope: "global", operations: { update: "platform:security:update" }, group: "user-management", order: 60 },
    { id: "authentication", label: "authenticationMethods", permission: "platform:authentication:access", accessScope: "global", operations: { update: "platform:authentication:update" }, group: "user-management", order: 70 },
    { id: "passkey", label: "passkeySettings", permission: "platform:passkey:access", accessScope: "global", operations: { update: "platform:passkey:update" }, group: "user-management", order: 80 },
    { id: "wechat", label: "wechatSettings", permission: "platform:wechat:access", accessScope: "global", operations: { update: "platform:wechat:update" }, group: "user-management", order: 90 },
    { id: "captcha", label: "captchaSettingsTitle", permission: "platform:captcha:access", accessScope: "global", operations: { update: "platform:captcha:update" }, group: "user-management", order: 100 },
    { id: "registration", label: "registrationSettingsTitle", permission: "platform:registration:access", accessScope: "global", operations: { update: "platform:registration:update", readRoles: "platform:registration:read-roles", assignDefaultRole: "platform:registration:assign-default-role" }, group: "user-management", order: 110 },
  ],
  tabGroups: [
    { id: "authentication-security", routes: ["security", "authentication", "passkey", "wechat", "captcha", "registration"], anchor: "security", collapse: "when-anchor-visible" },
  ],
} as const satisfies FrontendModuleManifest;
