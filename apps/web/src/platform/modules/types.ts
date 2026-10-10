import type { ReactNode } from "react";
import type { MessageKey } from "../i18n/messages";

export type NavigationGroupId =
  | "overview"
  | "ai-management"
  | "user-management"
  | "content-management"
  | "operations"
  | "administration";

/** A route and its page renderer belong to the same frontend module. */
export type FrontendRoute = {
  readonly id: string;
  readonly label: string;
  /** Optional wording used inside a route tab; sidebar wording remains `label`. */
  readonly tabLabel?: MessageKey;
  readonly permission: string | null;
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
  readonly readOnlyPreview?: boolean;
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
};
