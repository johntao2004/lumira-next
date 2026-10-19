/**
 * 保存共享页面注册表以隔离业务装配依赖，并要求先初始化静态页面再注册插件，保留页面归属和冲突约束。
 * Hold the shared page registry independently of feature composition and require static-page initialization before plugin registration to preserve ownership and collision checks.
 */
import { createFrontendPageRegistry, type FrontendPageRegistry } from './contracts.ts';
import type { FrontendModule, FrontendPageContext } from './types';

// 静态模块只由宿主装配入口提供；使用方不得反向导入该入口。
// Only the host composition root supplies static modules. Consumers never import that root.
let pages: FrontendPageRegistry = createFrontendPageRegistry([]);
let initialized = false;
export function initializeHostPages(modules: readonly FrontendModule[]) {
  if (initialized) throw new Error('Host pages already initialized');
  pages = createFrontendPageRegistry(modules);
  initialized = true;
}
export const renderFrontendPage = (section: string, context: FrontendPageContext) => pages.render(section, context);
export const frontendRouteOwner = (section: string) => pages.owner(section);
export const registerPluginPages = (module: FrontendModule) => {
  if (!initialized) throw new Error('Host pages not initialized');
  return pages.registerPlugin(module);
};

export const frontendPluginRoute = (section: string) => pages.pluginRoute(section);
