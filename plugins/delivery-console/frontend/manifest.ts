import type { FrontendModuleManifest } from "../../../apps/web/src/platform/modules/types.ts";

/** Optional management pages; identity sender ports remain in communication. */
export const communicationManifest = {
  id: "delivery-console",
  version: "1.0.0",
  required: false,
  dependencies: ["platform", "communication"],
  routes: [
    { id: "mail-deliveries", label: "deliveryRecords", tabLabel: "mailDeliveries", permission: "platform:mail-deliveries:access", accessScope: "global", group: "communication", order: 40 },
    { id: "sms-deliveries", label: "smsDeliveries", permission: "platform:sms-deliveries:access", accessScope: "global", group: "communication", order: 50 },
  ],
  tabGroups: [
    { id: "delivery-records", routes: ["mail-deliveries", "sms-deliveries"], anchor: "mail-deliveries", collapse: "when-all-visible" },
  ],
} as const satisfies FrontendModuleManifest;
