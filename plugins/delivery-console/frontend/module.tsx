import type { FrontendModule } from "@/platform/modules/types";
import { communicationManifest } from "./manifest";
import { lazy } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";
import { routeTabsForGroup } from "@/platform/modules/route-tabs";

const MailDeliveryPage = lazy(() => import("./mail-delivery-page").then(({ MailDeliveryPage }) => ({ default: MailDeliveryPage })));
const SmsDeliveryPage = lazy(() => import("./sms-delivery-page").then(({ SmsDeliveryPage }) => ({ default: SmsDeliveryPage })));

function DeliveryTabs({ hasRouteAccess }: { hasRouteAccess: (routeId: string) => boolean }) {
  return <RouteTabs tabs={routeTabsForGroup(communicationManifest, "delivery-records", {
    "mail-deliveries": hasRouteAccess("mail-deliveries") ? <MailDeliveryPage /> : undefined,
    "sms-deliveries": hasRouteAccess("sms-deliveries") ? <SmsDeliveryPage /> : undefined,
  })} />;
}

export const communicationFrontendModule = {
  ...communicationManifest,
  pages: {
    "mail-deliveries": ({ hasRouteAccess }) => <DeliveryTabs hasRouteAccess={hasRouteAccess} />,
    "sms-deliveries": ({ hasRouteAccess }) => <DeliveryTabs hasRouteAccess={hasRouteAccess} />,
  },
} satisfies FrontendModule;
