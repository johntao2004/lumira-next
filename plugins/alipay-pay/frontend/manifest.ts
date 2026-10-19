import type { FrontendModuleManifest } from "@/platform/modules/types";

export const alipayPayManifest = {
  id: "alipay-pay",
  version: "1.0.7",
  required: false,
  dependencies: ["platform", "payment-core"],
  routes: [
    {
      id: "alipay-pay-orders",
      label: "alipayPayOrders",
      permission: "platform:alipay-pay:orders-access",
      accessScope: "global",
      operations: {
        read: "platform:alipay-pay:orders-read",
        create: "platform:alipay-pay:orders-create",
        manage: "platform:alipay-pay:orders-manage",
        refund: "platform:alipay-pay:orders-refund",
      },
      global: true,
      group: "payments",
      order: 30,
    },
    {
      id: "alipay-pay-settings",
      label: "alipayPay",
      permission: "platform:alipay-pay:settings-access",
      accessScope: "global",
      operations: {
        read: "platform:alipay-pay:settings-read",
        update: "platform:alipay-pay:settings-update",
      },
      global: true,
      group: "payments",
      order: 50,
    },
  ],
} as const satisfies FrontendModuleManifest;
