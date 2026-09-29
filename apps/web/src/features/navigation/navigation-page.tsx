"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import { useEffect, useMemo, useState, type Key } from "react";
import {App, Button, Input, InputNumber, Select, Space, Switch, Tag, Typography} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { ArrowDownOutlined, ArrowUpOutlined, EditOutlined, MinusOutlined, PlusOutlined } from "@ant-design/icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, mutate } from "@/platform/api/client";
import type { NavigationMenu } from "@/platform/navigation/api-types";
import { messageKeyFor } from "@/platform/api/errors";
import { DeleteAction } from "@/platform/components/delete-action";
import { FormDrawer } from "@/platform/components/form-drawer";
import { ResourceTable } from "@/platform/components/resource-table";
import { EllipsisText } from "@/platform/components/ellipsis-text";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { useListRequest } from "@/platform/routing/use-list-request";
import { matchesListFilters } from "@/platform/api/pagination";
import { isSection } from "@/platform/shell/routes";
import { registeredPageComponentKeys, resolvePageComponent } from "@/platform/shell/page-component-registry";

type EditorValues = {
  parentId?: string | null;
  menuCode: string;
  labelKey: string;
  labelDefault: string;
  menuType: NavigationMenu["menuType"];
  sectionKey?: string;
  path?: string;
  componentKey?: string;
  icon?: string;
  permissionKey?: string;
  globalOnly?: boolean;
  moduleId?: string;
  sortOrder?: number;
  enabled?: boolean;
  version?: number;
};
type MenuRow = NavigationMenu & { level: number };
type NavigationTreeRow = NavigationMenu & { children?: NavigationTreeRow[] };
type NavigationTableRow = NavigationMenu & { level: number; parentLabel: string | null; hasChildren: boolean };

/** Convert the persisted adjacency list into a stable, depth-first sequence. */
export function flattenNavigation(rows: NavigationMenu[]): MenuRow[] {
  const byParent = new Map<string | null, NavigationMenu[]>();
  rows.filter((row) => !row.deleted).forEach((row) => {
    const list = byParent.get(row.parentId) ?? [];
    list.push(row);
    byParent.set(row.parentId, list);
  });
  const result: MenuRow[] = [];
  const walk = (parentId: string | null, level: number, path: Set<string>) => {
    const children = [...(byParent.get(parentId) ?? [])].sort((a, b) => a.sortOrder - b.sortOrder || a.id.localeCompare(b.id));
    children.forEach((row) => {
      if (path.has(row.id)) return;
      result.push({ ...row, level });
      walk(row.id, level + 1, new Set(path).add(row.id));
    });
  };
  walk(null, 0, new Set());
  return result;
}

function buildNavigationTree(rows: NavigationMenu[]): NavigationTreeRow[] {
  const byParent = new Map<string | null, NavigationMenu[]>();
  rows.filter((row) => !row.deleted).forEach((row) => {
    const list = byParent.get(row.parentId) ?? [];
    list.push(row);
    byParent.set(row.parentId, list);
  });
  const walk = (parentId: string | null, path: Set<string>): NavigationTreeRow[] => {
    const children = [...(byParent.get(parentId) ?? [])].sort((a, b) => a.sortOrder - b.sortOrder || a.id.localeCompare(b.id));
    return children.flatMap((row) => {
      if (path.has(row.id)) return [];
      const descendants = walk(row.id, new Set(path).add(row.id));
      return [{ ...row, ...(descendants.length ? { children: descendants } : {}) }];
    });
  };
  return walk(null, new Set());
}

function filterNavigationTree(nodes: NavigationTreeRow[], filters: Record<string, string> = {}, modes: Record<string, "contains" | "exact" | "range"> = {}): NavigationTreeRow[] {
  return nodes.flatMap((node) => {
    const children = filterNavigationTree(node.children ?? [], filters, modes);
    const matches = matchesListFilters({ ...node }, filters, modes);
    if (!matches && children.length === 0) return [];
    return [{ ...node, ...(children.length ? { children } : { children: undefined }) }];
  });
}

function collectExpandableKeys(nodes: NavigationTreeRow[]): Key[] {
  return nodes.flatMap((node) => [
    ...(node.children?.length ? [node.id] : []),
    ...collectExpandableKeys(node.children ?? []),
  ]);
}

