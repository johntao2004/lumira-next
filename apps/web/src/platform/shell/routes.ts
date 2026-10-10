import { isMessageKey, type MessageKey } from "../i18n/messages.ts";
import { frontendModuleManifests, frontendRoutes, frontendTabGroups, type FrontendRouteId } from "../modules/catalog.ts";
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
  standalone?: boolean;
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
    .filter((route) => !route.hidden)
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

const tabNavigationGroups = frontendTabGroups;

/** Collapse related route tabs to their visible anchor in the sidebar. */
export function collapseTabbedNavigation(groups: NavigationGroup[]): NavigationGroup[] {
  return groups
    .map((group) => {
      const pageIds = new Set(group.pages.map((page) => page.id));
      const hidden = new Set<string>();
      for (const tabGroup of tabNavigationGroups) {
        const groupIsVisible = tabGroup.collapse === "when-anchor-visible"
          ? pageIds.has(tabGroup.anchor)
          : tabGroup.routes.every((id) => pageIds.has(id));
        if (groupIsVisible) {
          tabGroup.routes.filter((id) => id !== tabGroup.anchor).forEach((id) => hidden.add(id));
        }
      }
      return { ...group, pages: group.pages.filter((page) => !hidden.has(page.id)) };
    })
    .filter((group) => group.pages.length > 0);
}

/** Use the visible group entry as the active menu item for a tab deep link. */
export function navigationPageIdForRoute(routeId: string, groups: readonly NavigationGroup[]): string {
  const visibleIds = new Set(groups.flatMap((group) => group.pages.map((page) => page.id)));
  for (const tabGroup of tabNavigationGroups) {
    if (tabGroup.routes.includes(routeId) && visibleIds.has(tabGroup.anchor) && !visibleIds.has(routeId)) {
      return tabGroup.anchor;
    }
  }
  return routeId;
}

/** Find a runtime PAGE by exact path first, then by its stable frontend section key. */
export function runtimeNavigationPageNode(
  nodes: readonly RuntimeNavigationNode[],
  section: string,
  pathname: string,
): RuntimeNavigationNode | null {
  const find = (matches: (node: RuntimeNavigationNode) => boolean) => {
    const stack = nodes.slice().reverse();
    while (stack.length) {
      const node = stack.pop();
      if (!node) continue;
      if (node.menuType === "ACTION") continue;
      if (node.menuType === "PAGE" && matches(node)) return node;
      const children = node.children ?? [];
      for (let index = children.length - 1; index >= 0; index--) stack.push(children[index]);
    }
    return null;
  };

  return find(node => Boolean(pathname) && node.path === pathname)
    ?? find(node => Boolean(section) && node.sectionKey === section);
}

/** Find a route's full visible catalog/page ancestry from the permission-filtered runtime tree. */
export function runtimeNavigationTrail(
  nodes: readonly RuntimeNavigationNode[],
  section: string,
  pathname: string,
): RuntimeNavigationNode[] | null {
  const target = runtimeNavigationPageNode(nodes, section, pathname);
  if (!target || !(target.routable === true || (target.routable === undefined && Boolean(target.sectionKey && isSection(target.sectionKey))))) {
    return null;
  }

  const stack = nodes.slice().reverse().map(node => ({ node, trail: [] as RuntimeNavigationNode[] }));
  while (stack.length) {
    const current = stack.pop();
    if (!current) continue;
    const { node } = current;
    if (node.menuType === "ACTION") continue;
    const trail = node.menuType === "CATALOG" || node.menuType === "PAGE"
      ? [...current.trail, node]
      : current.trail;
    if (node === target) return trail;
    const children = node.children ?? [];
    for (let index = children.length - 1; index >= 0; index--) stack.push({ node: children[index], trail });
  }
  return null;
}

/** Resolve labels for the runtime trail; pages absent from the user's tree keep only their title. */
export function runtimeBreadcrumbLabels(
  trail: readonly RuntimeNavigationNode[] | null,
  fallbackTitle: string,
  localize: (node: RuntimeNavigationNode) => string,
): string[] {
  const runtimeLabels = (trail ?? []).map(localize).filter(label => Boolean(label.trim()));
  const labels = runtimeLabels.length ? runtimeLabels : [fallbackTitle];
  return labels.filter(label => Boolean(label.trim()));
}

/**
 * Apply the server-owned menu order and enabled state without allowing an
 * unknown menu to create a route that the deployed frontend cannot render yet. Static route
 * metadata remains the type-safe source for permissions and page components.
 */
