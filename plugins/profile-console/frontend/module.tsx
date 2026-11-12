import type { FrontendModule } from "@/platform/modules/types";
import { lazy } from "react";
import { profileConsoleManifest } from "./manifest";
const ProfileFieldsPage=lazy(()=>import("./profile-fields-page").then(({ProfileFieldsPage})=>({default:ProfileFieldsPage})));
export const profileConsoleFrontendModule={...profileConsoleManifest,pages:{"profile-fields":({hasRouteOperation})=><ProfileFieldsPage canWrite={hasRouteOperation("profile-fields","update")} canReadDictionaries={hasRouteOperation("profile-fields","readDictionaries")}/>}} satisfies FrontendModule;
