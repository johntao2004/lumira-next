import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const directoryManifest = {
  id: "directory",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "files"],
  routes: [
    { id: "users", label: "userList", permission: "platform:users:read", group: "user-management", order: 10 },
    { id: "online-users", label: "onlineUsers", permission: "platform:users:read", global: true, group: "user-management", order: 20 },
    { id: "roles", label: "roles", permission: "platform:roles:read", group: "user-management", order: 30 },
    { id: "organizations", label: "organizations", permission: "platform:organizations:read", group: "user-management", order: 40 },
  ],
  tabGroups: [
    { id: "user-activity", routes: ["users", "online-users"], anchor: "users", collapse: "when-all-visible" },
  ],
} as const satisfies FrontendModuleManifest;
