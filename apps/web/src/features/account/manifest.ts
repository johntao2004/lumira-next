import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

/** Identity owns account drawers and auth capabilities, even without a dashboard page. */
export const identityManifest = {
  id: "identity",
  version: "1.0.0",
  required: true,
  dependencies: [],
  routes: [
    { id: "account-profile", label: "profile", permission: null, hidden: true, group: "user-management", order: 24 },
    { id: "account-security", label: "accountSecurity", permission: null, hidden: true, group: "user-management", order: 25 },
    { id: "account-role-preview", label: "rolePreview", permission: null, hidden: true, group: "user-management", order: 26 },
    { id: "account-wechat", label: "wechatBinding", permission: null, hidden: true, group: "user-management", order: 27 },
    { id: "account-password", label: "changePassword", permission: null, hidden: true, group: "user-management", order: 28 },
    { id: "account-sessions", label: "mySessions", permission: null, hidden: true, group: "user-management", order: 29 },
  ],
} as const satisfies FrontendModuleManifest;

export const accountSettingsRouteIds = [
  "account-profile",
  "account-security",
  "account-role-preview",
  "account-password",
  "account-sessions",
  "account-wechat",
] as const;

export type AccountSettingsRouteId = (typeof accountSettingsRouteIds)[number];

export function isAccountSettingsRoute(routeId: string): routeId is AccountSettingsRouteId {
  return accountSettingsRouteIds.includes(routeId as AccountSettingsRouteId);
}
