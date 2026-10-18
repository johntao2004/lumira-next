import test from "node:test";
import assert from "node:assert/strict";
import {
  canEditRolePermission,
  checkedPermissionTreeKeys,
  roleContainsUndelegableActiveGrant,
  filterPermissionCatalog,
  mergePermissionPageRows,
  permissionOperationNodeKey,
  permissionPageNodeKey,
  permissionPageTreeActions,
  permissionTreeNodeCheckable,
  pageAccessState,
  updateRoleAuthorities,
  updateAllEditableActiveRoleAuthorities,
  type PermissionCatalogGroup,
} from "./role-permission-model.ts";

const permission = (code: string, label = code, editable = true, retired = false) => ({ code, label, editable, retired });
const identityLabels = {
  group: (group: PermissionCatalogGroup) => group.title,
  page: (page: { title: string }) => page.title,
  permission: (value: { label: string }) => value.label,
};

function catalog(): PermissionCatalogGroup[] {
  return [{
    id: "users",
    title: "Users",
    source: "platform",
    pages: [{
      id: "users",
      path: "/dashboard/users",
      title: "userList",
      navigationGroup: "user-management",
      navigationOrder: 10,
      accessPermission: "platform:users:access",
      operationPermissions: ["platform:users:create", "platform:users:update"],
      mappingStatus: "MAPPED",
    }, {
      id: "ai-console",
      path: "/dashboard/ai-console",
      title: "aiConsole",
      navigationGroup: "ai-management",
      navigationOrder: 10,
      accessPermission: "platform:ai-console:access",
      operationPermissions: ["platform:ai-console:create-user"],
      mappingStatus: "MAPPED",
    }],
    permissions: [
      permission("platform:users:access", "permissionPageAccess"),
      permission("platform:users:create", "permissionCreate"),
      permission("platform:users:update", "permissionUpdate"),
      permission("platform:ai-console:access", "permissionPageAccess"),
      permission("platform:ai-console:create-user", "permissionCreate"),
      permission("platform:files:access", "permissionPageAccess", false),
    ],
  }, {
    id: "retired-plugin-permissions",
    title: "Retired permissions",
    source: "plugin",
    pages: [],
    permissions: [permission("plugin:legacy:manage", "plugin:legacy:manage", false, true)],
  }];
}

test("page access and each operation are independent persisted checkboxes", () => {
  const groups = catalog();
  const actor = groups[0]!.permissions.map(({ code }) => code);

  const accessOnly = updateRoleAuthorities([], { add: ["platform:users:access"] }, groups, actor);
  assert.deepEqual(accessOnly, ["platform:users:access"]);

  const actionOnly = updateRoleAuthorities([], { add: ["platform:users:create"] }, groups, actor);
  assert.deepEqual(actionOnly, ["platform:users:create"]);

  assert.deepEqual(updateRoleAuthorities(
    ["platform:users:access", "platform:users:create"],
    { remove: ["platform:users:access"] },
    groups,
    actor,
  ), ["platform:users:create"]);

  assert.deepEqual(updateRoleAuthorities(
    ["platform:users:access", "platform:users:create"],
    { remove: ["platform:users:create"] },
    groups,
    actor,
  ), ["platform:users:access"]);
});

test("AI user actions use their own page codes instead of user CRUD grants", () => {
  const groups = catalog().map((group) => ({
    ...group,
    permissions: group.permissions.map((entry) => entry.code === "platform:users:create"
      ? { ...entry, editable: false }
      : entry),
  }));
  const actor = ["platform:ai-console:access", "platform:ai-console:create-user"];
  assert.deepEqual(updateRoleAuthorities([], { add: ["platform:ai-console:create-user"] }, groups, actor), ["platform:ai-console:create-user"]);
  assert.equal(updateRoleAuthorities([], { add: ["platform:users:create"] }, groups, actor).length, 0);
});

test("delegation caps win over actor grants for noneditable permissions", () => {
  const groups = catalog().map((group) => ({
    ...group,
    permissions: group.permissions.map((entry) => entry.code === "platform:users:create"
      ? { ...entry, editable: false }
      : entry),
  }));
  const actor = ["platform:users:access", "platform:users:create"];
  assert.deepEqual(updateRoleAuthorities([], { add: ["platform:users:create"] }, groups, actor), []);
});

test("noneditable, unknown, and absent retired grants are preserved but cannot be added", () => {
  const groups = catalog();
  const current = ["platform:users:access", "platform:files:access", "platform:unknown:grant", "plugin:legacy:manage"];
  const after = updateRoleAuthorities(
    current,
    { add: ["platform:files:access", "platform:unknown:grant", "plugin:legacy:manage"], remove: current },
    groups,
    ["platform:users:access"],
  );

  assert.deepEqual(after, ["platform:files:access", "platform:unknown:grant"]);
  assert.deepEqual(updateRoleAuthorities([], { add: ["plugin:legacy:manage"] }, groups, ["plugin:legacy:manage"]), []);
});

test("search filters visible rows while toggling one action preserves hidden grants", () => {
  const groups = catalog();
  const results = filterPermissionCatalog(groups, "userList", identityLabels);
  assert.deepEqual(results[0]?.pages.map(({ page }) => page.id), ["users"]);

  const current = ["platform:ai-console:access", "platform:ai-console:create-user", "platform:users:access"];
  const after = updateRoleAuthorities(current, { remove: ["platform:users:access"] }, groups, [
    "platform:ai-console:access", "platform:ai-console:create-user", "platform:users:access",
  ]);
  assert.deepEqual(after, ["platform:ai-console:access", "platform:ai-console:create-user"]);
});

