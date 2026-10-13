import type { FrontendModuleManifest } from "../../platform/modules/types.ts";

/** Delivery operations are required because identity uses the sender ports. */
export const communicationManifest = {
  id: "communication",
  version: "1.0.0",
  required: true,
  dependencies: ["identity", "platform", "settings"],
  routes: [
    { id: "mail-deliveries", label: "deliveryRecords", tabLabel: "mailDeliveries", permission: "platform:mail-deliveries:access", accessScope: "global", group: "communication", order: 40 },
    { id: "sms-deliveries", label: "smsDeliveries", permission: "platform:sms-deliveries:access", accessScope: "global", group: "communication", order: 50 },
  ],
  tabGroups: [
    { id: "delivery-records", routes: ["mail-deliveries", "sms-deliveries"], anchor: "mail-deliveries", collapse: "when-all-visible" },
  ],
} as const satisfies FrontendModuleManifest;
