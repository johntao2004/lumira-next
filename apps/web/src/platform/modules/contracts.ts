/**
 * 校验清单及页面归属并拒绝冲突，使旧卸载回调和已失效的动态路由权限辅助判断不能影响新注册。
 * Validate manifests and page ownership, reject conflicts, and prevent stale disposers or expired dynamic-route permission helpers from affecting newer registrations.
 */
import type { ReactNode } from "react";
import type { FrontendModule, FrontendModuleManifest, FrontendPageContext, FrontendRoute } from "./types";

const moduleIdPattern = /^[a-z][a-z0-9-]{0,99}$/u;
const routeIdPattern = /^[a-z][a-z0-9-]{0,63}$/u;
const tabGroupIdPattern = /^[a-z][a-z0-9-]{0,63}$/u;
// 外壳直接渲染工作台，因此有意不为 home 注册功能渲染器。
// The shell renders Workbench directly and intentionally does not register a feature renderer for home.
const shellOwnedRouteIds = new Set(["home"]);

/**
 * 静态模块及插件元数据进入路由目录前先完成校验。
 * Validate static and plugin module metadata before it enters a route catalog.
 */
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

/**
 * 静态模块与已加载插件必须为每条声明路由提供一个渲染器。
 * Static modules and loaded plugins must provide one renderer for every declared route.
 */
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
  pluginRoute: (section: string) => FrontendRoute | null;
  registerPlugin: (module: FrontendModule) => () => void;
};

type PluginRegistration = {
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

/**
 * 负责渲染器注册，防止过期或重复的插件清理移除有效注册。
 * Owns renderer registration and prevents stale or duplicate plugin teardown.
 */
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

  const dynamicOwners = new Map<string, PluginRegistration>();
  const dynamicPages = new Map<string, DynamicPageRegistration>();
  return {
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
    pluginRoute(section) {
      return dynamicPages.get(section)?.route ?? null;
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

      const registration: PluginRegistration = { routeIds: Object.freeze(routeIds.slice()) };
      dynamicOwners.set(module.id, registration);
      for (const route of module.routes) {
        const routeId = route.id;
        dynamicPages.set(routeId, { moduleId: module.id, owner: registration, route });
        pageOwners.set(routeId, module.id);
        pageRenderers.set(routeId, module.pages[routeId]);
      }

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
      };
    },
  };
}
