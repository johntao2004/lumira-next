"use client";

/** 展示并编辑权限树，保持显式授权与搜索展开相互独立。
 * Present and edit the permission tree while keeping explicit grants separate from search expansion.
 */

import { useEffect, useRef, useState } from "react";
import { Button, Card, Input, Space, Tag, Tooltip, Tree, Typography } from "antd";
import type { DataNode } from "antd/es/tree";
import { navigationGroups } from "@/platform/shell/routes";
import {
  canEditRolePermission,
  checkedPermissionTreeKeys,
  filterPermissionCatalog,
  permissionSearchExpansionKeys,
  editableActivePermissionCodes,
  mergePermissionPageRows,
  permissionOperationNodeKey,
  permissionPageNodeKey,
  permissionPageTreeActions,
  permissionTreeNodeCheckable,
  pageAccessPermissionCodes,
  pageAccessState,
  permissionCatalogMap,
  permissionTreeKey,
  updateRoleAuthorities,
  updateAllEditableActiveRoleAuthorities,
  type PermissionCatalogGroup,
  type PermissionCatalogPage,
  type PermissionTreeNodeAction,
} from "./role-permission-model";
import "./role-permission-picker.css";

type PermissionTreeNode = DataNode & { children?: PermissionTreeNode[] };
type PermissionPickerProps = {
  value?: string[];
  onChange?: (value: string[]) => void;
  groups: PermissionCatalogGroup[];
  actorAuthorities: string[];
  canEdit: boolean;
  canManagePluginPermissions: boolean;
  registrationOnly?: boolean;
  t: (key: string, variables?: Record<string, string | number>) => string;
};

const permissionRootKeys = {
  platform: "permission-root:platform",
  plugin: "permission-root:plugin",
  retired: "permission-root:retired",
} as const;

function permissionLabel(label: string, code: string, t: PermissionPickerProps["t"]) {
  if (label === "update") return t(code === "platform:modules:update" ? "permissionUpdateModule" : "permissionUpdate");
  return t(label);
}

function permissionGroupTitle(group: PermissionCatalogGroup, t: PermissionPickerProps["t"]) {
  return t(group.source === "platform" && group.id === "settings" ? "systemSettings" : group.title);
}

function pageTitle(page: PermissionCatalogPage, t: PermissionPickerProps["t"]) {
  return t(page.title);
}

function rootTreeKey(scope: "platform" | "plugin" | "retired") {
  return permissionRootKeys[scope];
}

function navigationGroupTitle(groupId: string | null | undefined, t: PermissionPickerProps["t"]) {
  const group = navigationGroups.find((candidate) => candidate.id === groupId);
  return group ? t(group.label) : t("permissionAccessUnmapped");
}

function orphanNodeKey(group: PermissionCatalogGroup, code: string) {
  return `unmapped:${permissionTreeKey(`${group.source}:${group.id}:${code}`)}`;
}

function unknownNodeKey(code: string) {
  return `unknown:${permissionTreeKey(code)}`;
}

