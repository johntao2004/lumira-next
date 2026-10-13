import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

export const eventsManifest = {
  id: "events",
  version: "1.0.0",
  required: true,
  dependencies: ["platform"],
  routes: [
    { id: "event-outbox", label: "eventOutbox", permission: "platform:event-outbox:access", accessScope: "global", operations: { replay: "platform:event-outbox:replay" }, group: "operations", order: 40 },
  ],
} as const satisfies FrontendModuleManifest;
