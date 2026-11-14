/**
 * 区分导航加载、页面不存在和访问受限，避免把未知页面的 404 误报为 403，同时保留受保护路由的拒绝状态。
 * Distinguish navigation loading, missing pages and denied access so unknown pages retain 404 semantics without weakening denial for protected routes.
 */
export type WorkspaceRouteStateInput = {
  registeredSection: boolean;
  accountSettingsPage: boolean;
  removedDraftsRoute: boolean;
  runtimeLoading: boolean;
  runtimePageExists: boolean;
  runtimePageRoutable?: boolean;
  previewUnavailable: boolean;
  /**
   * null 表示不存在静态路由；已注册路由保留现有授权判断。
   * Null means no static route; a registered route keeps its existing authorization decision.
   */
  activeRouteAccess: boolean | null;
};

export function workspaceRouteState(input: WorkspaceRouteStateInput) {
  const unknownSection = !input.runtimeLoading && !input.removedDraftsRoute
    && !input.accountSettingsPage && !input.registeredSection && !input.runtimePageExists;
  const denied = !unknownSection && (input.previewUnavailable || input.runtimePageRoutable === false
    || (!input.runtimeLoading && (input.activeRouteAccess ?? input.runtimePageExists) === false));
  return { unknownSection, denied };
}
