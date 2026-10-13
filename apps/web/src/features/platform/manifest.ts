import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const platformManifest = {
  id: "platform",
  version: "1.0.0",
  required: true,
  dependencies: ["identity"],
  routes: [
    { id: "home", label: "home", permission: null, group: "overview", order: 10 },
    { id: "modules", label: "modules", permission: "platform:modules:access", accessScope: "global", operations: { install: "platform:modules:install", update: "platform:modules:update", uninstall: "platform:modules:uninstall" }, group: "administration", order: 10 },
    { id: "ai-console", label: "aiConsole", permission: "platform:ai-console:access", accessScope: "scoped", operations: { readUsers: "platform:ai-console:read-users", createUser: "platform:ai-console:create-user", updateUser: "platform:ai-console:update-user", deleteUser: "platform:ai-console:delete-user" }, scopedOperations: ["readUsers", "updateUser", "deleteUser"], group: "ai-management", order: 10 },
    { id: "ai-models", label: "aiModels", permission: "platform:ai-models:access", accessScope: "global", operations: { create: "platform:ai-models:create", update: "platform:ai-models:update", delete: "platform:ai-models:delete", test: "platform:ai-models:test" }, group: "ai-management", order: 20 },
  ],
} as const satisfies FrontendModuleManifest;
