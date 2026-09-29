import { isMessageKey, type MessageKey } from "../i18n/messages.ts";
import { frontendModuleManifests, frontendRoutes, type FrontendRouteId } from "../modules/catalog.ts";
import type { FrontendRoute, NavigationGroupId } from "../modules/types.ts";
import type { RuntimeNavigationNode } from "../navigation/runtime-types";
export type { RuntimeNavigationNode } from "../navigation/runtime-types";

/** Shared route catalog assembled from the frontend modules. */
export type Section = FrontendRouteId;
export type RouteMetadata = Pick<FrontendRoute, "label" | "permission" | "global"> & { module?: string };

const routeEntries = frontendModuleManifests.flatMap((module) => module.routes.map((route) => {
  const optionalModule = module.required ? {} : { module: module.id };
  return [route.id, { label: route.label, permission: route.permission, ...(route.global ? { global: true } : {}), ...optionalModule }] as const;
}));

export const routes = Object.fromEntries(routeEntries) as Record<Section, RouteMetadata>;

export function isSection(value: string): value is Section {
  return Object.hasOwn(routes, value);
}
/** Safe single-segment dashboard keys supplied by the server-owned menu tree. */
export function isDashboardSection(value: string): boolean {
  return /^[a-z][a-z0-9-]{0,63}$/.test(value);
}

export type NavigationPage = {
  id: string;
  label: string;
  permission: string | null;
  module?: string;
  global?: boolean;
  path?: string | null;
  componentKey?: string | null;
  labelDefault?: string;
  dynamic?: boolean;
};
export type NavigationGroup = {
  id: string;
  label: string;
  labelDefault?: string;
  subgroup: MessageKey | null;
  sections: readonly Section[];
  pages: NavigationPage[];
};

/** Translate known catalog keys and safely fall back to server-provided labels. */
export function localizedNavigationLabel(
  labelKey: string | null | undefined,
  labelDefault: string | null | undefined,
  translate: (key: MessageKey) => string,
): string {
  if (labelKey && isMessageKey(labelKey)) return translate(labelKey);
  return labelDefault?.trim() || labelKey?.trim() || "";
}
export function canAccessSection(section: Section, authorities: readonly string[], enabledModules: readonly string[] = [], globalAuthorities: readonly string[] = authorities) {
  const permission = routes[section].permission;
  const route=routes[section];
  return (permission === null || (route.global ? globalAuthorities : authorities).includes(permission)) && (!route.module || enabledModules.includes(route.module));
}

const groupSpecs = [
  { id: "overview", label: "overview", subgroup: null },
  { id: "ai-management", label: "aiManagement", subgroup: null },
  { id: "user-management", label: "userManagement", subgroup: null },
  { id: "content-management", label: "contentManagement", subgroup: null },
  { id: "operations", label: "operationsCenter", subgroup: null },
  { id: "administration", label: "administration", subgroup: null },
] as const satisfies readonly { id: NavigationGroupId; label: MessageKey; subgroup: MessageKey | null }[];

export const navigationGroups: readonly {
  id: NavigationGroupId;
  label: MessageKey;
  subgroup: MessageKey | null;
  sections: readonly Section[];
}[] = groupSpecs.map((group) => ({
  ...group,
  sections: frontendRoutes()
    .filter((route) => route.group === group.id)
    .sort((left, right) => left.order - right.order)
    .map((route) => route.id as Section),
}));

export function availableNavigation(authorities: readonly string[], enabledModules: readonly string[] = [], globalAuthorities: readonly string[] = authorities): NavigationGroup[] {
  const groups: NavigationGroup[] = navigationGroups.map((group) => ({
    ...group,
    pages: group.sections.filter((section) => canAccessSection(section, authorities, enabledModules, globalAuthorities))
      .map((id) => ({ id, ...routes[id] })),
  }));
  return groups.filter((group) => group.pages.length > 0);
}

const tabNavigationGroups = [
  ["branding", "watermark", "agreements", "maintenance"],
  ["smtp", "sms"],
  ["security", "passkey"],
  ["users", "online-users"],
  ["mail-deliveries", "sms-deliveries"],
] as const;