test("page search matches access and operation labels without deriving read dependencies", () => {
  const groups = catalog();
  assert.deepEqual(filterPermissionCatalog(groups, "create-user", identityLabels)[0]?.pages.map(({ page }) => page.id), ["ai-console"]);
  assert.deepEqual(filterPermissionCatalog(groups, "permissionPageAccess", identityLabels)[0]?.pages.map(({ page }) => page.id), ["users", "ai-console"]);
  assert.equal(pageAccessState(groups[0]!.pages[0]!, ["platform:users:access"]), "checked");
  assert.equal(pageAccessState(groups[0]!.pages[0]!, []), "unchecked");
  assert.equal(pageAccessState({ ...groups[0]!.pages[0]!, accessPermission: null }, []), "unassigned");
});

test("searched operation pages keep one independently checked page row and checkable leaves only", () => {
  const groups = catalog();
  const group = groups[0]!;
  const page = group.pages[0]!;
  const searchedPage = filterPermissionCatalog(groups, "platform:users:create", identityLabels)[0]?.pages[0];
  assert.equal(searchedPage?.page.path, "/dashboard/users");
  assert.deepEqual(searchedPage?.operations.map(({ code }) => code), ["platform:users:create"]);

  const pageKey = permissionPageNodeKey(group, page);
  const operationKey = permissionOperationNodeKey(group, page, "platform:users:create");
  const actions = new Map(permissionPageTreeActions(group, searchedPage!.page, searchedPage.operations.map(({ code }) => code)));
  assert.deepEqual([...actions.keys()], [pageKey, operationKey]);
  assert.deepEqual(actions.get(pageKey)?.codes, ["platform:users:access"]);
  assert.deepEqual(actions.get(operationKey)?.codes, ["platform:users:create"]);
  assert.equal(permissionTreeNodeCheckable("root"), false);
  assert.equal(permissionTreeNodeCheckable("group"), false);
  assert.equal(permissionTreeNodeCheckable("page", true), true);
  assert.equal(permissionTreeNodeCheckable("page", false), false);
  assert.equal(permissionTreeNodeCheckable("operation"), true);
  assert.deepEqual(checkedPermissionTreeKeys(actions, ["platform:users:access"]), [pageKey]);
  assert.deepEqual(checkedPermissionTreeKeys(actions, ["platform:users:create"]), [operationKey]);
  assert.ok([...actions.keys()].every((key) => !key.startsWith("root:") && !key.startsWith("domain:")));
});

test("duplicate route rows merge explicit metadata only", () => {
  const groups = catalog();
  const group = groups[0]!;
  const page = group.pages[0]!;
  const rows = mergePermissionPageRows([
    { group, page, operations: [group.permissions[1]!] },
    { group, page: { ...page, operationPermissions: ["platform:users:update"] }, operations: [group.permissions[2]!] },
  ]);

  assert.equal(rows.length, 1);
  assert.equal(rows[0]?.page.accessPermission, "platform:users:access");
  assert.deepEqual(rows[0]?.page.operationPermissions, ["platform:users:create", "platform:users:update"]);
  assert.deepEqual(rows[0]?.operations.map(({ code }) => code), ["platform:users:create", "platform:users:update"]);
});

test("bulk select and clear span the full catalog and preserve uneditable and unknown grants", () => {
  const groups = catalog();
  const selected = updateAllEditableActiveRoleAuthorities(
    ["platform:unknown:grant", "platform:files:access", "plugin:legacy:manage"],
    groups,
    ["platform:users:access"],
    true,
  );

  assert.ok(selected.includes("platform:users:access"));
  assert.ok(selected.includes("platform:users:create"));
  assert.ok(selected.includes("platform:ai-console:access"));
  assert.ok(selected.includes("platform:unknown:grant"));
  assert.ok(selected.includes("platform:files:access"));
  assert.ok(selected.includes("plugin:legacy:manage"));
  assert.ok(!updateAllEditableActiveRoleAuthorities([], groups, ["platform:users:access"], true).includes("plugin:legacy:manage"));

  const cleared = updateAllEditableActiveRoleAuthorities(selected, groups, ["platform:users:access"], false);
  assert.deepEqual(cleared, ["platform:unknown:grant", "platform:files:access", "plugin:legacy:manage"]);
});

test("a role that already contains an undelegable active grant is read-only", () => {
  const groups = catalog().map((group) => ({
    ...group,
    permissions: group.permissions.map((entry) => entry.code === "platform:users:update" ? { ...entry, editable: false } : entry),
  }));
  assert.equal(roleContainsUndelegableActiveGrant(groups, ["platform:users:update"]), true);
  assert.equal(roleContainsUndelegableActiveGrant(groups, ["platform:users:access"]), false);
  assert.equal(roleContainsUndelegableActiveGrant([{
    id: "retired-permissions",
    title: "retired",
    source: "platform",
    pages: [],
    permissions: [{ code: "platform:retired:read", label: "retired", editable: false, retired: true }],
  }], ["platform:retired:read"]), false);
});

test("the default registration role cannot gain permissions outside its allow-list", () => {
  const groups: PermissionCatalogGroup[] = [{
    id: "feedback",
    title: "feedback",
    source: "plugin",
    pages: [],
    permissions: [
      { code: "platform:feedback:access", label: "access", editable: true, registrationEligible: true },
      { code: "platform:feedback:reply", label: "reply", editable: true, registrationEligible: false },
    ],
  }];
  assert.equal(canEditRolePermission(groups, "platform:feedback:reply", [], [], false, true, true), false);
  assert.equal(canEditRolePermission(groups, "platform:feedback:reply", ["platform:feedback:reply"], [], false, false, true), true);
  assert.equal(canEditRolePermission(groups, "platform:feedback:access", [], [], false, true, true), true);
});
