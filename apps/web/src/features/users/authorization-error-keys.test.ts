import assert from "node:assert/strict";
import test from "node:test";
import { ApiError } from "../../platform/api/client.ts";
import { messageKeyFor } from "../../platform/api/errors.ts";
import { deleteErrorKeys } from "../../platform/components/delete-errors.ts";
import { messages } from "../../platform/i18n/messages/zh-cn.ts";
import { importRowErrorKeys, importTaskErrorKeys } from "./user-import-errors.ts";
import { userErrorKeys } from "./user-errors.ts";
import { roleAssignmentCheckboxDisabled } from "./user-role-selection.ts";

test("user role assignment shows delegation errors instead of a generic denial", () => {
  for (const code of [
    "rolePermissionUnavailable",
    "rolePermissionDelegationDenied",
    "rolePermissionRevocationDenied",
  ] as const) {
    const key = messageKeyFor(new ApiError(403, code), userErrorKeys);
    assert.equal(key, code);
    assert.ok(messages[key].length > 0);
    assert.doesNotMatch(messages[key], /令牌.*过期/);
  }
});

test("import preview and commit map live role delegation errors", () => {
  for (const code of [
    "rolePermissionDelegationDenied",
    "rolePermissionUnavailable",
    "rolePermissionRevocationDenied",
  ] as const) {
    assert.equal(importRowErrorKeys[code], code);
    assert.equal(importTaskErrorKeys[code], code);
    assert.equal(messages[code].length > 0, true);
  }
  assert.equal("invalidRoleAssignment" in importTaskErrorKeys, false);
  assert.equal("invalidRoleAssignment" in importRowErrorKeys, false);
  assert.equal(importRowErrorKeys.batchRolledBack, "userImportRowBatchRolledBack");
  assert.equal(importTaskErrorKeys.batchRolledBack, "userImportRowBatchRolledBack");
  assert.match(messages.userImportRowBatchRolledBack, /回滚/);
});

test("an already assigned role can be cleared when it is no longer delegable", () => {
  const base = { pending: false, committed: false, canAssignRoles: true, assignable: false };
  assert.equal(roleAssignmentCheckboxDisabled({ ...base, selected: true }), false);
  assert.equal(roleAssignmentCheckboxDisabled({ ...base, selected: false }), true);
  assert.equal(roleAssignmentCheckboxDisabled({ ...base, selected: true, canAssignRoles: false }), true);
});

test("role deletion explains that plugin installation cannot replace revocation", () => {
  const key = messageKeyFor(new ApiError(403, "rolePermissionRevocationDenied"), deleteErrorKeys);
  assert.equal(key, "rolePermissionRevocationDenied");
  assert.match(messages[key], /不能代替/);
  assert.doesNotMatch(messages[key], /令牌.*过期/);
});
