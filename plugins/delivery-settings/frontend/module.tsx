import type { FrontendModule,FrontendPageContext } from "@/platform/modules/types";
import { lazy } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";
import { routeTabsForGroup } from "@/platform/modules/route-tabs";
import { deliverySettingsManifest } from "./manifest";
const SmtpPage=lazy(()=>import("./smtp-page").then(({SmtpPage})=>({default:SmtpPage})));
const SmsPage=lazy(()=>import("./sms-page").then(({SmsPage})=>({default:SmsPage})));
function MessageChannelTabs({hasRouteAccess,hasRouteOperation}: FrontendPageContext) {return <RouteTabs preserveContent tabs={routeTabsForGroup(deliverySettingsManifest,"message-channels",{smtp:hasRouteAccess("smtp")?<SmtpPage canWrite={hasRouteOperation("smtp","update")}/>:undefined,sms:hasRouteAccess("sms")?<SmsPage canWrite={hasRouteOperation("sms","update")}/>:undefined})}/>;}
export const deliverySettingsFrontendModule={...deliverySettingsManifest,pages:{
  smtp:context=>context.embedded ? <SmtpPage canWrite={context.hasRouteOperation("smtp","update")} onSaved={context.embedded.onSaved}/> : <MessageChannelTabs {...context}/>,
  sms:context=>context.embedded ? <SmsPage canWrite={context.hasRouteOperation("sms","update")} onSaved={context.embedded.onSaved}/> : <MessageChannelTabs {...context}/>,
}} satisfies FrontendModule;
