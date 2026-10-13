import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const directoryManifest = {
  id: "directory",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "files"],
  routes: [
    { id: "users", label: "userList", permission: "platform:users:access", accessScope: "scoped", operations: { create: "platform:users:create", update: "platform:users:update", delete: "platform:users:delete", resetPassword: "platform:users:reset-password", assignRoles: "platform:users:assign-roles", assignOrganizations: "platform:users:assign-organizations", readRoles: "platform:users:read-roles", export: "platform:users:export" }, scopedOperations: ["update", "delete", "resetPassword", "assignRoles", "assignOrganizations", "export"], group: "user-management", order: 10 },
    { id: "online-users", label: "onlineUsers", permission: "platform:online-users:access", accessScope: "global", operations: { terminateSession: "platform:online-users:terminate-session" }, global: true, group: "user-management", order: 20 },
    { id: "roles", label: "roles", permission: "platform:roles:access", accessScope: "global", operations: { create: "platform:roles:create", update: "platform:roles:update", delete: "platform:roles:delete", preview: "platform:roles:preview", readAuthenticationPolicy: "platform:roles:read-authentication-policy", updateAuthenticationPolicy: "platform:roles:update-authentication-policy" }, group: "user-management", order: 30 },
    { id: "organizations", label: "organizations", permission: "platform:organizations:access", accessScope: "scoped", operations: { create: "platform:organizations:create", update: "platform:organizations:update", delete: "platform:organizations:delete" }, group: "user-management", order: 40 },
  ],
  tabGroups: [
    { id: "user-activity", routes: ["users", "online-users"], anchor: "users", collapse: "when-all-visible" },
  ],
} as const satisfies FrontendModuleManifest;
