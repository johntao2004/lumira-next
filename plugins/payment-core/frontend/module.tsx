import type { FrontendModule } from "@/platform/modules/types";
import { PaymentChannelWorkbench } from "../../sdk/frontend/payment-channel-workbench";
import { paymentCoreManifest } from "./manifest";
import { paymentCoreMessages } from "./messages";

function permissions(hasOperation: (actionId: string) => boolean) {
  return {
    canRead: hasOperation("read"),
    canUpdateSettings: false,
    canCreateOrders: hasOperation("create"),
    canManageOrders: hasOperation("manage"),
    canRefund: hasOperation("refund"),
  };
}

export const paymentCoreFrontendModule = {
  ...paymentCoreManifest,
  pages: {
    "payment-core-orders": ({ hasOperation, readOnlyPreview }) => (
      <PaymentChannelWorkbench
        section="orders"
        translations={paymentCoreMessages}
        subjectMaxLength={127}
        {...permissions(hasOperation)}
        readOnlyPreview={readOnlyPreview}
      />
    ),
  },
} satisfies FrontendModule;
