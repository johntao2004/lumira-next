/**
 * 按依赖顺序装配静态宿主模块并初始化页面存储，公共运行时不得反向导入此入口以免形成业务依赖循环。
 * Assemble static host modules in dependency order and initialize the page store without reverse imports from the shared runtime that would create feature dependency cycles.
 */
import { orderModuleGraph } from "@/platform/modules/graph";
import { initializeHostPages } from "@/platform/modules/page-store";
import type { FrontendModule } from "@/platform/modules/types";
import { identityFrontendModule } from "@/features/account/module";
import { platformFrontendModule } from "@/features/platform/module";
import { filesFrontendModule } from "@/features/files/module";
import { settingsFrontendModule } from "@/features/settings/module";

const candidates: readonly FrontendModule[] = [
  identityFrontendModule,
  platformFrontendModule,
  filesFrontendModule,
  settingsFrontendModule,
];

export const frontendModules = orderModuleGraph(candidates);
initializeHostPages(frontendModules);
export {
  renderFrontendPage,
  frontendRouteOwner,
  registerPluginPages,
  frontendRouteRegistrationKey,
  frontendWorkspaceActions,
  subscribeFrontendWorkspaceActions,
} from "@/platform/modules/page-store";
