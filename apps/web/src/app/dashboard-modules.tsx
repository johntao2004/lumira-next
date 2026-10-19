/**
 * 按依赖顺序装配静态宿主模块并初始化页面存储，公共运行时不得反向导入此入口以免形成业务依赖循环。
 * Assemble static host modules in dependency order and initialize the page store without reverse imports from the shared runtime that would create feature dependency cycles.
 */
import { orderModuleGraph } from "@/platform/modules/graph";
import { initializeHostPages } from "@/platform/modules/page-store";
import type { FrontendModule } from "@/platform/modules/types";
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
initializeHostPages(frontendModules);
export { renderFrontendPage, frontendRouteOwner, registerPluginPages } from "@/platform/modules/page-store";
