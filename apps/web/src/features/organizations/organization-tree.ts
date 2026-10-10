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

  const removeEmptyChildren = (node: OrganizationNode): OrganizationNode => {
    const children = node.children?.map(removeEmptyChildren) ?? [];
    return children.length ? { ...node, children } : { ...node, children: undefined };
  };
  return roots.map(removeEmptyChildren);
}

export function filterOrganizationTree(nodes: OrganizationNode[], filters: OrganizationFilters): OrganizationNode[] {
  const codeNeedle = filters.code.trim().toLocaleLowerCase();
  const nameNeedle = filters.name.trim().toLocaleLowerCase();
  const searchNeedle = filters.legacySearch.trim().toLocaleLowerCase();
  if (!codeNeedle && !nameNeedle && !filters.status && !searchNeedle) return nodes;

  return nodes.flatMap(node => {
    const children = filterOrganizationTree(node.children ?? [], filters);
    const matchesCode = !codeNeedle || node.code.toLocaleLowerCase().includes(codeNeedle);
    const matchesName = !nameNeedle || node.name.toLocaleLowerCase().includes(nameNeedle);
    const matchesStatus = !filters.status || node.enabled === (filters.status === "enabled");
    const matchesLegacySearch = !searchNeedle || `${node.code} ${node.name}`.toLocaleLowerCase().includes(searchNeedle);
    const matches = matchesCode && matchesName && matchesStatus && matchesLegacySearch;
    if (!matches && children.length === 0) return [];
    return [{ ...node, children: children.length ? children : undefined }];
  });
}

export function paginateOrganizationTree(nodes: OrganizationNode[], page: number, pageSize: number): OrganizationNode[] {
  return nodes.slice((page - 1) * pageSize, page * pageSize);
}
