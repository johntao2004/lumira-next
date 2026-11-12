import type { FrontendModuleManifest } from "@/platform/modules/types";

export const wechatPayManifest = {
  id: "wechat-pay",
  version: "1.1.0",
  required: false,
  dependencies: ["platform", "payment-core"],
  routes: [
    {
      id: "wechat-pay-orders",
      label: "wechatPayOrders",
      permission: "platform:wechat-pay:orders-access",
      accessScope: "global",
      operations: {
        read: "platform:wechat-pay:orders-read",
        create: "platform:wechat-pay:orders-create",
        manage: "platform:wechat-pay:orders-manage",
        refund: "platform:wechat-pay:orders-refund",
      },
      global: true,
      group: "payments",
      order: 20,
    },
    {
      id: "wechat-pay-settings",
      label: "wechatPay",
      permission: "platform:wechat-pay:settings-access",
      accessScope: "global",
      operations: {
        read: "platform:wechat-pay:settings-read",
        update: "platform:wechat-pay:settings-update",
      },
      global: true,
      group: "payments",
      order: 40,
    },
  ],
} as const satisfies FrontendModuleManifest;
