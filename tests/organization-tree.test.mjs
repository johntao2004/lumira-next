import test from "node:test";
import assert from "node:assert/strict";
import {
  buildOrganizationTree,
  collectExpandableOrganizationKeys,
  filterOrganizationTree,
  paginateOrganizationTree,
} from "../plugins/directory/frontend/organizations/organization-tree.ts";

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


test("build, filter and expansion handle a 20000-level tree without recursive stack growth", () => {
  const count = 20000;
  const input = Array.from({ length: count }, (_, i) => organization(String(i), i ? String(i - 1) : null, "ORG" + i, 0));
  const tree = buildOrganizationTree(input);
  const filters = { code: "", name: "ORG19999", status: "", legacySearch: "" };
  const filtered = filterOrganizationTree(tree, filters);
  let actual = filtered[0], levels = 0;
  while (actual) { levels++; actual = actual.children?.[0]; }
  assert.equal(levels, count);
  const expanded = collectExpandableOrganizationKeys(filtered);
  assert.equal(expanded.length, count - 1);
  assert.equal(expanded[0], "0");
  assert.equal(expanded.at(-1), "19998");
  assert.equal(input.every(item => !Object.hasOwn(item, "children")), true);
  assert.notEqual(filtered[0], tree[0]);
  assert.equal(filterOrganizationTree(tree, { code: "", name: "", status: "", legacySearch: "" }), tree);
});

test("filter keeps stable sibling order and ancestors without changing the source tree", () => {
  const tree = buildOrganizationTree(fixture);
  const before = JSON.stringify(tree);
  const filtered = filterOrganizationTree(tree, { code: "child", name: "", status: "enabled", legacySearch: "" });
  assert.deepEqual(filtered[0].children.map(node => node.id), ["child-first", "child-late"]);
  assert.deepEqual(collectExpandableOrganizationKeys(tree), ["root-later", "child-late"]);
  assert.equal(JSON.stringify(tree), before);
});