export function mergeRuntimeNavigation(groups: NavigationGroup[], nodes?: readonly RuntimeNavigationNode[]): NavigationGroup[] {
  if (!nodes) return groups;
  const staticGroups = new Map(groups.map(group => [group.id, group]));
  const staticPages = new Map(groups.flatMap(group => group.pages.map(page => [page.id, page] as const)));
  const staticGroupFallbacks = new Map<string, NavigationGroup>(navigationGroups.map(group => [
    group.id,
    { ...group, labelDefault: undefined, pages: [] },
  ]));
  const staticOwners = new Map<string, string>(navigationGroups.flatMap(group =>
    group.sections.map(section => [section, group.id] as [string, string]),
  ));
  const merged = new Map<string, NavigationGroup>();
  const orderedIds: string[] = [];

  const ensureGroup = (id: string, label?: string | null, labelDefault?: string | null) => {
    let group = merged.get(id);
    if (!group) {
      const original = staticGroups.get(id) ?? staticGroupFallbacks.get(id);
      group = original
        ? { ...original, pages: [], label: label || original.label, labelDefault: labelDefault || original.labelDefault }
        : { id, label: label || id, labelDefault: labelDefault || label || id, subgroup: null, sections: [], pages: [] };
      merged.set(id, group);
      orderedIds.push(id);
    } else {
      if (label) group.label = label;
      if (labelDefault) group.labelDefault = labelDefault;
    }
    return group;
  };

  const ensureStandaloneGroup = (page: NavigationPage) => {
    const id = `root-page:${page.id}`;
    let group = merged.get(id);
    if (!group) {
      group = {
        id,
        label: page.label,
        labelDefault: page.labelDefault,
        subgroup: null,
        sections: [],
        pages: [],
        standalone: true,
      };
      merged.set(id, group);
      orderedIds.push(id);
    }
    return group;
  };

  const ensureRootAncestorGroup = (node: RuntimeNavigationNode) => {
    const id = `root-ancestor:${node.id ?? node.menuCode ?? node.sectionKey ?? "page"}`;
    return ensureGroup(id, node.labelKey?.trim() || node.menuCode, node.labelDefault);
  };

  const pageForRuntimeNode = (node: RuntimeNavigationNode): NavigationPage | null => {
    const id = node.sectionKey;
    if (!id) return null;
    const saved = staticPages.get(id);
    if (saved) {
      if (node.routable === false) return null;
      return {
        ...saved,
        label: node.labelKey?.trim() || saved.label,
        labelDefault: node.labelDefault?.trim() || saved.labelDefault,
        path: node.path?.trim() || saved.path,
        componentKey: node.componentKey?.trim() || saved.componentKey,
      };
    }
    // Registered but unavailable routes stay hidden; runtime data cannot grant static permissions.
    if (isSection(id)) return null;
    if (node.routable !== true) return null;
    return {
      id,
      label: node.labelKey?.trim() || node.menuCode || id,
      labelDefault: node.labelDefault?.trim() || node.labelKey?.trim() || id,
      permission: null,
      module: node.moduleId ?? undefined,
      path: node.path?.trim() || `/dashboard/${id}`,
      componentKey: node.componentKey?.trim() || node.menuCode || id,
      dynamic: true,
    };
  };

  const appendPages = (items: readonly RuntimeNavigationNode[], group: NavigationGroup) => {
    const stack = items.slice().reverse();
    while (stack.length) {
      const item = stack.pop();
      if (!item || item.menuType === "ACTION") continue;
      if (item.menuType === "PAGE") {
        const page = pageForRuntimeNode(item);
        if (page) group.pages.push(page);
      }
      const children = item.children ?? [];
      for (let index = children.length - 1; index >= 0; index--) stack.push(children[index]);
    }
  };

  nodes.forEach(root => {
    if (root.menuType === "CATALOG") {
      const id = root.menuCode?.trim() || root.id?.trim();
      if (!id) return;
      appendPages(root.children ?? [], ensureGroup(id, root.labelKey, root.labelDefault));
      return;
    }
    // Older menu trees may place a built-in PAGE at the root. Keep it under its existing static group.
    if (root.menuType === "PAGE") {
      const page = pageForRuntimeNode(root);
      const owner = root.sectionKey ? staticOwners.get(root.sectionKey) : undefined;
      if (owner) {
        const group = ensureGroup(owner);
        if (page) group.pages.push(page);
        appendPages(root.children ?? [], group);
      } else if (page?.dynamic) {
        const group = ensureStandaloneGroup(page);
        group.pages.push(page);
        appendPages(root.children ?? [], group);
      } else if ((root.children ?? []).length > 0) {
        appendPages(root.children ?? [], ensureRootAncestorGroup(root));
      }
    }
  });

  return orderedIds
    .map(id => merged.get(id)!)
    .map(group => ({
      ...group,
      sections: group.pages.flatMap(page => isSection(page.id) ? [page.id as Section] : []),
    }))
    .filter(group => group.pages.length > 0);
}
