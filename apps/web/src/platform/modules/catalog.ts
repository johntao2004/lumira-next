/**
 * 仅汇总清单元数据供路由和导航使用，不导入页面实现，也不把目录声明等同于动态插件已安装或运行。
 * Collect only manifest metadata for routes and navigation without importing page implementations or treating catalog presence as proof that a dynamic plugin is installed or running.
 */
import { personalFilesManifest } from "../../../../../plugins/personal-files/frontend/manifest.ts";
import { authenticationConsoleManifest } from "../../../../../plugins/authentication-console/frontend/manifest.ts";
import { profileConsoleManifest } from "../../../../../plugins/profile-console/frontend/manifest.ts";
import { deliverySettingsManifest } from "../../../../../plugins/delivery-settings/frontend/manifest.ts";
import { siteConsoleManifest } from "../../../../../plugins/site-console/frontend/manifest.ts";
import { fileConsoleManifest } from "../../../../../plugins/file-console/frontend/manifest.ts";
import { fileRetentionManifest } from "../../../../../plugins/file-retention/frontend/manifest.ts";
import { aiManifest } from "../../../../../plugins/ai/frontend/manifest.ts";
import { orderModuleGraph } from "./graph.ts";
import type { FrontendModuleManifest, FrontendRoute } from "./types.ts";
import { validateFrontendModuleManifest } from "./contracts.ts";
import { identityManifest } from "../../features/account/manifest.ts";
import { platformManifest } from "../../features/platform/manifest.ts";
import { directoryManifest } from "../../../../../plugins/directory/frontend/manifest.ts";
import { dictionariesManifest } from "../../../../../plugins/dictionaries/frontend/manifest.ts";
import { filesManifest } from "../../features/files/manifest.ts";
import { settingsManifest } from "../../features/settings/manifest.ts";
import { eventsManifest } from "../../features/events/manifest.ts";
import { navigationManifest } from "../../features/navigation/manifest.ts";
import { notificationsManifest } from "../../../../../plugins/notifications/frontend/manifest.ts";
import { monitoringManifest } from "../../../../../plugins/monitoring/frontend/manifest.ts";
import { auditManifest } from "../../../../../plugins/audit-console/frontend/manifest.ts";
import { feedbackManifest } from "../../../../../plugins/feedback/frontend/manifest.ts";
import { sensitiveWordsManifest } from "../../../../../plugins/sensitive-words/frontend/manifest.ts";
import { localizationManifest } from "../../../../../plugins/localization/frontend/manifest.ts";
import { mfaManifest } from "../../../../../plugins/mfa/frontend/manifest.ts";
import { communicationManifest } from "../../features/communication/manifest.ts";

import { eventsManifest as eventConsoleManifest } from "../../../../../plugins/event-console/frontend/manifest.ts";

import { communicationManifest as deliveryConsoleManifest } from "../../../../../plugins/delivery-console/frontend/manifest.ts";

import { navigationManifest as navigationConsoleManifest } from "../../../../../plugins/navigation-console/frontend/manifest.ts";

const candidates = [
  personalFilesManifest,
  authenticationConsoleManifest,
  profileConsoleManifest,
  deliverySettingsManifest,
  siteConsoleManifest,
  aiManifest,
  navigationConsoleManifest,
  deliveryConsoleManifest,
  eventConsoleManifest,
  identityManifest,
  platformManifest,
  directoryManifest,
  dictionariesManifest,
  filesManifest,
  fileRetentionManifest,
  fileConsoleManifest,
  settingsManifest,
  eventsManifest,
  navigationManifest,
  notificationsManifest,
  monitoringManifest,
  auditManifest,
  feedbackManifest,
  sensitiveWordsManifest,
  localizationManifest,
  mfaManifest,
  communicationManifest,
] as const;

type CandidateModule = (typeof candidates)[number];
export type FrontendRouteId = CandidateModule["routes"][number]["id"];
type RegisteredFrontendModule = Omit<FrontendModuleManifest, "id" | "routes"> & {
  readonly id: CandidateModule["id"];
  readonly routes: readonly FrontendRoute[];
};

/**
 * 可安全用于服务端的纯目录：此处不导入 JSX 或功能页面实现。
 * Server-safe catalog: no JSX and no feature page implementation is imported here.
 */
export const frontendModuleManifests: readonly RegisteredFrontendModule[] = orderModuleGraph(candidates);
const catalogRouteOwners = new Map<string, string>();
for (const module of frontendModuleManifests) {
  validateFrontendModuleManifest(module);
  for (const route of module.routes) {
    const owner = catalogRouteOwners.get(route.id);
    if (owner) throw new Error(`Duplicate frontend route: ${route.id}`);
    catalogRouteOwners.set(route.id, module.id);
  }
}
export const frontendRoutes = (): readonly FrontendRoute[] => frontendModuleManifests.flatMap((module) => module.routes);
export const frontendTabGroups = frontendModuleManifests.flatMap((module) => (module.tabGroups ?? []).map((group) => ({ moduleId: module.id, ...group })));
