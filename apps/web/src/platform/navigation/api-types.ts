export interface NavigationMenu {
  id: string;
  parentId: string | null;
  menuCode: string;
  labelKey: string;
  labelDefault: string;
  menuType: "CATALOG" | "PAGE" | "ACTION";
  sectionKey: string | null;
  path: string | null;
  componentKey: string | null;
  icon: string | null;
  permissionKey: string | null;
  globalOnly: boolean;
  moduleId: string | null;
  sortOrder: number;
  enabled: boolean;
  builtIn: boolean;
  deleted: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}
