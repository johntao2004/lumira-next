import type { ReactNode } from "react";
import type { RouteTab } from "../components/route-tabs";
import type { FrontendModuleManifest } from "./types";

/** Build tab labels and deep-link paths from the module route catalog. */
export function routeTabsForGroup(
  manifest: FrontendModuleManifest,
  groupId: string,
  childrenByRoute: Readonly<Record<string, ReactNode | undefined>>,
): RouteTab[] {
  const group = manifest.tabGroups?.find((candidate) => candidate.id === groupId);
  if (!group) throw new Error(`Missing frontend tab group: ${manifest.id}:${groupId}`);

  return group.routes.flatMap((routeId) => {
    const route = manifest.routes.find((candidate) => candidate.id === routeId);
    if (!route) throw new Error(`Unknown frontend tab route: ${manifest.id}:${groupId}:${routeId}`);
    const children = childrenByRoute[routeId];
    if (children === undefined) return [];
    return [{
      key: route.id,
      path: `/dashboard/${route.id}`,
      label: (route.tabLabel ?? route.label) as RouteTab["label"],
      children,
    }];
  });
}
