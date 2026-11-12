import { orderModuleGraph } from "./graph";
import { createFrontendPageRegistry } from "./contracts";
import type { FrontendModule } from "./types";
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
const pageRegistry = createFrontendPageRegistry(frontendModules);

export const renderFrontendPage = pageRegistry.render;
export const frontendRouteOwner = pageRegistry.owner;
export const frontendRouteRegistrationKey = pageRegistry.registrationKey;
export const registerPluginPages = pageRegistry.registerPlugin;
export const frontendWorkspaceActions = pageRegistry.workspaceActions;
export const subscribeFrontendWorkspaceActions = pageRegistry.subscribeWorkspaceActions;
