import type { ReactNode } from "react";
import type { FrontendModule, FrontendModuleManifest, FrontendPageContext } from "./types";

const moduleIdPattern = /^[a-z][a-z0-9-]{0,99}$/u;
const routeIdPattern = /^[a-z][a-z0-9-]{0,63}$/u;
const tabGroupIdPattern = /^[a-z][a-z0-9-]{0,63}$/u;
// The shell renders Workbench directly and intentionally does not register a feature renderer for home.
const shellOwnedRouteIds = new Set(["home"]);

/** Validate static and plugin module metadata before it enters a route catalog. */
export function validateFrontendModuleManifest(manifest: FrontendModuleManifest): void {
  if (!moduleIdPattern.test(manifest.id)) throw new Error(`Invalid frontend module id: ${manifest.id}`);

  const routes = new Set<string>();
  for (const route of manifest.routes) {
    if (!routeIdPattern.test(route.id)) throw new Error(`Invalid frontend route: ${manifest.id}:${route.id}`);
    if (routes.has(route.id)) throw new Error(`Duplicate frontend route: ${route.id}`);
    routes.add(route.id);
  }

  const groupIds = new Set<string>();
  const groupedRoutes = new Set<string>();
  for (const group of manifest.tabGroups ?? []) {
    if (!tabGroupIdPattern.test(group.id)) throw new Error(`Invalid frontend tab group: ${manifest.id}:${group.id}`);
    if (groupIds.has(group.id)) throw new Error(`Duplicate frontend tab group: ${manifest.id}:${group.id}`);
    groupIds.add(group.id);
    if (group.routes.length < 2) throw new Error(`Frontend tab group needs at least two routes: ${manifest.id}:${group.id}`);
    if (!group.routes.includes(group.anchor)) throw new Error(`Frontend tab group anchor is not a member: ${manifest.id}:${group.id}`);
    const members = new Set<string>();
    for (const routeId of group.routes) {
      if (members.has(routeId)) throw new Error(`Duplicate frontend tab route: ${manifest.id}:${group.id}:${routeId}`);
      if (!routes.has(routeId)) throw new Error(`Unknown frontend tab route: ${manifest.id}:${group.id}:${routeId}`);
      if (groupedRoutes.has(routeId)) throw new Error(`Frontend route belongs to multiple tab groups: ${routeId}`);
      members.add(routeId);
      groupedRoutes.add(routeId);
    }
  }
}

/** Static modules and loaded plugins must provide one renderer for every declared route. */
export function validateFrontendModule(module: FrontendModule): void {
  validateFrontendModuleManifest(module);
  const routeIds = new Set(module.routes.map((route) => route.id));
  for (const routeId of routeIds) {
    if (typeof module.pages[routeId] !== "function" && !shellOwnedRouteIds.has(routeId)) {
      throw new Error(`Missing frontend page: ${module.id}:${routeId}`);
    }
  }
  for (const pageId of Object.keys(module.pages)) {
    if (!routeIds.has(pageId)) throw new Error(`Uncatalogued frontend page: ${module.id}:${pageId}`);
  }
}

export type FrontendPageRegistry = {
  render: (section: string, context: FrontendPageContext) => ReactNode | null;
  owner: (section: string) => string | null;
  registerPlugin: (module: FrontendModule) => () => void;
};

/** Owns renderer registration and prevents stale or duplicate plugin teardown. */
export function createFrontendPageRegistry(modules: readonly FrontendModule[]): FrontendPageRegistry {
  const pageRenderers = new Map<string, FrontendModule["pages"][string]>();
  const pageOwners = new Map<string, string>();
  const staticModuleIds = new Set<string>();
  for (const module of modules) {
    validateFrontendModule(module);
    if (staticModuleIds.has(module.id)) throw new Error(`Duplicate frontend module: ${module.id}`);
    staticModuleIds.add(module.id);
    for (const route of module.routes) {
      if (pageOwners.has(route.id)) throw new Error(`Duplicate frontend route: ${route.id}`);
      pageOwners.set(route.id, module.id);
      const renderer = module.pages[route.id];
      if (typeof renderer === "function") pageRenderers.set(route.id, renderer);
    }
  }

  const dynamicOwners = new Map<string, readonly string[]>();
  return {
    render(section, context) {
      return pageRenderers.get(section)?.(context) ?? null;
    },
    owner(section) {
      return pageOwners.get(section) ?? null;
    },
    registerPlugin(module) {
      validateFrontendModule(module);
      if (staticModuleIds.has(module.id) || dynamicOwners.has(module.id)) {
        throw new Error(`Duplicate plugin module: ${module.id}`);
      }
      const routeIds = module.routes.map((route) => route.id);
      for (const routeId of routeIds) {
        if (pageOwners.has(routeId)) throw new Error(`Duplicate plugin route: ${routeId}`);
      }

      const ownedIds = Object.freeze(routeIds.slice());
      dynamicOwners.set(module.id, ownedIds);
      for (const routeId of ownedIds) {
        pageOwners.set(routeId, module.id);
        pageRenderers.set(routeId, module.pages[routeId]);
      }

      let registered = true;
      return () => {
        if (!registered) return;
        registered = false;
        if (dynamicOwners.get(module.id) !== ownedIds) return;
        dynamicOwners.delete(module.id);
        for (const routeId of ownedIds) {
          if (pageOwners.get(routeId) !== module.id) continue;
          pageOwners.delete(routeId);
          pageRenderers.delete(routeId);
        }
      };
    },
  };
}
