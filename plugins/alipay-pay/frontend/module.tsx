import type { FrontendModule } from "@/platform/modules/types";
import { PaymentChannelWorkbench, type PaymentSettingField } from "../../sdk/frontend/payment-channel-workbench";
import { paymentOrderPermissions, paymentSettingsPermissions } from "../../sdk/frontend/payment-channel-permissions";
import { alipayPayManifest } from "./manifest";
import { alipayPayMessages } from "./messages";

const fields: readonly PaymentSettingField[] = [
  { key: "environment", labelKey: "environment", helpKey: "environmentHelp", kind: "text", required: true, maxLength: 10 },
  { key: "notifyUrl", labelKey: "notifyUrl", helpKey: "notifyUrlHelp", kind: "text", required: true, maxLength: 2048 },
  { key: "appId", labelKey: "appId", helpKey: "appIdHelp", kind: "text", required: true, maxLength: 32 },
  { key: "sellerId", labelKey: "sellerId", helpKey: "sellerIdHelp", kind: "text", required: true, maxLength: 16 },
  { key: "appPrivateKey", labelKey: "appPrivateKey", helpKey: "keyHelp", kind: "secret", required: true, maxLength: 8192 },
  { key: "alipayPublicKey", labelKey: "alipayPublicKey", helpKey: "keyHelp", kind: "secret", required: true, maxLength: 8192 },
];

export const alipayPayFrontendModule = {
  ...alipayPayManifest,
  pages: {
    "alipay-pay-orders": ({ hasOperation, readOnlyPreview }) => (
      <PaymentChannelWorkbench
        providerId="alipay-pay"
        section="orders"
        translations={alipayPayMessages}
        {...paymentOrderPermissions(hasOperation)}
        readOnlyPreview={readOnlyPreview}
      />
    ),
    "alipay-pay-settings": ({ hasOperation, readOnlyPreview }) => (
      <PaymentChannelWorkbench
        providerId="alipay-pay"
        section="settings"
        fields={fields}
        translations={alipayPayMessages}
        {...paymentSettingsPermissions(hasOperation)}
        readOnlyPreview={readOnlyPreview}
      />
    ),
  },
} satisfies FrontendModule;
