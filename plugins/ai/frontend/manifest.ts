import type { FrontendModuleManifest } from "@/platform/modules/types";

export const aiManifest = {
  id: "ai",
  version: "1.0.0",
  required: false,
  dependencies: ["identity", "platform", "directory", "files", "settings"],
  routes: [
    { id: "ai-console", label: "aiConsole", permission: "platform:ai-console:access", accessScope: "scoped", operations: { readUsers: "platform:ai-console:read-users", createUser: "platform:ai-console:create-user", updateUser: "platform:ai-console:update-user", deleteUser: "platform:ai-console:delete-user" }, scopedOperations: ["readUsers", "updateUser", "deleteUser"], group: "ai-management", order: 10 },
    { id: "ai-models", label: "aiModels", permission: "platform:ai-models:access", accessScope: "global", operations: { create: "platform:ai-models:create", update: "platform:ai-models:update", delete: "platform:ai-models:delete", test: "platform:ai-models:test" }, group: "ai-management", order: 20 },
  ],
} as const satisfies FrontendModuleManifest;
