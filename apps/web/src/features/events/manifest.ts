import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const eventsManifest = {
  id: "events",
  version: "1.0.0",
  required: true,
  dependencies: ["platform"],
  routes: [
    { id: "event-outbox", label: "eventOutbox", permission: "platform:audit:read", group: "operations", order: 60 },
  ],
} as const satisfies FrontendModuleManifest;
