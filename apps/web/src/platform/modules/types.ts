import type { ReactNode } from "react";
import type { MessageKey } from "../i18n/messages.ts";

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
  readonly label: MessageKey;
  readonly permission: string | null;
  readonly global?: boolean;
  readonly group: NavigationGroupId;
  readonly order: number;
};

export type FrontendPageContext = {
  readonly authorities: string[];
  readonly hasGlobal: (permission: string) => boolean;
};

export type FrontendPageRenderer = (context: FrontendPageContext) => ReactNode;

export type FrontendModuleManifest = {
  readonly id: string;
  readonly version: string;
  readonly required: boolean;
  readonly dependencies: readonly string[];
  readonly routes: readonly FrontendRoute[];
};

export type FrontendModule = FrontendModuleManifest & {
  readonly pages: Readonly<Record<string, FrontendPageRenderer>>;
};
