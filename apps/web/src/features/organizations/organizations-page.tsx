"use client";

import { useEffect, useMemo, useState, type Key } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Tag } from "antd";
import { api, mutate } from "@/platform/api/client";
import { useCrudDrawer } from "@/platform/forms/use-crud-drawer";
import { saveRecord } from "@/platform/api/records";
import type { MessageKey } from "@/platform/i18n/messages";
import { useListLocation, useLocationParams } from "@/platform/routing/use-list-location";
import { usePreferences } from "@/platform/providers";
import { isRolePreviewReadOnly } from "@/platform/api/client";
import { TableActions } from "@/platform/components/table-actions";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { FormDrawer } from "@/platform/components/form-drawer";
import { DeleteAction } from "@/platform/components/delete-action";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { OrganizationForm } from "./organization-form";
import {
  buildOrganizationTree,
  filterOrganizationTree,
  paginateOrganizationTree,
  type Organization,
  type OrganizationFilters,
  type OrganizationNode,
} from "./organization-tree";

const organizationErrorKeys = ["staleRecord", "forbidden", "invalidOrganization", "organizationCycle"] as const satisfies readonly MessageKey[];
const organizationErrorAliases = { duplicateRecord: "duplicateOrganization" } as const satisfies Readonly<Record<string, MessageKey>>;
const organizationPageSize = 100;
const organizationPageBatchSize = 4;

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

function collectExpandableKeys(nodes: OrganizationNode[]): Key[] {
  return nodes.flatMap(node => [
    ...(node.children?.length ? [node.id] : []),
    ...collectExpandableKeys(node.children ?? []),
  ]);
}

function collectOrganizationAncestorKeys(organizations: Organization[], organizationId: string): Key[] {
  const byId = new Map(organizations.map(organization => [organization.id, organization]));
  const expandedKeys: Key[] = [];
  const visited = new Set<string>();
  let parentId = byId.get(organizationId)?.parentId;

  while (parentId && !visited.has(parentId)) {
    visited.add(parentId);
    expandedKeys.push(parentId);
    parentId = byId.get(parentId)?.parentId;
  }

  return expandedKeys;
}

