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

/** A route and its page renderer belong to the same frontend module. */
export type FrontendRoute = {
  readonly id: string;
  readonly label: string;
  /** Optional wording used inside a route tab; sidebar wording remains `label`. */
  readonly tabLabel?: MessageKey;
  /** Route access permission. Operation grants are declared separately. */
  readonly permission: string | null;
  /** Whether the page grant is checked in global or caller/data-scoped authorities. */
  readonly accessScope?: "global" | "scoped";
  /** Explicit action id to operation-code mappings owned by this page. */
  readonly operations?: Readonly<Record<string, string>>;
  /** Action ids whose grants are checked in the caller's effective authorities; other actions are global. */
  readonly scopedOperations?: readonly string[];
  readonly global?: boolean;
  readonly hidden?: boolean;
  readonly group: NavigationGroupId;
  readonly order: number;
};

export type FrontendTabGroup = {
  /** Stable key local to the owning module. */
  readonly id: string;
  /** Ordered route ids shown as tabs, including the sidebar anchor. */
  readonly routes: readonly string[];
  readonly anchor: string;
  /** Preserve the security settings anchor when only a subset of tabs is visible. */
  readonly collapse: "when-all-visible" | "when-anchor-visible";
};

export type FrontendPageContext = {
  readonly authorities: string[];
  readonly hasGlobal: (permission: string) => boolean;
  readonly hasRouteAccess: (routeId: string) => boolean;
  readonly hasRouteOperation: (routeId: string, actionId: string) => boolean;
  /** Check an action declared by the current page route. */
  readonly hasOperation: (actionId: string) => boolean;
  readonly readOnlyPreview?: boolean;
  /** Render this page alone inside a host workflow and report a completed save. */
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
  /** Account-level header controls; their APIs must enforce their own access rules. */
  readonly workspaceActions?: readonly FrontendWorkspaceAction[];
};

export type FrontendWorkspaceAction = {
  /** Unique within this module. */
  readonly id: string;
  /** Function/class component. Wrap lazy or memo components in a function. */
  readonly component: ComponentType;
};

export type RegisteredWorkspaceAction = FrontendWorkspaceAction & {
  readonly moduleId: string;
  /** Changes on re-registration so React cannot retain the previous plugin's state. */
  readonly key: string;
};
