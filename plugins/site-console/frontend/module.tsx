import type { FrontendModule, FrontendPageContext } from "@/platform/modules/types";
import { lazy } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";
import { routeTabsForGroup } from "@/platform/modules/route-tabs";
import { siteConsoleManifest } from "./manifest";
const BrandingPage = lazy(() => import("./branding-page").then(({ BrandingPage }) => ({ default: BrandingPage })));
const WatermarkPage = lazy(() => import("./watermark-page").then(({ WatermarkPage }) => ({ default: WatermarkPage })));
const AgreementsPage = lazy(() => import("./agreements-page").then(({ AgreementsPage }) => ({ default: AgreementsPage })));
const MaintenancePage = lazy(() => import("./maintenance-page").then(({ MaintenancePage }) => ({ default: MaintenancePage })));
function SiteTabs({ hasRouteAccess, hasRouteOperation }: FrontendPageContext) {
  return <RouteTabs preserveContent tabs={routeTabsForGroup(siteConsoleManifest, "personalization", {
    branding: hasRouteAccess("branding") ? <BrandingPage canWrite={hasRouteOperation("branding", "update")} /> : undefined,
    watermark: hasRouteAccess("watermark") ? <WatermarkPage canWrite={hasRouteOperation("watermark", "update")} /> : undefined,
    agreements: hasRouteAccess("agreements") ? <AgreementsPage canWrite={hasRouteOperation("agreements", "update")} /> : undefined,
    maintenance: hasRouteAccess("maintenance") ? <MaintenancePage canWrite={hasRouteOperation("maintenance", "update")} /> : undefined,
  })} />;
}
export const siteConsoleFrontendModule = {
  ...siteConsoleManifest,
  pages: { branding: context => <SiteTabs {...context} />, watermark: context => <SiteTabs {...context} />, agreements: context => <SiteTabs {...context} />, maintenance: context => <SiteTabs {...context} /> },
} satisfies FrontendModule;
