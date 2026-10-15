import type { FrontendModuleManifest } from "@/platform/modules/types";

export const feedbackManifest = {
  id: "feedback",
  version: "1.0.14",
  required: false,
  dependencies: ["platform", "identity", "files", "dictionaries", "notifications"],
  routes: [
    {
      id: "feedback",
      label: "feedback",
      permission: "platform:feedback:access",
      operations: {
        read: "platform:feedback:read",
        create: "platform:feedback:create",
        update: "platform:feedback:update",
        reply: "platform:feedback:reply",
      },
      accessScope: "scoped",
      scopedOperations: ["read", "create", "update", "reply"],
      group: "communication",
      order: 60,
    },
  ],
} as const satisfies FrontendModuleManifest;
