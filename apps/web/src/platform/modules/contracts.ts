import type { ReactNode } from "react";
import type { FrontendModule, FrontendModuleManifest, FrontendPageContext, FrontendRoute, RegisteredWorkspaceAction } from "./types";

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
  const actionIds = new Set<string>();
  for (const action of module.workspaceActions ?? []) {
    if (!routeIdPattern.test(action.id) || typeof action.component !== "function") {
      throw new Error(`Invalid workspace action: ${module.id}:${action.id}`);
    }
    if (actionIds.has(action.id)) throw new Error(`Duplicate workspace action: ${module.id}:${action.id}`);
    actionIds.add(action.id);
  }
}

export type FrontendPageRegistry = {
  render: (section: string, context: FrontendPageContext) => ReactNode | null;
  owner: (section: string) => string | null;
  registrationKey: (section: string) => string | null;
  registerPlugin: (module: FrontendModule) => () => void;
  workspaceActions: () => readonly RegisteredWorkspaceAction[];
  subscribeWorkspaceActions: (listener: () => void) => () => void;
};

type PluginRegistration = {
  readonly key: string;
  readonly routeIds: readonly string[];
};

type DynamicPageRegistration = {
  readonly moduleId: string;
  readonly owner: PluginRegistration;
  readonly route: FrontendRoute;
};

function hasPageAccess(route: FrontendRoute, context: FrontendPageContext): boolean {
  const scope = route.accessScope ?? (route.global ? "global" : "scoped");
  return route.permission === null || (scope === "global"
    ? context.hasGlobal(route.permission)
    : context.authorities.includes(route.permission));
}

function hasPageOperation(route: FrontendRoute, actionId: string, context: FrontendPageContext): boolean {
  if (!hasPageAccess(route, context)) return false;
  const operation = route.operations?.[actionId];
  if (!operation) return false;
  return route.scopedOperations?.includes(actionId)
    ? context.authorities.includes(operation)
    : context.hasGlobal(operation);
}

/** Owns renderer registration and prevents stale or duplicate plugin teardown. */
export function createFrontendPageRegistry(modules: readonly FrontendModule[]): FrontendPageRegistry {
  const pageRenderers = new Map<string, FrontendModule["pages"][string]>();
  const pageOwners = new Map<string, string>();
  const staticModuleIds = new Set<string>();
  const actionModules = new Map<string, readonly RegisteredWorkspaceAction[]>();
  let generation = 0;
  const registerActions = (module: FrontendModule) => {
    const revision = ++generation;
    actionModules.set(module.id, Object.freeze((module.workspaceActions ?? []).map(action => Object.freeze({
      id: action.id,
      component: action.component,
      moduleId: module.id,
      key: `${module.id}:${revision}:${action.id}`,
    }))));
  };
  for (const module of modules) {
    validateFrontendModule(module);
    if (staticModuleIds.has(module.id)) throw new Error(`Duplicate frontend module: ${module.id}`);
    staticModuleIds.add(module.id);
    registerActions(module);
    for (const route of module.routes) {
      if (pageOwners.has(route.id)) throw new Error(`Duplicate frontend route: ${route.id}`);
      pageOwners.set(route.id, module.id);
      const renderer = module.pages[route.id];
      if (typeof renderer === "function") pageRenderers.set(route.id, renderer);
    }
  }

  const dynamicOwners = new Map<string, PluginRegistration>();
  const dynamicPages = new Map<string, DynamicPageRegistration>();
  const listeners = new Set<() => void>();
  const snapshot = () => Object.freeze([...actionModules.values()].flat());
  let actions = snapshot();
  const publishActions = () => {
    // Embedded pages still observe every registration; unchanged header controls keep one snapshot.
    const nextActions = snapshot();
    if (nextActions.length !== actions.length || nextActions.some((action, index) => action !== actions[index])) {
      actions = nextActions;
    }
    for (const listener of [...listeners]) {
      try {
        listener();
      } catch (error) {
        // Registration has committed. A broken observer must not prevent the
        // caller receiving its disposer or stop other mounted shells updating.
        console.error("Workspace action subscriber failed", error);
      }
    }
  };
  return {
    workspaceActions: () => actions,
    subscribeWorkspaceActions(listener) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
    render(section, context) {
      const renderer = pageRenderers.get(section);
      if (!renderer) return null;
      const dynamicPage = dynamicPages.get(section);
      if (!dynamicPage) return renderer(context);

      const isActive = (page: DynamicPageRegistration) => (
        dynamicOwners.get(page.moduleId) === page.owner
        && dynamicPages.get(page.route.id) === page
      );
      const hasDynamicAccess = (routeId: string) => {
        const target = dynamicPages.get(routeId);
        if (!target) return dynamicPage.owner.routeIds.includes(routeId) ? false : null;
        if (!isActive(dynamicPage) || !isActive(target)) return false;
        return hasPageAccess(target.route, context);
      };
      const hasDynamicOperation = (routeId: string, actionId: string) => {
        const target = dynamicPages.get(routeId);
        if (!target) return dynamicPage.owner.routeIds.includes(routeId) ? false : null;
        if (!isActive(dynamicPage) || !isActive(target)) return false;
        return hasPageOperation(target.route, actionId, context);
      };

      return renderer({
        ...context,
        hasRouteAccess(routeId) {
          return hasDynamicAccess(routeId) ?? context.hasRouteAccess(routeId);
        },
        hasRouteOperation(routeId, actionId) {
          return hasDynamicOperation(routeId, actionId) ?? context.hasRouteOperation(routeId, actionId);
        },
        hasOperation(actionId) {
          return hasDynamicOperation(dynamicPage.route.id, actionId) ?? false;
        },
      });
    },
    registrationKey(section) {
      return dynamicPages.get(section)?.owner.key ?? pageOwners.get(section) ?? null;
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

      const registration: PluginRegistration = { routeIds: Object.freeze(routeIds.slice()), key: `${module.id}:${++generation}` };
      dynamicOwners.set(module.id, registration);
      for (const route of module.routes) {
        const routeId = route.id;
        dynamicPages.set(routeId, { moduleId: module.id, owner: registration, route });
        pageOwners.set(routeId, module.id);
        pageRenderers.set(routeId, module.pages[routeId]);
      }
      registerActions(module);
      publishActions();

      let registered = true;
      return () => {
        if (!registered) return;
        registered = false;
        if (dynamicOwners.get(module.id) !== registration) return;
        dynamicOwners.delete(module.id);
        for (const routeId of registration.routeIds) {
          const dynamicPage = dynamicPages.get(routeId);
          if (dynamicPage?.owner === registration) dynamicPages.delete(routeId);
          if (pageOwners.get(routeId) !== module.id) continue;
          pageOwners.delete(routeId);
          pageRenderers.delete(routeId);
        }
        actionModules.delete(module.id);
        publishActions();
      };
    },
  };
}
