import type { FrontendModuleManifest } from "@/platform/modules/types";

export const paymentCoreManifest = {
  id: "payment-core",
  version: "1.0.6",
  required: false,
  dependencies: ["platform"],
  routes: [
    {
      id: "payment-core-orders",
      label: "paymentOrders",
      permission: "platform:payment-core:orders-access",
      accessScope: "global",
      operations: {
        read: "platform:payment-core:orders-read",
        create: "platform:payment-core:orders-create",
        manage: "platform:payment-core:orders-manage",
        refund: "platform:payment-core:orders-refund",
      },
      global: true,
      group: "payments",
      order: 10,
    },
  ],
} as const satisfies FrontendModuleManifest;