function flattenVisibleNavigation(nodes: NavigationTreeRow[], expanded: ReadonlySet<string>, level = 0, parentLabel: string | null = null): NavigationTableRow[] {
  return nodes.flatMap(({ children = [], ...node }) => {
    const row: NavigationTableRow = { ...node, level, parentLabel, hasChildren: children.length > 0 };
    return [row, ...(expanded.has(node.id) ? flattenVisibleNavigation(children, expanded, level + 1, node.labelDefault) : [])];
  });
}

export function NavigationPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [editor, setEditor] = useState<NavigationMenu | null>(null);
  const [creating, setCreating] = useState(false);
  const [pending, setPending] = useState(false);
  const [pendingMutation, setPendingMutation] = useState<string | null>(null);
  const [expandedRowKeys, setExpandedRowKeys] = useState<Key[]>([]);
  const [form] = Form.useForm<EditorValues>();
  const menuType = Form.useWatch("menuType", form);
  const pageComponentKeys = registeredPageComponentKeys();
  const query = useQuery({
    queryKey: ["navigation-menus"],
    queryFn: ({ signal }) => api<NavigationMenu[]>("/platform/navigation/menus", { signal }),
    staleTime: 0,
  });
  const flattened = useMemo(() => flattenNavigation(query.data ?? []), [query.data]);
  const tree = useMemo(() => buildNavigationTree(query.data ?? []), [query.data]);
  const filteredTree = useMemo(() => filterNavigationTree(tree, request.filters, request.filterModes), [tree, request.filters, request.filterModes]);
  const visibleRows = useMemo(() => flattenVisibleNavigation(filteredTree, new Set(expandedRowKeys.map(String))), [filteredTree, expandedRowKeys]);
  const pageRows = visibleRows.slice((request.page - 1) * request.pageSize, request.page * request.pageSize);
  const hasFilters = Object.values(request.filters ?? {}).some((value) => value.trim().length > 0);
  useEffect(() => {
    setExpandedRowKeys(hasFilters ? collectExpandableKeys(filteredTree) : []);
  }, [filteredTree, hasFilters]);
  useEffect(() => {
    const lastPage = Math.max(1, Math.ceil(visibleRows.length / request.pageSize));
    if (request.page > lastPage) setRequest({ ...request, page: lastPage });
  }, [request, setRequest, visibleRows.length]);
  const excludedParentIds = useMemo(() => {
    const excluded = new Set<string>();
    if (!editor) return excluded;
    const children = new Map<string, string[]>();
    for (const row of query.data ?? []) {
      if (row.deleted || !row.parentId) continue;
      const siblings = children.get(row.parentId) ?? [];
      siblings.push(row.id);
      children.set(row.parentId, siblings);
    }
    const queue = [editor.id];
    excluded.add(editor.id);
    while (queue.length) {
      const parentId = queue.shift();
      if (!parentId) continue;
      for (const childId of children.get(parentId) ?? []) {
        if (excluded.has(childId)) continue;
        excluded.add(childId);
        queue.push(childId);
      }
    }
    return excluded;
  }, [editor?.id, query.data]);
  const actionPending = pending || pendingMutation !== null;
  const parentOptions = flattened.filter((row) => row.menuType !== "ACTION" && !excludedParentIds.has(row.id)).map((row) => ({
    value: row.id,
    label: `${"　".repeat(row.level)}${row.labelDefault}`,
  }));
  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({ menuType: "PAGE", enabled: true, globalOnly: false, sortOrder: 0 });
    setEditor(null);
    setCreating(true);
  };
  const openEdit = (row: NavigationMenu) => {
    if (!canWrite || row.builtIn) return;
    form.setFieldsValue({
      parentId: row.parentId,
      menuCode: row.menuCode,
      labelKey: row.labelKey,
      labelDefault: row.labelDefault,
      menuType: row.menuType,
      sectionKey: row.sectionKey ?? undefined,
      path: row.path ?? undefined,
      componentKey: row.componentKey ?? undefined,
      icon: row.icon ?? undefined,
      permissionKey: row.permissionKey ?? undefined,
      globalOnly: row.globalOnly,
      moduleId: row.moduleId ?? undefined,
      sortOrder: row.sortOrder,
      enabled: row.enabled,
      version: row.version,
    });
    setEditor(row);
    setCreating(false);
  };
  const close = () => {
    if (!actionPending) {
      setEditor(null);
      setCreating(false);
      form.resetFields();
    }
  };
  const navigationErrorKeys = ["invalidNavigation", "navigationBuiltIn", "navigationHasChildren", "navigationCycle", "navigationNotFound", "navigationRouteConflict", "staleRecord", "forbidden"] as const satisfies readonly MessageKey[];
  const save = async (values: EditorValues) => {
    if (!canWrite || actionPending) return;
    setPending(true);
    const payload = {
      parentId: values.parentId || null,
      menuCode: values.menuCode,
      labelKey: values.labelKey,
      labelDefault: values.labelDefault,
      menuType: values.menuType,
      sectionKey: values.menuType === "CATALOG" ? null : values.sectionKey || null,
      path: values.menuType === "CATALOG" ? null : values.path || null,
      componentKey: values.menuType === "CATALOG" ? null : values.componentKey || null,
      icon: values.icon || null,
      permissionKey: values.permissionKey || null,
      globalOnly: Boolean(values.globalOnly),
      moduleId: values.moduleId || null,
      sortOrder: values.sortOrder ?? 0,
      enabled: values.enabled !== false,
      ...(editor ? { version: values.version } : {}),
    };
    try {
      if (editor) await mutate(`/platform/navigation/menus/${editor.id}`, "PUT", payload);
      else await mutate("/platform/navigation/menus", "POST", payload);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["navigation-menus"] }),
        client.invalidateQueries({ queryKey: ["navigation-runtime"] }),
      ]);
      close();
      void message.success(t("navigationSaved"));
    } catch (error) {
      void message.error(t(messageKeyFor(error, navigationErrorKeys)));
      if (error instanceof ApiError && error.status === 409) void query.refetch();
    } finally {
      setPending(false);
    }
  };
  const toggle = async (row: NavigationMenu) => {
    if (!canWrite || row.builtIn || actionPending) return;
    setPendingMutation(row.id);
    try {
      await mutate(`/platform/navigation/menus/${row.id}/status`, "PATCH", { version: row.version, enabled: !row.enabled });
      await Promise.all([client.invalidateQueries({ queryKey: ["navigation-menus"] }), client.invalidateQueries({ queryKey: ["navigation-runtime"] })]);
    } catch (error) {
      void message.error(t(messageKeyFor(error, navigationErrorKeys)));
      if (error instanceof ApiError && error.status === 409) void query.refetch();
    } finally {
      setPendingMutation(null);
    }
  };
  const remove = async (row: NavigationMenu) => {
    if (actionPending) return;
    setPendingMutation(row.id);
    try {
      await mutate(`/platform/navigation/menus/${row.id}?version=${row.version}`, "DELETE");
      await Promise.all([client.invalidateQueries({ queryKey: ["navigation-menus"] }), client.invalidateQueries({ queryKey: ["navigation-runtime"] })]);
      void message.success(t("navigationDeleted"));
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) void query.refetch();
      throw error;
    } finally {
      setPendingMutation(null);
    }
  };
  const move = async (row: NavigationMenu, direction: -1 | 1) => {
    if (!canWrite || row.builtIn || actionPending) return;
    const siblings = flattened.filter((candidate) => candidate.parentId === row.parentId).sort((a, b) => a.sortOrder - b.sortOrder || a.id.localeCompare(b.id));
    const index = siblings.findIndex((candidate) => candidate.id === row.id);
    const neighbor = siblings[index + direction];
    if (!neighbor || neighbor.builtIn) return;
    setPendingMutation(row.id);
    try {
      await mutate("/platform/navigation/menus/reorder", "PUT", [
        { id: row.id, parentId: row.parentId, sortOrder: neighbor.sortOrder, version: row.version },
        { id: neighbor.id, parentId: neighbor.parentId, sortOrder: row.sortOrder, version: neighbor.version },
      ]);
      await Promise.all([client.invalidateQueries({ queryKey: ["navigation-menus"] }), client.invalidateQueries({ queryKey: ["navigation-runtime"] })]);
    } catch (error) {
      void message.error(t(messageKeyFor(error, navigationErrorKeys)));
      if (error instanceof ApiError && error.status === 409) void query.refetch();
    } finally {
      setPendingMutation(null);
    }
  };
  const hasChildren = (id: string) => flattened.some((row) => row.parentId === id);
  const toggleExpanded = (id: string) => {
    setExpandedRowKeys(current => current.includes(id) ? current.filter(key => key !== id) : [...current, id]);
    setRequest({ ...request, page: 1 });
  };
  return <>
    <ResourceTable<NavigationTableRow>
      result={{ items: pageRows, total: visibleRows.length, page: request.page, pageSize: request.pageSize }}
      loading={query.isFetching}
      error={query.error}
      request={request}
      onChange={setRequest}
      onRetry={() => void query.refetch()}
      searchInitialValues={{ labelDefault: request.filters?.labelDefault, menuType: request.filters?.menuType, enabled: request.filters?.enabled }}
      actions={canWrite ? <Button type="primary" icon={<PlusOutlined />} disabled={actionPending} onClick={openCreate}>{t("createNavigation")}</Button> : undefined}
      columns={[
        { title: t("navigationLabel"), dataIndex: "labelDefault", width: 360, search: true, render: (_, row) => <NavigationHierarchyLabel label={row.labelDefault} level={row.level} parentLabel={row.parentLabel} hasChildren={row.hasChildren} expanded={expandedRowKeys.includes(row.id)} onToggle={() => toggleExpanded(row.id)} /> },
        { title: t("navigationCode"), dataIndex: "menuCode", width: 200, render: (_, row) => <EllipsisText text={row.menuCode} /> },
        { title: t("navigationType"), dataIndex: "menuType", width: 120, search: true, valueEnum: { CATALOG: { text: t("navigationCatalog") }, PAGE: { text: t("navigationPage") }, ACTION: { text: t("navigationAction") } }, render: (_, row) => <Tag>{t(row.menuType === "CATALOG" ? "navigationCatalog" : row.menuType === "PAGE" ? "navigationPage" : "navigationAction")}</Tag> },
        { title: t("status"), dataIndex: "enabled", width: 100, search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
        { title: t("navigationBuiltIn"), dataIndex: "builtIn", width: 110, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => row.builtIn ? <Tag>{t("enabled")}</Tag> : "—" },
        { title: t("actions"), search: false, width: 360, render: (_, row) => <TableActions>
          {canWrite && !row.builtIn && <Button icon={<EditOutlined />} disabled={actionPending} onClick={() => openEdit(row)} aria-label={t("edit")}>{t("edit")}</Button>}
          {canWrite && !row.builtIn && <Button disabled={actionPending} loading={pendingMutation === row.id} onClick={() => void toggle(row)}>{t(row.enabled ? "disabled" : "enabled")}</Button>}
          {canWrite && !row.builtIn && <Button icon={<ArrowUpOutlined />} disabled={actionPending || !siblingsCanMove(flattened, row, -1)} loading={pendingMutation === row.id} onClick={() => void move(row, -1)} aria-label={t("moveUp")} />}
          {canWrite && !row.builtIn && <Button icon={<ArrowDownOutlined />} disabled={actionPending || !siblingsCanMove(flattened, row, 1)} loading={pendingMutation === row.id} onClick={() => void move(row, 1)} aria-label={t("moveDown")} />}
          {canWrite && !row.builtIn && <DeleteAction name={row.labelDefault} disabled={actionPending || hasChildren(row.id)} onDelete={() => remove(row)} />}
        </TableActions> },
      ]}
    />
    <FormDrawer open={creating || !!editor} title={creating ? t("createNavigation") : t("edit")} pending={actionPending} formId="navigation-editor" size={520} onClose={close}>
      <Typography.Paragraph type="secondary">{t("navigationHint")}</Typography.Paragraph>
      <Form id="navigation-editor" form={form} layout="vertical" onFinish={save}>
        <Form.Item name="menuCode" label={t("navigationCode")} rules={[{ required: true }, { pattern: /^[a-z][a-z0-9._:-]{1,127}$/ }]}><Input maxLength={128} disabled={!!editor} /></Form.Item>
        <Form.Item name="labelKey" label={t("navigationLabelKey")} rules={[{ required: true }, { pattern: /^[a-z][a-zA-Z0-9._:-]{0,127}$/ }]}><Input maxLength={128} /></Form.Item>
        <Form.Item name="labelDefault" label={t("navigationLabel")} rules={[{ required: true }, { max: 200 }]}><Input maxLength={200} /></Form.Item>
        <Form.Item name="menuType" label={t("navigationType")} rules={[{ required: true }]}><Select options={[{ value: "CATALOG", label: t("navigationCatalog") }, { value: "PAGE", label: t("navigationPage") }, { value: "ACTION", label: t("navigationAction") }]} /></Form.Item>
        <Form.Item name="parentId" label={t("parentNavigation")}><Select allowClear placeholder={t("rootNavigation")} options={parentOptions} /></Form.Item>
        {menuType !== "CATALOG" && <>
          <Form.Item name="sectionKey" label={t("navigationSectionKey")} rules={[{ required: true }, { pattern: /^[a-z][a-z0-9-]{0,63}$/ }, { validator: (_, value) => {
            const target = form.getFieldValue("componentKey") || form.getFieldValue("menuCode") || value;
            if (menuType !== "PAGE" || isSection(value) || resolvePageComponent(target)) return Promise.resolve();
            return Promise.reject(new Error(t("navigateHint")));
          } }]}><Input maxLength={64} /></Form.Item>
          <Form.Item name="path" label={t("navigationPath")} rules={[{ pattern: /^\/dashboard\/[a-z][a-z0-9-]{0,63}$/ }]}><Input maxLength={512} placeholder="/dashboard/example" /></Form.Item>
          <Form.Item name="componentKey" label={t("navigationComponent")} rules={[{ pattern: /^[a-z][a-z0-9._:-]{1,127}$/ }]}><Select allowClear showSearch optionFilterProp="label" options={pageComponentKeys.map(value => ({ value, label: value }))} placeholder={t("navigationComponentPlaceholder")} /></Form.Item>
          <Form.Item name="permissionKey" label={t("navigationPermission")}><Input maxLength={120} /></Form.Item>
          <Form.Item name="moduleId" label={t("navigationModule")}><Input maxLength={100} /></Form.Item>
          <Form.Item name="globalOnly" label={t("navigationPermission")} valuePropName="checked"><Switch checkedChildren={t("navigationGlobal")} unCheckedChildren={t("navigationLocal")} /></Form.Item>
        </>}
        <Space align="start">
          <Form.Item name="sortOrder" label={t("sortOrder")}><InputNumber min={0} max={1000000} /></Form.Item>
          <Form.Item name="enabled" label={t("status")} valuePropName="checked"><Switch checkedChildren={t("enabled")} unCheckedChildren={t("disabled")} /></Form.Item>
        </Space>
      </Form>
    </FormDrawer>
  </>;
}

