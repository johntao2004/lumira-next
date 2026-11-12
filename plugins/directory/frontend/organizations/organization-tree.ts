export type Organization = {
  id: string;
  parentId: string | null;
  code: string;
  name: string;
  enabled: boolean;
  sortNo: number;
  version: number;
};

export type OrganizationNode = Organization & { children?: OrganizationNode[] };

export type OrganizationFilters = {
  code: string;
  name: string;
  status: "enabled" | "disabled" | "";
  legacySearch: string;
};

export function buildOrganizationTree(organizations: Organization[]): OrganizationNode[] {
  // API pages are already ordered by sortNo, code, and id. The explicit index
  // tie-break preserves that server ordering without locale-dependent compares.
  const ordered = organizations
    .map((organization, index) => ({ organization, index }))
    .sort((left, right) => left.organization.sortNo - right.organization.sortNo || left.index - right.index)
    .map(({ organization }) => organization);
  const nodes = new Map<string, OrganizationNode>(ordered.map(organization => [organization.id, { ...organization, children: [] }]));
  const roots: OrganizationNode[] = [];

  for (const organization of ordered) {
    const node = nodes.get(organization.id)!;
    const parent = organization.parentId ? nodes.get(organization.parentId) : undefined;
    if (parent) parent.children!.push(node);
    else roots.push(node);
  }

  for (const node of nodes.values()) {
    if (!node.children?.length) node.children = undefined;
  }
  return roots;
}

export function filterOrganizationTree(nodes: OrganizationNode[], filters: OrganizationFilters): OrganizationNode[] {
  const codeNeedle = filters.code.trim().toLocaleLowerCase();
  const nameNeedle = filters.name.trim().toLocaleLowerCase();
  const searchNeedle = filters.legacySearch.trim().toLocaleLowerCase();
  if (!codeNeedle && !nameNeedle && !filters.status && !searchNeedle) return nodes;

  const retained = new Map<OrganizationNode, OrganizationNode>();
  const stack: { node: OrganizationNode; visited: boolean }[] = [];
  for (let i = nodes.length - 1; i >= 0; i--) stack.push({ node: nodes[i], visited: false });
  while (stack.length) {
    const { node, visited } = stack.pop()!;
    if (!visited) {
      stack.push({ node, visited: true });
      for (let i = (node.children?.length ?? 0) - 1; i >= 0; i--) {
        stack.push({ node: node.children![i], visited: false });
      }
      continue;
    }
    const children = (node.children ?? []).flatMap(child => {
      const value = retained.get(child);
      return value ? [value] : [];
    });
    const matchesCode = !codeNeedle || node.code.toLocaleLowerCase().includes(codeNeedle);
    const matchesName = !nameNeedle || node.name.toLocaleLowerCase().includes(nameNeedle);
    const matchesStatus = !filters.status || node.enabled === (filters.status === "enabled");
    const matchesLegacySearch = !searchNeedle || `${node.code} ${node.name}`.toLocaleLowerCase().includes(searchNeedle);
    if ((matchesCode && matchesName && matchesStatus && matchesLegacySearch) || children.length) {
      retained.set(node, { ...node, children: children.length ? children : undefined });
    }
  }
  return nodes.flatMap(node => {
    const value = retained.get(node);
    return value ? [value] : [];
  });
}

export function paginateOrganizationTree(nodes: OrganizationNode[], page: number, pageSize: number): OrganizationNode[] {
  return nodes.slice((page - 1) * pageSize, page * pageSize);
}


/** Iterative preorder keeps large/deep organization trees off the JavaScript call stack. */
export function collectExpandableOrganizationKeys(nodes: readonly OrganizationNode[]): string[] {
  const keys: string[] = [];
  const stack: OrganizationNode[] = [];
  for (let i = nodes.length - 1; i >= 0; i--) stack.push(nodes[i]);
  while (stack.length) {
    const node = stack.pop()!;
    if (!node.children?.length) continue;
    keys.push(node.id);
    for (let i = node.children.length - 1; i >= 0; i--) stack.push(node.children[i]);
  }
  return keys;
}