export function OrganizationsPage({ canCreate, canUpdate, canDelete, readOnlyPreview = false }: { canCreate: boolean; canUpdate: boolean; canDelete: boolean; readOnlyPreview?: boolean }) {
  const previewReadOnly = readOnlyPreview || isRolePreviewReadOnly();
  const effectiveCanCreate = canCreate && !previewReadOnly;
  const effectiveCanUpdate = canUpdate && !previewReadOnly;
  const effectiveCanDelete = canDelete && !previewReadOnly;
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
  const {selected,setSelected,pending,error,close,saved,run}=useCrudDrawer<Organization>({errorKeys:organizationErrorKeys,errorAliases:organizationErrorAliases});
  const [newOrganizationParentId, setNewOrganizationParentId] = useState<string | null>(null);
  const [expandAfterCreate, setExpandAfterCreate] = useState<string | null>(null);
  useEffect(() => {
    if (previewReadOnly && selected !== undefined) close();
  }, [previewReadOnly, selected, close]);
  const [expandedRowKeys, setExpandedRowKeys] = useState<Key[]>([]);
  const query = useQuery({
    queryKey: ["organizations", "tree"],
    queryFn: ({ signal }) => fetchAllOrganizations(signal),
  });
  const tree = useMemo(() => buildOrganizationTree(query.data ?? []), [query.data]);
  const filteredTree = useMemo(() => filterOrganizationTree(tree, filters), [tree, filters]);
  // Paginate roots so expanding a branch keeps its descendants on the same page.
  const pageRows = paginateOrganizationTree(filteredTree, request.page, request.pageSize);

  useEffect(() => {
    setExpandedRowKeys(hasFilters ? collectExpandableKeys(filteredTree) : []);
  }, [filteredTree, hasFilters]);
  useEffect(() => {
    if (!expandAfterCreate || !query.data?.some(organization => organization.id === expandAfterCreate)) return;
    setExpandedRowKeys(collectOrganizationAncestorKeys(query.data, expandAfterCreate));
    setExpandAfterCreate(null);
  }, [expandAfterCreate, query.data]);
  useEffect(() => {
    const lastPage = Math.max(1, Math.ceil(filteredTree.length / request.pageSize));
    if (request.page > lastPage) setRequest({ ...request, page: lastPage });
  }, [request, setRequest, filteredTree.length]);


  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["organizations"] }),
      client.invalidateQueries({ queryKey: ["resource-select", "/platform/organizations"] }),
      client.invalidateQueries({ queryKey: ["resource-select-detail", "/platform/organizations"] }),
    ]);
  };

  const beginCreate = (parentId: string | null) => {
    if (pending) return;
    setNewOrganizationParentId(parentId);
    setSelected(null);
  };
  const beginEdit = (organization: OrganizationNode) => {
    if (pending) return;
    setNewOrganizationParentId(null);
    setSelected(organization);
  };
  const closeDrawer = () => {
    if (pending) return;
    close();
    setNewOrganizationParentId(null);
  };

  return <>
    <ResourceTable<OrganizationNode>
      searchInitialValues={{ code: codeFilter || undefined, name: nameFilter || undefined, enabled: statusFilter ? statusFilter === "enabled" : undefined }}
      onSearch={({ filters: values }) => updateLocation({
        organizationCode: values?.code?.trim() || null,
        organizationName: values?.name?.trim() || null,
        organizationStatus: values?.enabled === "true" ? "enabled" : values?.enabled === "false" ? "disabled" : null,
        search: null,
        page: null,
      })}
      result={query.data ? { items: pageRows, total: filteredTree.length, page: request.page, pageSize: request.pageSize } : undefined}
      expandable={{ expandedRowKeys, onExpandedRowsChange: keys => setExpandedRowKeys([...keys]) }}
      loading={query.isFetching}
      error={query.error}
      request={request}
      onChange={setRequest}
      onRetry={() => void query.refetch()}
      actions={effectiveCanCreate && <Button type="primary" onClick={() => beginCreate(null)}>{t("createOrganization")}</Button>}
      columns={[
        { title: t("organizationCode"), dataIndex: "code", search: true },
        { title: t("name"), dataIndex: "name", search: true },
        { title: t("organizationSortNo"), dataIndex: "sortNo", search: false },
        { title: t("status"), key: "status", dataIndex: "enabled", search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
        ...(effectiveCanCreate || effectiveCanUpdate || effectiveCanDelete ? [{ title: t("actions"), key: "actions", render: (_: unknown, row: OrganizationNode) => <TableActions>
          {effectiveCanCreate && <Button type="link" onClick={() => beginCreate(row.id)}>{t("createChildOrganization")}</Button>}
          {effectiveCanUpdate && <Button type="link" onClick={() => beginEdit(row)}>{t("edit")}</Button>}
          {effectiveCanDelete && <DeleteAction name={row.name} onDelete={async () => {
            await mutate(`/platform/organizations/${row.id}?version=${row.version}`, "DELETE", {});
            await refresh();
          }} />}
        </TableActions> }] : []),
      ]}
    />
    <FormDrawer open={selected !== undefined} title={t(selected ? "edit" : newOrganizationParentId ? "createChildOrganization" : "createOrganization")} pending={pending} submitDisabled={selected === null ? !effectiveCanCreate : !effectiveCanUpdate} formId="organization-editor" onClose={closeDrawer}>
      <FeedbackBubble id="organization-editor-error" content={error ? t(error) : undefined} />
      {selected !== undefined && <OrganizationForm key={selected?.id ?? `new:${newOrganizationParentId ?? "root"}`} selected={selected} parent={selected === null ? newOrganizationParentId ?? "root" : "root"} pending={pending} canWrite={selected === null ? effectiveCanCreate : effectiveCanUpdate} onFinish={async values => { await run(async () => {

          const isChildCreate = selected === null && newOrganizationParentId !== null;
          const savedOrganization = await saveRecord<Organization>("/platform/organizations",selected?.id, { ...values, parentId: values.parentId ?? null, version: selected?.version });
          if (isChildCreate) setExpandAfterCreate(savedOrganization.id);
          saved();
          setNewOrganizationParentId(null);
          await refresh();

      }); }} />}
    </FormDrawer>
  </>;
}
