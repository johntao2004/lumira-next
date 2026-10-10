import type { FrontendModuleManifest } from "@/platform/modules/types";

export const feedbackManifest = {
  id: "feedback",
  version: "1.0.6",
  required: false,
  dependencies: ["platform", "files", "dictionaries", "notifications"],
  routes: [
    { id: "feedback", label: "feedback", permission: "platform:feedback:read", group: "operations", order: 70 },
  ],
} as const satisfies FrontendModuleManifest;
