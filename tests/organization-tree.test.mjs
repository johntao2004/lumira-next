import test from "node:test";
import assert from "node:assert/strict";
import {
  buildOrganizationTree,
  filterOrganizationTree,
  paginateOrganizationTree,
} from "../apps/web/src/features/organizations/organization-tree.ts";

const organization = (id, parentId, code, sortNo, name = code) => ({
  id,
  parentId,
  code,
  name,
  sortNo,
  enabled: true,
  version: 1,
});

const fixture = [
  organization("root-later", null, "ROOT_Z", 7),
  organization("child-late", "root-later", "CHILD_Z", 8),
  organization("leaf-match", "child-late", "LEAF", 0, "Target leaf"),
  organization("child-first", "root-later", "CHILD_A", 1),
  organization("root-alpha", null, "ROOT_A", 2),
  organization("root-beta", null, "ROOT_B", 2),
  organization("root-first", null, "ROOT_1", 1),
];

test("sorts roots and each sibling group by sortNo while keeping descendants under their parent", () => {
  const tree = buildOrganizationTree(fixture);

  assert.deepEqual(tree.map(node => node.id), ["root-first", "root-alpha", "root-beta", "root-later"]);
  assert.deepEqual(tree[2].children, undefined);
  assert.deepEqual(tree[3].children?.map(node => node.id), ["child-first", "child-late"]);
  assert.deepEqual(tree[3].children?.[1].children?.map(node => node.id), ["leaf-match"]);

  const firstPage = paginateOrganizationTree(tree, 1, 2);
  const secondPage = paginateOrganizationTree(tree, 2, 2);
  assert.deepEqual(firstPage.map(node => node.id), ["root-first", "root-alpha"]);
  assert.deepEqual(secondPage.map(node => node.id), ["root-beta", "root-later"]);
  assert.deepEqual(secondPage[1].children?.map(node => node.id), ["child-first", "child-late"]);
});

test("filters matching descendants with their ancestors and paginates the resulting sorted roots", () => {
  const tree = buildOrganizationTree(fixture);
  const filtered = filterOrganizationTree(tree, {
    code: "",
    name: "Target leaf",
    status: "",
    legacySearch: "",
  });

  assert.deepEqual(filtered.map(node => node.id), ["root-later"]);
  assert.deepEqual(filtered[0].children?.map(node => node.id), ["child-late"]);
  assert.deepEqual(filtered[0].children?.[0].children?.map(node => node.id), ["leaf-match"]);
  assert.deepEqual(paginateOrganizationTree(filtered, 1, 1).map(node => node.id), ["root-later"]);
});
