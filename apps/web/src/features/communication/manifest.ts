import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

/** Delivery operations are required because identity uses the sender ports. */
export const communicationManifest = {
  id: "communication",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "settings"],
  routes: [
    { id: "mail-deliveries", label: "deliveryRecords", permission: "platform:audit:read", group: "operations", order: 40 },
    { id: "sms-deliveries", label: "smsDeliveries", permission: "platform:audit:read", group: "operations", order: 50 },
  ],
} as const satisfies FrontendModuleManifest;
