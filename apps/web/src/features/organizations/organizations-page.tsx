"use client";

import { useEffect, useMemo, useState, type Key } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Tag } from "antd";
import { api, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import type { MessageKey } from "@/platform/i18n/messages";
import { useListLocation, useLocationParams } from "@/platform/routing/use-list-location";
import { usePreferences } from "@/platform/providers";
import { TableActions } from "@/platform/components/table-actions";
import { ResourceTable } from "@/platform/components/resource-table";
import { HierarchyTableLabel } from "@/platform/components/hierarchy-table-label";
import type { PageResult } from "@/platform/api/pagination";
import { FormDrawer } from "@/platform/components/form-drawer";
import { DeleteAction } from "@/platform/components/delete-action";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { OrganizationForm, type Organization } from "./organization-form";

const organizationErrorKeys = ["staleRecord", "forbidden", "invalidOrganization", "organizationCycle"] as const satisfies readonly MessageKey[];
const organizationErrorAliases = { duplicateRecord: "duplicateOrganization" } as const satisfies Readonly<Record<string, MessageKey>>;
const organizationPageSize = 100;
const organizationPageBatchSize = 4;

type OrganizationNode = Organization & { children?: OrganizationNode[] };
type OrganizationTableRow = Organization & { level: number; parentLabel: string | null; hasChildren: boolean };
type OrganizationFilters = { code: string; name: string; status: "enabled" | "disabled" | ""; legacySearch: string };

async function fetchAllOrganizations(signal: AbortSignal): Promise<Organization[]> {
  const loadPage = (page: number) => api<PageResult<Organization>>(
    `/platform/organizations?${new URLSearchParams({ page: String(page), pageSize: String(organizationPageSize) })}`,
    { signal },
  );
  const first = await loadPage(1);
  const items = [...first.items];
  const pageCount = Math.ceil(first.total / organizationPageSize);

  for (let start = 2; start <= pageCount; start += organizationPageBatchSize) {
    const pages = Array.from(
      { length: Math.min(organizationPageBatchSize, pageCount - start + 1) },
      (_, index) => start + index,
    );
    const results = await Promise.all(pages.map(loadPage));
    items.push(...results.flatMap(result => result.items));
  }

  return items;
}

function buildOrganizationTree(organizations: Organization[]): OrganizationNode[] {
  const ordered = [...organizations].sort((left, right) => left.code.localeCompare(right.code) || left.id.localeCompare(right.id));
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

function filterOrganizationTree(nodes: OrganizationNode[], filters: OrganizationFilters): OrganizationNode[] {
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

function collectExpandableKeys(nodes: OrganizationNode[]): Key[] {
  return nodes.flatMap(node => [
    ...(node.children?.length ? [node.id] : []),
    ...collectExpandableKeys(node.children ?? []),
  ]);
}

function flattenVisibleOrganizations(nodes: OrganizationNode[], expanded: ReadonlySet<string>, level = 0, parentLabel: string | null = null): OrganizationTableRow[] {
  return nodes.flatMap(({ children = [], ...node }) => {
    const row: OrganizationTableRow = { ...node, level, parentLabel, hasChildren: children.length > 0 };
    return [row, ...(expanded.has(node.id) ? flattenVisibleOrganizations(children, expanded, level + 1, node.name) : [])];
  });
}

export function OrganizationsPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [request, setRequest] = useListLocation();
  const [locationParams, updateLocation] = useLocationParams();
  const codeFilter = (locationParams.get("organizationCode") ?? "").trim();
  const nameFilter = (locationParams.get("organizationName") ?? "").trim();
  const statusParam = locationParams.get("organizationStatus");
  const statusFilter = statusParam === "enabled" || statusParam === "disabled" ? statusParam : "";
  const filters = useMemo<OrganizationFilters>(() => ({
    code: codeFilter,
    name: nameFilter,
    status: statusFilter,
    legacySearch: request.search,
  }), [codeFilter, nameFilter, statusFilter, request.search]);
  const hasFilters = Boolean(codeFilter || nameFilter || statusFilter || request.search.trim());
  const [selected, setSelected] = useState<Organization | null | undefined>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<MessageKey | null>(null);
  const [expandedRowKeys, setExpandedRowKeys] = useState<Key[]>([]);
  const query = useQuery({
    queryKey: ["organizations", "tree"],
    queryFn: ({ signal }) => fetchAllOrganizations(signal),
  });
  const tree = useMemo(() => buildOrganizationTree(query.data ?? []), [query.data]);
  const filteredTree = useMemo(() => filterOrganizationTree(tree, filters), [tree, filters]);
  const visibleRows = useMemo(() => flattenVisibleOrganizations(filteredTree, new Set(expandedRowKeys.map(String))), [filteredTree, expandedRowKeys]);
  const pageRows = visibleRows.slice((request.page - 1) * request.pageSize, request.page * request.pageSize);

  useEffect(() => {
    setExpandedRowKeys(hasFilters ? collectExpandableKeys(filteredTree) : []);
  }, [filteredTree, hasFilters]);
  useEffect(() => {
    const lastPage = Math.max(1, Math.ceil(visibleRows.length / request.pageSize));
    if (request.page > lastPage) setRequest({ ...request, page: lastPage });
  }, [request, setRequest, visibleRows.length]);

  const toggleExpanded = (id: string) => {
    setExpandedRowKeys(current => current.includes(id) ? current.filter(key => key !== id) : [...current, id]);
    setRequest({ ...request, page: 1 });
  };

  const close = () => {
    setSelected(undefined);
    setError(null);
  };
  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["organizations"] }),
      client.invalidateQueries({ queryKey: ["resource-select", "/platform/organizations"] }),
      client.invalidateQueries({ queryKey: ["resource-select-detail", "/platform/organizations"] }),
    ]);
  };

  return <>
    <ResourceTable<OrganizationTableRow>
      searchInitialValues={{ code: codeFilter || undefined, name: nameFilter || undefined, enabled: statusFilter ? statusFilter === "enabled" : undefined }}
      onSearch={({ filters: values }) => updateLocation({
        organizationCode: values?.code?.trim() || null,
        organizationName: values?.name?.trim() || null,
        organizationStatus: values?.enabled === "true" ? "enabled" : values?.enabled === "false" ? "disabled" : null,
        search: null,
        page: null,
      })}
      result={query.data ? { items: pageRows, total: visibleRows.length, page: request.page, pageSize: request.pageSize } : undefined}
      loading={query.isFetching}
      error={query.error}
      request={request}
      onChange={setRequest}
      onRetry={() => void query.refetch()}
      actions={canWrite && <Button type="primary" onClick={() => setSelected(null)}>{t("createOrganization")}</Button>}
      columns={[
        { title: t("organizationCode"), dataIndex: "code", search: true },
        { title: t("name"), dataIndex: "name", search: true, render: (_, row) => <HierarchyTableLabel label={row.name} level={row.level} parentLabel={row.parentLabel} hasChildren={row.hasChildren} expanded={expandedRowKeys.includes(row.id)} onToggle={() => toggleExpanded(row.id)} /> },
        { title: t("status"), key: "status", dataIndex: "enabled", search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
        ...(canWrite ? [{ title: t("actions"), render: (_: unknown, row: OrganizationNode) => <TableActions>
          <Button type="link" onClick={() => setSelected(row)}>{t("edit")}</Button>
          <DeleteAction name={row.name} onDelete={async () => {
            await mutate(`/platform/organizations/${row.id}?version=${row.version}`, "DELETE", {});
            await refresh();
          }} />
        </TableActions> }] : []),
      ]}
    />
    <FormDrawer open={selected !== undefined} title={t(selected ? "edit" : "createOrganization")} pending={pending} submitDisabled={!canWrite} formId="organization-editor" onClose={close}>
      <FeedbackBubble id="organization-editor-error" content={error ? t(error) : undefined} />
      {selected !== undefined && <OrganizationForm key={selected?.id ?? "new"} selected={selected} parent="root" pending={pending} canWrite={canWrite} onFinish={async values => {
        setPending(true);
        setError(null);
        try {
          await mutate(selected ? `/platform/organizations/${selected.id}` : "/platform/organizations", selected ? "PUT" : "POST", { ...values, parentId: values.parentId ?? null, version: selected?.version });
          close();
          await refresh();
        } catch (caught) {
          setError(messageKeyFor(caught, organizationErrorKeys, "unavailable", organizationErrorAliases));
        } finally {
          setPending(false);
        }
      }} />}
    </FormDrawer>
  </>;
}
