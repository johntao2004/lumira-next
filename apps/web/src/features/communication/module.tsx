import type { FrontendModule } from "../../platform/modules/types";
import { communicationManifest } from "./manifest";
import { lazy } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";

const MailDeliveryPage = lazy(() => import("../mail/delivery-page").then(({ MailDeliveryPage }) => ({ default: MailDeliveryPage })));
const SmsDeliveryPage = lazy(() => import("../sms/delivery-page").then(({ SmsDeliveryPage }) => ({ default: SmsDeliveryPage })));

function DeliveryTabs() {
  return <RouteTabs tabs={[
    { key: "mail-deliveries", path: "/dashboard/mail-deliveries", label: "mailDeliveries", children: <MailDeliveryPage /> },
    { key: "sms-deliveries", path: "/dashboard/sms-deliveries", label: "smsDeliveries", children: <SmsDeliveryPage /> },
  ]} />;
}

export const communicationFrontendModule = {
  ...communicationManifest,
  pages: {
    "mail-deliveries": () => <DeliveryTabs />,
    "sms-deliveries": () => <DeliveryTabs />,
  },
} satisfies FrontendModule;