/** Collapse a complete related route set to its first page in the sidebar. */
export function collapseTabbedNavigation(groups: NavigationGroup[]): NavigationGroup[] {
  return groups
    .map((group) => {
      const pageIds = new Set(group.pages.map((page) => page.id));
      const hidden = new Set<string>();
      for (const members of tabNavigationGroups) {
        if (members.every((id) => pageIds.has(id))) {
          members.slice(1).forEach((id) => hidden.add(id));
        }
      }
      return { ...group, pages: group.pages.filter((page) => !hidden.has(page.id)) };
    })
    .filter((group) => group.pages.length > 0);
}

/** Use the visible group entry as the active menu item for a tab deep link. */
export function navigationPageIdForRoute(routeId: string, groups: readonly NavigationGroup[]): string {
  const visibleIds = new Set(groups.flatMap((group) => group.pages.map((page) => page.id)));
  for (const members of tabNavigationGroups) {
    if (members.some((id) => id === routeId) && visibleIds.has(members[0]) && !visibleIds.has(routeId)) {
      return members[0];
    }
  }
  return routeId;
}

/**
 * Apply the server-owned menu order and enabled state without allowing an
 * unknown menu to create a route that the deployed frontend cannot render yet. Static route
 * metadata remains the type-safe source for permissions and page components.
 */
export function mergeRuntimeNavigation(groups: NavigationGroup[], nodes?: readonly RuntimeNavigationNode[]): NavigationGroup[] {
  if (!nodes) return groups;
  const order = new Map<string, number>();
  const visible = new Set<string>();
  const dynamic = new Map<string, NavigationPage[]>();
  const dynamicGroups = new Map<string, NavigationGroup>();
  const staticGroupIds = new Set(groups.map((group) => group.id));
  let index = 0;
  const visit = (items: readonly RuntimeNavigationNode[], rootCode: string | null = null) => {
    items.forEach((item) => {
      const nextRoot = rootCode ?? item.menuCode ?? null;
      if (!rootCode && item.menuType === "CATALOG" && item.menuCode && !staticGroupIds.has(item.menuCode)) {
        dynamicGroups.set(item.menuCode, {
          id: item.menuCode,
          label: item.labelKey || item.menuCode,
          labelDefault: item.labelDefault || item.menuCode,
          subgroup: null,
          sections: [],
          pages: [],
        });
      }
      if (item.sectionKey) {
        visible.add(item.sectionKey);
        order.set(item.sectionKey, index++);
      }
      if (item.menuType === "PAGE" && item.sectionKey && !isSection(item.sectionKey) && nextRoot) {
        const page: NavigationPage = {
          id: item.sectionKey,
          label: item.labelKey || item.sectionKey,
          labelDefault: item.labelDefault || item.sectionKey,
          permission: null,
          module: item.moduleId ?? undefined,
          path: item.path || `/dashboard/${item.sectionKey}`,
          componentKey: item.componentKey || item.menuCode || item.sectionKey,
          dynamic: true,
        };
        const pages = dynamic.get(nextRoot) ?? [];
        pages.push(page);
        dynamic.set(nextRoot, pages);
      }
      visit(item.children ?? [], nextRoot);
    });
  };
  visit(nodes);
  const merged = groups.map((group) => ({
    ...group,
    pages: [...group.pages.filter((page) => visible.has(page.id)), ...(dynamic.get(group.id) ?? [])]
      .sort((left, right) => (order.get(left.id) ?? Number.MAX_SAFE_INTEGER) - (order.get(right.id) ?? Number.MAX_SAFE_INTEGER)),
  }));
  dynamicGroups.forEach((group, id) => {
    group.pages = (dynamic.get(id) ?? []).sort((left, right) =>
      (order.get(left.id) ?? Number.MAX_SAFE_INTEGER) - (order.get(right.id) ?? Number.MAX_SAFE_INTEGER));
    if (group.pages.length) merged.push(group);
  });
  return merged.filter((group) => group.pages.length > 0);
}
