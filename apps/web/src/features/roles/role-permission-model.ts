/** 权限目录与选择模型，保持页面、动作及委派限制各自明确。
 * Permission catalog and selection model with explicit page, action and delegation boundaries.
 */
export type PermissionCatalogPermission = {
  code: string;
  label: string;
  /** 操作者能否变更该授权，含委派与预览限制。 Whether this actor may change the grant, including delegation and preview limits. */
  editable?: boolean;
  /** 已退役授权不生效，仅允许移除已有项。 Retired grants are inert and may only be removed when already present. */
  retired?: boolean;
  /** 默认注册角色能否保留该权限。 True when a default registration role may keep this permission. */
  registrationEligible?: boolean;
};

export type PermissionCatalogPage = {
  id?: string;
  path: string;
  title: string;
  navigationGroup?: string | null;
  navigationOrder?: number | null;
  accessPermission?: string | null;
  /** 页面所关联的动作权限代码，与页面访问权限分别处理。 Action permission codes associated with the page, handled separately from its access grant. */
  operationPermissions?: string[];
  mappingStatus?: "MAPPED" | "UNASSIGNED";
};

export type PermissionCatalogGroup = {
  id: string;
  title: string;
  source: "platform" | "plugin";
  pages: PermissionCatalogPage[];
  permissions: PermissionCatalogPermission[];
};

export type PermissionCatalogSearchResult = {
  group: PermissionCatalogGroup;
  pages: Array<{ page: PermissionCatalogPage; operations: PermissionCatalogPermission[] }>;
  unmappedPermissions: PermissionCatalogPermission[];
};

export type PermissionCatalogPageRow = {
  group: PermissionCatalogGroup;
  page: PermissionCatalogPage;
  operations: PermissionCatalogPermission[];
};

export type PermissionTreeNodeAction = { codes: string[] };

export type PermissionChange = {
  add?: string[];
  remove?: string[];
};

export type PermissionSearchLabels = {
  group: (group: PermissionCatalogGroup) => string;
  page: (page: PermissionCatalogPage) => string;
  permission: (permission: PermissionCatalogPermission) => string;
};

export type PageAccessState = "checked" | "unchecked" | "unassigned";

export function permissionTreeKey(value: string) {
  return encodeURIComponent(value);
}

export function permissionPageNodeKey(group: PermissionCatalogGroup, page: PermissionCatalogPage) {
  return `page:${permissionTreeKey(`${group.source}:${page.navigationGroup ?? ""}:${page.path}`)}`;
}

export function permissionOperationNodeKey(group: PermissionCatalogGroup, page: PermissionCatalogPage, code: string) {
  return `operation:${permissionTreeKey(`${group.source}:${page.navigationGroup ?? ""}:${group.id}:${page.path}:${code}`)}`;
}

/** 每个可勾选动作仅包含一个页面或动作代码。 Checkable tree actions contain one page code or one operation code each. */
export function permissionPageTreeActions(
  group: PermissionCatalogGroup,
  page: PermissionCatalogPage,
  operationCodes: string[],
): Array<[string, PermissionTreeNodeAction]> {
  const actions: Array<[string, PermissionTreeNodeAction]> = [];
  const accessCode = page.mappingStatus !== "UNASSIGNED" ? page.accessPermission : null;
  if (accessCode) actions.push([permissionPageNodeKey(group, page), { codes: [accessCode] }]);
  for (const code of operationCodes) {
    actions.push([permissionOperationNodeKey(group, page, code), { codes: [code] }]);
  }
  return actions;
}

export function permissionTreeNodeCheckable(
  kind: "root" | "group" | "page" | "operation" | "unmapped-permission",
  pageHasAccess = false,
) {
  return kind === "operation" || kind === "unmapped-permission" || (kind === "page" && pageHasAccess);
}

/** 页面访问与动作节点各按自身明确代码勾选。 Page access and operation nodes are checked by their own explicit codes. */
export function checkedPermissionTreeKeys(
  nodeActions: ReadonlyMap<string, PermissionTreeNodeAction>,
  authorities: string[],
) {
  return [...nodeActions]
    .filter(([, action]) => action.codes.some((code) => authorities.includes(code)))
    .map(([key]) => key);
}

export function pageAccessPermissionCodes(page: PermissionCatalogPage) {
  return page.accessPermission ? [page.accessPermission] : [];
}

export function pagePermissionCodes(page: PermissionCatalogPage) {
  return [...new Set([
    ...pageAccessPermissionCodes(page),
    ...(page.operationPermissions ?? []),
  ])];
}

