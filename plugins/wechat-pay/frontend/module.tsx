import type { FrontendModule } from "@/platform/modules/types";
import { PaymentChannelWorkbench, type PaymentSettingField } from "../../sdk/frontend/payment-channel-workbench";
import { paymentOrderPermissions, paymentSettingsPermissions } from "../../sdk/frontend/payment-channel-permissions";
import { wechatPayManifest } from "./manifest";
import { wechatPayMessages } from "./messages";

const fields: readonly PaymentSettingField[] = [
  { key: "appId", labelKey: "appId", helpKey: "appIdHelp", kind: "text", required: true, maxLength: 64 },
  { key: "merchantId", labelKey: "merchantId", helpKey: "merchantIdHelp", kind: "text", required: true, maxLength: 32 },
  { key: "merchantSerialNumber", labelKey: "merchantSerialNumber", helpKey: "merchantSerialNumberHelp", kind: "text", required: true, maxLength: 64 },
  { key: "wechatPayPublicKeyId", labelKey: "wechatPayPublicKeyId", helpKey: "wechatPayPublicKeyIdHelp", kind: "text", required: true, maxLength: 128 },
  { key: "notifyUrl", labelKey: "notifyUrl", helpKey: "notifyUrlHelp", kind: "text", required: true, maxLength: 2048 },
  { key: "merchantPrivateKeyPem", labelKey: "merchantPrivateKeyPem", helpKey: "merchantPrivateKeyPemHelp", kind: "secret", required: true, maxLength: 8192 },
  { key: "wechatPayPublicKeyPem", labelKey: "wechatPayPublicKeyPem", helpKey: "wechatPayPublicKeyPemHelp", kind: "secret", required: true, maxLength: 8192 },
  { key: "apiV3Key", labelKey: "apiV3Key", helpKey: "apiV3KeyHelp", kind: "secret", required: true, maxLength: 32 },
];

export const wechatPayFrontendModule = {
  ...wechatPayManifest,
  pages: {
    "wechat-pay-orders": ({ hasOperation, readOnlyPreview }) => (
      <PaymentChannelWorkbench
        providerId="wechat-pay"
        section="orders"
        translations={wechatPayMessages}
        {...paymentOrderPermissions(hasOperation)}
        readOnlyPreview={readOnlyPreview}
      />
    ),
    "wechat-pay-settings": ({ hasOperation, readOnlyPreview }) => (
      <PaymentChannelWorkbench
        providerId="wechat-pay"
        section="settings"
        fields={fields}
        translations={wechatPayMessages}
        {...paymentSettingsPermissions(hasOperation)}
        readOnlyPreview={readOnlyPreview}
      />
    ),
  },
} satisfies FrontendModule;
