import assert from "node:assert/strict";
import test from "node:test";
import { automaticImportMapping, normalizeImportHeader } from "./user-import-mapping.ts";
import type { ImportField } from "../../platform/templates/types.ts";

const fields: ImportField[] = [
  { key: "username", label: "Username", type: "TEXT", source: "account", writable: true },
  { key: "displayName", label: "Display name", type: "TEXT", source: "account", writable: true },
  { key: "roles", label: "Role codes", type: "TEXT", source: "roles", writable: true },
];
const labels: Record<string, string> = { username: "用户名", displayName: "名称", roles: "角色编码" };
const visible = (field: ImportField) => labels[field.key] ?? field.label;
const columns = (...names: string[]) => names.map((name, index) => ({ name, index }));

test("exact field keys, API labels and current locale labels all map", () => {
  assert.deepEqual(automaticImportMapping(columns("username", "名称", "Role codes"), fields, visible), {
    username: "username", 名称: "displayName", "Role codes": "roles",
  });
  assert.deepEqual(automaticImportMapping(columns("username", "displayName", "roles"), fields, visible), {
    username: "username", displayName: "displayName", roles: "roles",
  });
});

test("header normalization handles BOM, spacing, case and separators", () => {
  assert.equal(normalizeImportHeader("\uFEFF Display_Name（ ） "), "displayname");
  assert.deepEqual(automaticImportMapping(columns("\uFEFF USER_NAME ", "display-name", "角色编码"), fields, visible), {
    "\uFEFF USER_NAME ": "username", "display-name": "displayName", 角色编码: "roles",
  });
});

test("an exact field key wins over another field's matching display label", () => {
  const ambiguous = [{ ...fields[1], label: "username" }, fields[0]];
  assert.deepEqual(automaticImportMapping(columns("username"), ambiguous, (field) => field.label), { username: "username" });
});

test("duplicate candidates never map the same field twice and unknown or empty headers stay unmapped", () => {
  assert.deepEqual(automaticImportMapping(columns("username", "Username", "unrecognized", "", "___", "Display name"), fields, visible), {
    username: "username", "Display name": "displayName",
  });
});
