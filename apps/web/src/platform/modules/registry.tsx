import { orderModuleGraph } from "./graph";
import { createFrontendPageRegistry } from "./contracts";
import type { FrontendModule } from "./types";
import { identityFrontendModule } from "@/features/account/module";
import { platformFrontendModule } from "@/features/platform/module";
import { directoryFrontendModule } from "@/features/directory/module";
import { dictionariesFrontendModule } from "@/features/dictionaries/module";
import { filesFrontendModule } from "@/features/files/module";
import { settingsFrontendModule } from "@/features/settings/module";
import { eventsFrontendModule } from "@/features/events/module";
import { navigationFrontendModule } from "@/features/navigation/module";
import { notificationsFrontendModule } from "@/features/notifications/module";
import { monitoringFrontendModule } from "@/features/monitoring/module";
import { auditFrontendModule } from "@/features/audit/module";
import { communicationFrontendModule } from "@/features/communication/module";

const candidates: readonly FrontendModule[] = [
  identityFrontendModule,
  platformFrontendModule,
  directoryFrontendModule,
  dictionariesFrontendModule,
  filesFrontendModule,
  settingsFrontendModule,
  eventsFrontendModule,
  navigationFrontendModule,
  notificationsFrontendModule,
  monitoringFrontendModule,
  auditFrontendModule,
  communicationFrontendModule,
];

export const frontendModules = orderModuleGraph(candidates);
const pageRegistry = createFrontendPageRegistry(frontendModules);

export const renderFrontendPage = pageRegistry.render;
export const frontendRouteOwner = pageRegistry.owner;
export const registerPluginPages = pageRegistry.registerPlugin;
