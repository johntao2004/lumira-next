import { orderModuleGraph } from "./graph.ts";
import type { FrontendModuleManifest, FrontendRoute } from "./types.ts";
import { identityManifest } from "../../features/account/manifest.ts";
import { platformManifest } from "../../features/platform/manifest.ts";
import { directoryManifest } from "../../features/directory/manifest.ts";
import { dictionariesManifest } from "../../features/dictionaries/manifest.ts";
import { filesManifest } from "../../features/files/manifest.ts";
import { settingsManifest } from "../../features/settings/manifest.ts";
import { eventsManifest } from "../../features/events/manifest.ts";
import { navigationManifest } from "../../features/navigation/manifest.ts";
import { notificationsManifest } from "../../features/notifications/manifest.ts";
import { monitoringManifest } from "../../features/monitoring/manifest.ts";
import { auditManifest } from "../../features/audit/manifest.ts";
import { feedbackManifest } from "../../features/feedback/manifest.ts";
import { sensitiveWordsManifest } from "../../features/sensitive/manifest.ts";
import { localizationManifest } from "../../features/localization/manifest.ts";
import { communicationManifest } from "../../features/communication/manifest.ts";

const candidates = [
  identityManifest,
  platformManifest,
  directoryManifest,
  dictionariesManifest,
  filesManifest,
  settingsManifest,
  eventsManifest,
  navigationManifest,
  notificationsManifest,
  monitoringManifest,
  auditManifest,
  feedbackManifest,
  sensitiveWordsManifest,
  localizationManifest,
  communicationManifest,
] as const;

type CandidateModule = (typeof candidates)[number];
export type FrontendRouteId = CandidateModule["routes"][number]["id"];
type RegisteredFrontendModule = Omit<FrontendModuleManifest, "id" | "routes"> & {
  readonly id: CandidateModule["id"];
  readonly routes: readonly FrontendRoute[];
};

/** Server-safe catalog: no JSX and no feature page implementation is imported here. */
export const frontendModuleManifests: readonly RegisteredFrontendModule[] = orderModuleGraph(candidates);
export const frontendRoutes = (): readonly FrontendRoute[] => frontendModuleManifests.flatMap((module) => module.routes);
