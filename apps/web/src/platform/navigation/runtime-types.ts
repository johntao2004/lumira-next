/** Server-owned navigation data shared by the API adapter and shell merger. */
export type RuntimeNavigationNode = {
  readonly id?: string;
  readonly parentId?: string | null;
  readonly menuCode?: string;
  readonly menuType?: "CATALOG" | "PAGE" | "ACTION";
  readonly sectionKey: string | null;
  readonly path?: string | null;
  readonly componentKey?: string | null;
  readonly icon?: string | null;
  readonly moduleId?: string | null;
  readonly labelKey?: string | null;
  readonly labelDefault?: string | null;
  readonly children: readonly RuntimeNavigationNode[];
};
