/**
 * 定义宿主与插件共用的页面契约，分别表达页面访问、操作权限及全局／作用域授权，避免混淆访问与操作能力。
 * Define shared host and plugin page contracts that distinguish page access, operation grants and global or scoped authorities rather than conflating access with action rights.
 */
import type { ComponentType, ReactNode } from "react";
import type { MessageKey } from "../i18n/messages";

export type NavigationGroupId =
  | "overview"
  | "ai-management"
  | "user-management"
  | "content-management"
  | "communication"
  | "payments"
  | "operations"
  | "administration";

/**
 * 路由及其页面渲染器必须归属同一前端模块。
 * A route and its page renderer belong to the same frontend module.
 */
export type FrontendRoute = {
  readonly id: string;
  readonly label: string;
  /**
   * 页签内可使用独立文案；侧栏仍使用 label。
   * Optional wording used inside a route tab; sidebar wording remains `label`.
   */
  readonly tabLabel?: MessageKey;
  /**
   * 路由访问权限；操作授权另行声明。
   * Route access permission. Operation grants are declared separately.
   */
  readonly permission: string | null;
  /**
   * 指定页面权限按全局授权还是调用方数据范围授权校验。
   * Whether the page grant is checked in global or caller/data-scoped authorities.
   */
  readonly accessScope?: "global" | "scoped";
  /**
   * 由本页面持有的显式动作标识到操作权限码映射。
   * Explicit action id to operation-code mappings owned by this page.
   */
  readonly operations?: Readonly<Record<string, string>>;
  /**
   * 这些动作按调用方有效授权校验，其余动作按全局授权校验。
   * Action ids whose grants are checked in the caller's effective authorities; other actions are global.
   */
  readonly scopedOperations?: readonly string[];
  readonly global?: boolean;
  readonly hidden?: boolean;
  readonly group: NavigationGroupId;
  readonly order: number;
};

export type FrontendTabGroup = {
  /**
   * 在所属模块内保持稳定的键。
   * Stable key local to the owning module.
   */
  readonly id: string;
  /**
   * 按显示顺序列出页签路由标识，包含侧栏锚点。
   * Ordered route ids shown as tabs, including the sidebar anchor.
   */
  readonly routes: readonly string[];
  readonly anchor: string;
  /**
   * 仅部分页签可见时，仍保留安全设置锚点。
   * Preserve the security settings anchor when only a subset of tabs is visible.
   */
  readonly collapse: "when-all-visible" | "when-anchor-visible";
};

export type FrontendPageContext = {
  readonly authorities: string[];
  readonly hasGlobal: (permission: string) => boolean;
  readonly hasRouteAccess: (routeId: string) => boolean;
  readonly hasRouteOperation: (routeId: string, actionId: string) => boolean;
  /**
   * 校验当前页面路由声明的动作。
   * Check an action declared by the current page route.
   */
  readonly hasOperation: (actionId: string) => boolean;
  readonly readOnlyPreview?: boolean;
  /**
   * 在宿主流程中独立渲染此页面并回调保存完成事件。
   * Render this page alone inside a host workflow and report a completed save.
   */
  readonly embedded?: {
    readonly onSaved?: () => void | Promise<void>;
    readonly presentation?: "summary";
  };
};

export type FrontendPageRenderer = (context: FrontendPageContext) => ReactNode;

export type FrontendModuleManifest = {
  readonly id: string;
  readonly version: string;
  readonly required: boolean;
  readonly dependencies: readonly string[];
  readonly routes: readonly FrontendRoute[];
  readonly tabGroups?: readonly FrontendTabGroup[];
};

export type FrontendModule = FrontendModuleManifest & {
  readonly pages: Readonly<Record<string, FrontendPageRenderer>>;
  /**
   * 账号级顶部栏操作控件；其对应 API 必须自行强制校验访问权限。
   * Account-level header controls; their APIs must enforce their own access rules.
   */
  readonly workspaceActions?: readonly FrontendWorkspaceAction[];
};

export type FrontendWorkspaceAction = {
  /**
   * 在当前模块内唯一。
   * Unique within this module.
   */
  readonly id: string;
  /**
   * 函数或类组件；使用 lazy 或 memo 包装时须再包裹一层函数。
   * Function/class component. Wrap lazy or memo components in a function.
   */
  readonly component: ComponentType;
};

export type RegisteredWorkspaceAction = FrontendWorkspaceAction & {
  readonly moduleId: string;
  /**
   * 重新注册时变更，防止 React 保留前一个插件实例的状态。
   * Changes on re-registration so React cannot retain the previous plugin's state.
   */
  readonly key: string;
};