function NavigationHierarchyLabel({ label, level, parentLabel, hasChildren, expanded, onToggle }: {
  label: string;
  level: number;
  parentLabel: string | null;
  hasChildren: boolean;
  expanded: boolean;
  onToggle: () => void;
}) {
  return <span style={{ display: "flex", alignItems: "center", width: "100%", minWidth: 0, paddingInlineStart: level * 20 }}>
    {hasChildren
      ? <Button type="text" size="small" aria-label={label} aria-expanded={expanded} icon={expanded ? <MinusOutlined /> : <PlusOutlined />} onClick={onToggle} />
      : <span aria-hidden="true" style={{ width: 32, flex: "0 0 32px" }} />}
    <span style={{ flex: "1 1 0%", minWidth: 0 }}><EllipsisText text={label} /></span>
    {parentLabel && <Typography.Text type="secondary" style={{ display: "block", flex: "0 1 38%", minWidth: 0, marginInlineStart: 8, fontSize: 12 }}><EllipsisText text={parentLabel} /></Typography.Text>}
  </span>;
}

function siblingsCanMove(rows: NavigationMenu[], row: NavigationMenu, direction: -1 | 1) {
  const siblings = rows.filter((candidate) => candidate.parentId === row.parentId).sort((a, b) => a.sortOrder - b.sortOrder || a.id.localeCompare(b.id));
  const index = siblings.findIndex((candidate) => candidate.id === row.id);
  const neighbor = siblings[index + direction];
  return Boolean(neighbor && !neighbor.builtIn);
}