export function permissionCatalogMap(groups: PermissionCatalogGroup[]) {
  return new Map(groups.flatMap((group) => group.permissions.map((permission) => [permission.code, { group, permission }] as const)));
}

export function canEditRolePermission(
  groups: PermissionCatalogGroup[],
  code: string,
  current: string[],
  actorAuthorities: string[],
  canManagePluginPermissions: boolean,
  adding: boolean,
  registrationOnly = false,
) {
  const entry = permissionCatalogMap(groups).get(code);
  if (!entry) return false;
  const { group, permission } = entry;
  const retired = permission.retired === true || group.id === "retired-plugin-permissions";
  if (retired) return !adding && current.includes(code);
  if (registrationOnly && permission.registrationEligible !== true) return !adding && current.includes(code);
  if (permission.editable === false) return false;
  if (permission.editable === true) return true;
  return actorAuthorities.includes(code) || (group.source === "plugin" && canManagePluginPermissions);
}

/** 保存会复核全部有效授权，含不可委派项时整棵树只读。 The save rechecks every active grant, so one undelegable grant makes the tree read-only. */
export function roleContainsUndelegableActiveGrant(
  groups: PermissionCatalogGroup[],
  authorities: readonly string[],
) {
  const held = new Set(authorities);
  return groups.some((group) => group.permissions.some((permission) => held.has(permission.code)
    && permission.retired !== true
    && group.id !== "retired-permissions"
    && permission.editable === false));
}

export function editableActivePermissionCodes(
  groups: PermissionCatalogGroup[],
  actorAuthorities: string[],
  canManagePluginPermissions = false,
  registrationOnly = false,
) {
  return [...new Set(groups
    .filter((group) => group.id !== "retired-plugin-permissions")
    .flatMap((group) => group.permissions.map(({ code }) => code)))]
    .filter((code) => canEditRolePermission(groups, code, [], actorAuthorities, canManagePluginPermissions, true, registrationOnly));
}

/** 批量变更使用完整有效目录，不受当前搜索可见行限制。 Bulk changes use the full active catalog and never depend on the visible search rows. */
export function updateAllEditableActiveRoleAuthorities(
  current: string[],
  groups: PermissionCatalogGroup[],
  actorAuthorities: string[],
  selected: boolean,
  canManagePluginPermissions = false,
  registrationOnly = false,
) {
  const activeCodes = editableActivePermissionCodes(groups, actorAuthorities, canManagePluginPermissions, registrationOnly);
  return updateRoleAuthorities(current, selected ? { add: activeCodes } : { remove: activeCodes }, groups, actorAuthorities, canManagePluginPermissions, registrationOnly);
}

/** 合并同一路由重复行，不推断共享权限行为。 Merge duplicate rows for one route without inventing shared permission behavior. */
export function mergePermissionPageRows(rows: PermissionCatalogPageRow[]): PermissionCatalogPageRow[] {
  const merged = new Map<string, PermissionCatalogPageRow>();
  for (const row of rows) {
    const key = `${row.group.source}\u001f${row.page.navigationGroup ?? ""}\u001f${row.page.id ?? row.page.path}`;
    const current = merged.get(key);
    if (!current) {
      merged.set(key, {
        ...row,
        page: {
          ...row.page,
          operationPermissions: [...new Set(row.page.operationPermissions ?? [])],
        },
        operations: [...new Map(row.operations.map((permission) => [permission.code, permission])).values()],
      });
      continue;
    }
    const group = current.group.source === "platform" || row.group.source !== "platform" ? current.group : row.group;
    const navigationOrderValues = [current.page.navigationOrder, row.page.navigationOrder]
      .filter((value): value is number => typeof value === "number");
    const accessPermission = current.page.accessPermission ?? row.page.accessPermission;
    const operationCodes = [...new Set([...(current.page.operationPermissions ?? []), ...(row.page.operationPermissions ?? [])])];
    const operations = [...new Map([...current.operations, ...row.operations].map((permission) => [permission.code, permission])).values()];
    merged.set(key, {
      group,
      page: {
        ...current.page,
        title: current.page.title || row.page.title,
        navigationOrder: navigationOrderValues.length > 0 ? Math.min(...navigationOrderValues) : undefined,
        mappingStatus: current.page.mappingStatus === "MAPPED" || row.page.mappingStatus === "MAPPED" ? "MAPPED" : "UNASSIGNED",
        accessPermission,
        operationPermissions: operationCodes,
      },
      operations,
    });
  }
  return [...merged.values()];
}

