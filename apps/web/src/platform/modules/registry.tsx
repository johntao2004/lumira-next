import type { FrontendModule, FrontendPageContext } from "./types";
import type { ReactNode } from "react";
import { orderModuleGraph } from "./graph";
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
import { feedbackFrontendModule } from "@/features/feedback/module";
import { sensitiveWordsFrontendModule } from "@/features/sensitive/module";
import { localizationFrontendModule } from "@/features/localization/module";
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
  feedbackFrontendModule,
  sensitiveWordsFrontendModule,
  localizationFrontendModule,
  communicationFrontendModule,
];

export const frontendModules = orderModuleGraph(candidates);

const pageRenderers = new Map<string, (context: FrontendPageContext) => ReactNode>();
const pageOwners = new Map<string, string>();
for (const module of frontendModules) {
  for (const route of module.routes) {
    if (pageOwners.has(route.id)) throw new Error(`Duplicate frontend route: ${route.id}`);
    pageOwners.set(route.id, module.id);
    const renderer = module.pages[route.id];
    if (renderer) pageRenderers.set(route.id, renderer);
  }
  for (const [pageId, renderer] of Object.entries(module.pages)) {
    if (!module.routes.some((route) => route.id === pageId)) throw new Error(`Uncatalogued frontend page: ${module.id}:${pageId}`);
    if (pageRenderers.get(pageId) !== renderer) throw new Error(`Duplicate frontend page: ${pageId}`);
  }
}

export function renderFrontendPage(section: string, context: FrontendPageContext): ReactNode | null {
  return pageRenderers.get(section)?.(context) ?? null;
}

export function frontendRouteOwner(section: string): string | null {
  return pageOwners.get(section) ?? null;
}
