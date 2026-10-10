import type { FrontendModule } from "../../platform/modules/types";
import { communicationManifest } from "./manifest";
import { lazy } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";
import { routeTabsForGroup } from "@/platform/modules/route-tabs";

const MailDeliveryPage = lazy(() => import("../mail/delivery-page").then(({ MailDeliveryPage }) => ({ default: MailDeliveryPage })));
const SmsDeliveryPage = lazy(() => import("../sms/delivery-page").then(({ SmsDeliveryPage }) => ({ default: SmsDeliveryPage })));

function DeliveryTabs() {
  return <RouteTabs tabs={routeTabsForGroup(communicationManifest, "delivery-records", {
    "mail-deliveries": <MailDeliveryPage />,
    "sms-deliveries": <SmsDeliveryPage />,
  })} />;
}

export const communicationFrontendModule = {
  ...communicationManifest,
  pages: {
    "mail-deliveries": () => <DeliveryTabs />,
    "sms-deliveries": () => <DeliveryTabs />,
  },
} satisfies FrontendModule;