export function RolePermissionPicker({
  value = [], onChange, groups, actorAuthorities, canEdit, canManagePluginPermissions, registrationOnly = false, t,
}: PermissionPickerProps) {
  const [search, setSearch] = useState("");
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const initializedExpandedKeys = useRef(false);
  const catalogCodes = new Set(groups.flatMap((group) => group.permissions.map(({ code }) => code)));
  const permissionMap = permissionCatalogMap(groups);
  const searchResults = filterPermissionCatalog(groups, search, {
    group: (group) => permissionGroupTitle(group, t),
    page: (page) => `${pageTitle(page, t)} ${navigationGroupTitle(page.navigationGroup, t)}`,
    permission: (permission) => permissionLabel(permission.label, permission.code, t),
  });
  const searchText = search.trim().toLocaleLowerCase();
  const navigationOrder = new Map<string, number>(navigationGroups.map((group, index) => [group.id, index]));
  const nodeActions = new Map<string, PermissionTreeNodeAction>();
  const allPages = mergePermissionPageRows(groups.flatMap((group) => group.pages.map((page) => ({
    group,
    page,
    operations: (page.operationPermissions ?? []).map((code) => permissionMap.get(code)?.permission ?? { code, label: code }),
  }))));
  const unknownCodes = value.filter((code) => !catalogCodes.has(code));
  const treeData: PermissionTreeNode[] = [];

  for (const scope of ["platform", "plugin"] as const) {
    const scopeResults = searchResults.filter(({ group }) => group.source === scope && group.id !== "retired-plugin-permissions");
    const pageRows = mergePermissionPageRows(scopeResults.flatMap(({ group, pages }) => pages.map(({ page, operations }) => ({ group, page, operations }))));
    const navigationDomains = [...new Set(pageRows.map(({ page }) => page.navigationGroup ?? ""))]
      .sort((left, right) => (navigationOrder.get(left) ?? Number.MAX_SAFE_INTEGER) - (navigationOrder.get(right) ?? Number.MAX_SAFE_INTEGER)
        || left.localeCompare(right));
    const domainNodes: PermissionTreeNode[] = navigationDomains.flatMap((navigationGroup) => {
      const domainRows = pageRows.filter(({ page }) => (page.navigationGroup ?? "") === navigationGroup);
      const pages = [...domainRows].sort((left, right) => (left.page.navigationOrder ?? Number.MAX_SAFE_INTEGER) - (right.page.navigationOrder ?? Number.MAX_SAFE_INTEGER)
        || left.page.path.localeCompare(right.page.path));
      const pageNodes = pages.map(({ group, page, operations }) => {
        const key = permissionPageNodeKey(group, page);
        const accessCode = pageAccessPermissionCodes(page)[0];
        const mappedPage = page.mappingStatus !== "UNASSIGNED" && !!accessCode;
        for (const [actionKey, action] of permissionPageTreeActions(group, page, operations.map(({ code }) => code))) {
          nodeActions.set(actionKey, action);
        }
        const canEditAccess = mappedPage && canEdit && canEditRolePermission(
          groups,
          accessCode,
          value,
          actorAuthorities,
          canManagePluginPermissions,
          !value.includes(accessCode),
          registrationOnly,
        );

        const operationNodes: PermissionTreeNode[] = operations.map(({ code, label }) => {
          const operationKey = permissionOperationNodeKey(group, page, code);
          const canEditOperation = canEdit && canEditRolePermission(groups, code, value, actorAuthorities, canManagePluginPermissions, !value.includes(code), registrationOnly);
          const pageAccessGranted = !!accessCode && value.includes(accessCode);
          const actionUnavailable = value.includes(code) && !pageAccessGranted;
          return {
            key: operationKey,
            checkable: permissionTreeNodeCheckable("operation"),
            disableCheckbox: !canEditOperation,
            title: <Tooltip title={<Space direction="vertical" size={0} className="role-permission-picker__tooltip">
              <span>{code}</span>
              {actionUnavailable && <span>{t("permissionActionUnavailable")}</span>}
              {code === "platform:mfa:management-reset" && <span>{t("permissionMfaResetScope")}</span>}
            </Space>}>
              <span className="role-permission-picker__operation-row">
                <span className="role-permission-picker__operation" data-permission-code={code} data-permission-operation="true">
                  {permissionLabel(label, code, t)}
                </span>
                {actionUnavailable && <Typography.Text type="warning" className="role-permission-picker__unavailable" data-permission-unavailable="true">
                  {t("permissionActionUnavailable")}
                </Typography.Text>}
              </span>
            </Tooltip>,
          };
        });

        return {
          key,
          checkable: permissionTreeNodeCheckable("page", mappedPage),
          disableCheckbox: !canEditAccess,
          title: <Tooltip title={<Space direction="vertical" size={0} className="role-permission-picker__tooltip">
            <span>{page.path}</span>
            {accessCode && <span>{accessCode}</span>}
          </Space>}>
            <span
              className="role-permission-picker__page-title"
              data-permission-page={page.path}
              data-permission-access-code={mappedPage ? accessCode : undefined}
            >
              <span>{pageTitle(page, t)}</span>
              {!mappedPage && <Tag>{t("permissionAccessUnmapped")}</Tag>}
            </span>
          </Tooltip>,
          children: operationNodes.length > 0 ? operationNodes : undefined,
        };
      });

      const domainKey = `domain:${scope}:${permissionTreeKey(navigationGroup || "unassigned")}`;
      return [{
        key: domainKey,
        checkable: permissionTreeNodeCheckable("group"),
        title: <span className="role-permission-picker__domain" data-permission-navigation-group={navigationGroup || "unassigned"}>
          {navigationGroupTitle(navigationGroup, t)}
        </span>,
        children: pageNodes,
      }];
    });

    const unmappedGroups = scopeResults.flatMap(({ group, unmappedPermissions }) => unmappedPermissions.length > 0
      ? [{ group, permissions: unmappedPermissions }]
      : []);
    const unmappedNodes: PermissionTreeNode[] = unmappedGroups.map(({ group, permissions: groupPermissions }) => ({
      key: `unmapped-domain:${permissionTreeKey(`${group.source}:${group.id}`)}`,
      checkable: permissionTreeNodeCheckable("group"),
      title: <span className="role-permission-picker__domain">{permissionGroupTitle(group, t)}</span>,
      children: groupPermissions.map(({ code, label }) => {
        const key = orphanNodeKey(group, code);
        const canEditPermission = canEdit && canEditRolePermission(groups, code, value, actorAuthorities, canManagePluginPermissions, !value.includes(code), registrationOnly);
        nodeActions.set(key, { codes: [code] });
        return {
          key,
          checkable: permissionTreeNodeCheckable("unmapped-permission"),
          disableCheckbox: !canEditPermission,
          title: <Tooltip title={<span>{code}</span>}>
            <span className="role-permission-picker__operation" data-permission-code={code}>
              {permissionLabel(label, code, t)}
            </span>
          </Tooltip>,
        };
      }),
    }));

    const hasNodes = domainNodes.length > 0 || unmappedNodes.length > 0;
    if (!hasNodes) continue;
    treeData.push({
      key: rootTreeKey(scope),
      checkable: permissionTreeNodeCheckable("root"),
      title: <span className="role-permission-picker__root" data-permission-scope={scope}>{t(scope === "platform" ? "permissionPlatformRoot" : "permissionPluginRoot")}</span>,
      children: [
        ...domainNodes,
        ...(unmappedNodes.length > 0 ? [{
          key: `unmapped-root:${scope}`,
          checkable: permissionTreeNodeCheckable("group"),
          title: <span className="role-permission-picker__domain">{t("permissionUnmappedPermissions")}</span>,
          children: unmappedNodes,
        }] : []),
      ],
    });
  }

  const retiredGroupNodes: PermissionTreeNode[] = searchResults.filter(({ group }) => group.id === "retired-plugin-permissions")
    .map(({ group, unmappedPermissions }) => ({
      key: `retired-domain:${permissionTreeKey(group.id)}`,
      checkable: permissionTreeNodeCheckable("group"),
      title: <span className="role-permission-picker__domain">{permissionGroupTitle(group, t)}</span>,
      children: unmappedPermissions.map(({ code, label }) => {
        const key = orphanNodeKey(group, code);
        const canRemove = canEdit && value.includes(code);
        nodeActions.set(key, { codes: [code] });
        return {
          key,
          checkable: permissionTreeNodeCheckable("unmapped-permission"),
          disableCheckbox: !canRemove,
          title: <Tooltip title={<span>{code}</span>}>
            <span className="role-permission-picker__operation" data-permission-code={code}>
              {permissionLabel(label, code, t)}
            </span>
          </Tooltip>,
        };
      }),
    }));
  const visibleUnknownCodes = unknownCodes.filter((code) => !searchText || code.toLocaleLowerCase().includes(searchText)
    || t("permissionUnknown").toLocaleLowerCase().includes(searchText));
  if (retiredGroupNodes.length > 0 || visibleUnknownCodes.length > 0) {
    treeData.push({
      key: rootTreeKey("retired"),
      checkable: permissionTreeNodeCheckable("root"),
      title: <span className="role-permission-picker__root" data-permission-scope="retired">{t("permissionRetired")}</span>,
      children: [
        ...retiredGroupNodes,
        ...(visibleUnknownCodes.length > 0 ? [{
          key: "unknown-permission-domain",
          checkable: permissionTreeNodeCheckable("group"),
          title: <span className="role-permission-picker__domain">{t("permissionUnknown")}</span>,
          children: visibleUnknownCodes.map((code) => {
            const key = unknownNodeKey(code);
            return {
              key,
              checkable: permissionTreeNodeCheckable("unmapped-permission"),
              disableCheckbox: true,
              title: <Tooltip title={<span>{code}</span>}>
                <span className="role-permission-picker__operation" data-permission-code={code}>{code}</span>
              </Tooltip>,
            };
          }),
        }] : []),
      ],
    });
  }

  const groupSignature = allPages.map(({ group, page }) => `${group.source}:${page.path}:${page.navigationGroup ?? ""}`).join("\u001f")
    + groups.map((group) => `${group.source}:${group.id}:${group.permissions.map(({ code }) => code).join("|")}`).join("\u001e");
  useEffect(() => {
    if (initializedExpandedKeys.current || groups.length === 0) return;
    const selectedPages = allPages.filter(({ page }) => pageAccessState(page, value) === "checked"
      || (page.operationPermissions ?? []).some((code) => value.includes(code)));
    const fallbackPage = allPages.find(({ page }) => page.path === "/dashboard/users")
      ?? allPages.find(({ page }) => pageAccessPermissionCodes(page).length)
      ?? allPages[0];
    const defaultPage = selectedPages.length > 0
      ? selectedPages.slice(0, 3)
      : fallbackPage ? [fallbackPage] : [];
    const defaultScopes = [...new Set(defaultPage.map(({ group }) => rootTreeKey(group.source)))];
    const defaultDomains = [...new Set(defaultPage.map(({ group, page }) => `domain:${group.source}:${permissionTreeKey(page.navigationGroup ?? "unassigned")}`))];
    const defaultPages = defaultPage.map(({ group, page }) => permissionPageNodeKey(group, page));
    const roots = treeData.map((node) => String(node.key));
    setExpandedKeys([...new Set([...roots, ...defaultScopes, ...defaultDomains, ...defaultPages])]);
    initializedExpandedKeys.current = true;
  }, [groupSignature]);

  // 按完整展开键触发，避免新数组循环更新或漏掉导航组变化。
  // Depend on complete expansion keys to avoid array-driven loops and missed navigation-group changes.
  const searchExpansionKey = JSON.stringify(permissionSearchExpansionKeys(searchResults));
  useEffect(() => {
    if (!searchText) return;
    const keys: string[] = JSON.parse(searchExpansionKey);
    setExpandedKeys((current) => [...new Set([...current, ...keys])]);
  }, [searchText, searchExpansionKey]);

  const checkedKeys = checkedPermissionTreeKeys(nodeActions, value);
  const unknownCheckedKeys = visibleUnknownCodes.map(unknownNodeKey);
  const disabledTree = !canEdit;
  const bulkCodes = editableActivePermissionCodes(groups, actorAuthorities, canManagePluginPermissions, registrationOnly);
  const bulkAllSelected = bulkCodes.length > 0 && bulkCodes.every((code) => value.includes(code));
  const bulkClearHasChanges = bulkCodes.some((code) => value.includes(code));

  const updateBulk = (selected: boolean) => {
    const next = updateAllEditableActiveRoleAuthorities(
      value,
      groups,
      actorAuthorities,
      selected,
      canManagePluginPermissions,
      registrationOnly,
    );
    onChange?.(next);
  };

  return <Card size="small" className="role-permission-picker" styles={{ body: { padding: 12 } }}>
    <Space wrap className="role-permission-picker__bulk-actions">
      <Button size="small" disabled={disabledTree || bulkCodes.length === 0 || bulkAllSelected} onClick={() => updateBulk(true)}>{t("permissionSelectAllActive")}</Button>
      <Button size="small" disabled={disabledTree || bulkCodes.length === 0 || !bulkClearHasChanges} onClick={() => updateBulk(false)}>{t("permissionClearActive")}</Button>
    </Space>
    <Typography.Text type="secondary" className="role-permission-picker__bulk-hint">{t("permissionBulkScopeHint")}</Typography.Text>
    <Input
      allowClear
      size="small"
      aria-label={t("permissionSearch")}
      data-permission-search="true"
      placeholder={t("permissionSearch")}
      value={search}
      onChange={(event) => setSearch(event.target.value)}
    />
    <Typography.Text type="secondary" className="role-permission-picker__hint">{t("permissionPageTreeSearchHint")}</Typography.Text>
    {treeData.length > 0 ? <Tree
      aria-label={t("permissions")}
      checkable
      checkStrictly
      blockNode
      selectable={false}
      className="role-permission-tree"
      expandedKeys={expandedKeys}
      checkedKeys={{ checked: [...new Set([...checkedKeys, ...unknownCheckedKeys])], halfChecked: [] }}
      treeData={treeData}
      style={{ width: "100%", minWidth: 0, overflowX: "hidden" }}
      onExpand={(keys) => setExpandedKeys(keys.map(String))}
      onCheck={(_, info) => {
        const action = nodeActions.get(String(info.node.key));
        if (!action || disabledTree) return;
        const checked = info.checked;
        const next = updateRoleAuthorities(value, checked
          ? { add: action.codes }
          : { remove: action.codes }, groups, actorAuthorities, canManagePluginPermissions, registrationOnly);
        onChange?.(next);
      }}
    /> : <Typography.Text type="secondary" className="role-permission-picker__empty">{t("permissionNoMatches")}</Typography.Text>}
  </Card>;
}