/** 只更新明确勾选的代码，不推断页面与动作依赖。 Update only the explicitly checked permission codes. No page/action dependency is inferred. */
export function updateRoleAuthorities(
  current: string[],
  change: PermissionChange,
  groups: PermissionCatalogGroup[],
  actorAuthorities: string[],
  canManagePluginPermissions = false,
  registrationOnly = false,
) {
  const next = new Set(current);

  for (const code of change.remove ?? []) {
    if (canEditRolePermission(groups, code, current, actorAuthorities, canManagePluginPermissions, false, registrationOnly)) next.delete(code);
  }
  for (const code of change.add ?? []) {
    if (current.includes(code)) continue;
    if (canEditRolePermission(groups, code, current, actorAuthorities, canManagePluginPermissions, true, registrationOnly)) next.add(code);
  }

  return [...next];
}

export function pageAccessState(page: PermissionCatalogPage, authorities: string[]): PageAccessState {
  const accessPermission = pageAccessPermissionCodes(page)[0];
  if (!accessPermission || page.mappingStatus === "UNASSIGNED") return "unassigned";
  return authorities.includes(accessPermission) ? "checked" : "unchecked";
}

export function filterPermissionCatalog(
  groups: PermissionCatalogGroup[],
  query: string,
  labels: PermissionSearchLabels,
): PermissionCatalogSearchResult[] {
  const searchText = query.trim().toLocaleLowerCase();
  const allPermissions = permissionCatalogMap(groups);
  const mappedCodes = new Set(groups.flatMap((group) => group.pages
    .filter((page) => page.mappingStatus !== "UNASSIGNED")
    .flatMap((page) => pagePermissionCodes(page))));
  const matches = (value: string) => !searchText || value.toLocaleLowerCase().includes(searchText);

  return groups.flatMap((group) => {
    const groupMatches = matches(`${group.id} ${group.title} ${labels.group(group)}`);
    const unmappedPermissions = (group.id === "retired-plugin-permissions"
      ? group.permissions
      : group.permissions.filter((permission) => !mappedCodes.has(permission.code)))
      .filter((permission) => groupMatches || matches(`${permission.code} ${permission.label} ${labels.permission(permission)}`));

    const pages = group.pages.flatMap((page) => {
      const accessCodes = pageAccessPermissionCodes(page);
      const operationCodes = page.operationPermissions ?? [];
      const pageMatches = groupMatches || matches(`${page.id ?? ""} ${page.path} ${page.title} ${labels.page(page)} ${page.navigationGroup ?? ""}`);
      const accessMatches = accessCodes.some((code) => {
        const permission = allPermissions.get(code)?.permission ?? { code, label: code };
        return matches(`${code} ${permission.label} ${labels.permission(permission)}`);
      });
      const matchingOperations = operationCodes.filter((code) => {
        const permission = allPermissions.get(code)?.permission ?? { code, label: code };
        return matches(`${code} ${permission.label} ${labels.permission(permission)}`);
      });
      if (!pageMatches && !accessMatches && matchingOperations.length === 0) return [];

      const showAllOperations = pageMatches || accessMatches;
      const visibleOperationCodes = showAllOperations ? operationCodes : matchingOperations;
      const operations = visibleOperationCodes.map((code) => allPermissions.get(code)?.permission ?? { code, label: code });
      return [{ page, operations }];
    });

    return pages.length > 0 || unmappedPermissions.length > 0 ? [{ group, pages, unmappedPermissions }] : [];
  });
}

/** 搜索展开键包含来源与导航组，目录变化时不会沿用旧分支签名。
 * Search expansion keys include source and navigation group so catalog changes cannot reuse an obsolete branch signature.
 */
export function permissionSearchExpansionKeys(results: PermissionCatalogSearchResult[]): string[] {
  const keys = new Set<string>();
  for (const { group, pages, unmappedPermissions } of results) {
    if (pages.length || unmappedPermissions.length) keys.add(`permission-root:${group.source}`);
    for (const { page } of pages) {
      keys.add(`domain:${group.source}:${permissionTreeKey(page.navigationGroup ?? "unassigned")}`);
      keys.add(permissionPageNodeKey(group, page));
    }
    if (unmappedPermissions.length) {
      keys.add(`unmapped-root:${group.source}`);
      keys.add(`unmapped-domain:${permissionTreeKey(`${group.source}:${group.id}`)}`);
    }
  }
  return [...keys];
}
