import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";

export const eventsManifest = {
  id: "event-console",
  version: "1.0.0",
  required: false,
  dependencies: ["platform", "events"],
  routes: [
    { id: "event-outbox", label: "eventOutbox", permission: "platform:event-outbox:access", accessScope: "global", operations: { replay: "platform:event-outbox:replay" }, group: "operations", order: 40 },
  ],
} as const satisfies FrontendModuleManifest;
