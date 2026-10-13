"use client";
import { ProTable, type ProColumns, type ProFormInstance } from "@ant-design/pro-components";
import type { TableProps } from "antd";
import { Children, useRef } from "react";
import { useEffect } from "react";
import dayjs from "dayjs";
import { ApiError } from "../api/client";
import { TABLE_PAGE_SIZE_OPTIONS, type ListRequest, type PageResult } from "../api/pagination";
import { usePreferences } from "../providers";
import { FeedbackBubble } from "./feedback-bubble";
import { tableActionColumn } from "./table-actions";
import { useQueryFilterSpan } from "./use-query-filter-span";

export type { ListRequest, PageResult } from "../api/pagination";
export type { ProColumns } from "@ant-design/pro-components";
export type ResourceSearch = Pick<ListRequest, "filters" | "filterModes">;

function fieldValue(value: unknown): string | undefined {
  if (value === null || value === undefined || value === "") return undefined;
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value);
  if (value instanceof Date) return value.toISOString();
  if (typeof value === "object" && "toISOString" in value && typeof value.toISOString === "function") return value.toISOString();
  return undefined;
}

function readColumnSearch<T>(columns: ProColumns<T>[], values: Record<string, unknown>): ResourceSearch {
  const filters: Record<string, string> = {};
  const filterModes: NonNullable<ListRequest["filterModes"]> = {};
  for (const column of columns) {
    const name = typeof column.dataIndex === "string" ? column.dataIndex : undefined;
    if (!column.search || !name || !(name in values)) continue;
    const raw = values[name];
    if (raw === null || raw === undefined || raw === "") continue;
    const valueType = String(column.valueType ?? "text");
    const mode = valueType.toLowerCase().includes("range") ? "range"
      : valueType === "text" && !column.valueEnum ? "contains" : "exact";
    filterModes[name] = mode;
    if (Array.isArray(raw) && mode === "range") {
      const start = fieldValue(raw[0]);
      const end = fieldValue(raw[1]);
      if (start) filters[`${name}.from`] = start;
      if (end) filters[`${name}.to`] = end;
      continue;
    }
    if (mode === "range" && typeof raw === "object") {
      const range = raw as { from?: unknown; to?: unknown; start?: unknown; end?: unknown };
      const start = fieldValue(range.from ?? range.start);
      const end = fieldValue(range.to ?? range.end);
      if (start) filters[`${name}.from`] = start;
      if (end) filters[`${name}.to`] = end;
      continue;
    }
    const value = fieldValue(raw);
    if (value !== undefined) filters[name] = value;
  }
  return { filters, filterModes };
}

function requestSearchValues<T>(columns: ProColumns<T>[], request: ListRequest): Record<string, unknown> {
  const values: Record<string, unknown> = Object.create(null);
  for (const column of columns) {
    const name = typeof column.dataIndex === "string" ? column.dataIndex : undefined;
    if (!column.search || !name) continue;
    const valueType = String(column.valueType ?? "text");
    if (valueType.toLowerCase().includes("range")) {
      const from = request.filters?.[`${name}.from`];
      const to = request.filters?.[`${name}.to`];
      const parse = (value: string | undefined) => {
        if (value === undefined) return null;
        if (valueType.toLowerCase().includes("date") || valueType.toLowerCase().includes("time")) return dayjs(value);
        return value;
      };
      if (from !== undefined || to !== undefined) values[name] = [parse(from), parse(to)];
    } else if (request.filters?.[name] !== undefined) {
      values[name] = request.filters[name];
    } else {
      values[name] = null;
    }
  }
  return values;
}

export function ResourceTable<T extends { id: string }>({ result, loading, error, request, onChange, onRetry, columns, actions, onSearch, searchInitialValues, rowSelection, tableAlertOptionRender, expandable, searchable = true, embedded = false, scroll = { x: "max-content" } }: {
  searchable?: boolean; rowSelection?: TableProps<T>["rowSelection"]; actions?: React.ReactNode;
  tableAlertOptionRender?: false;
  expandable?: TableProps<T>["expandable"];
  embedded?: boolean; scroll?: TableProps<T>["scroll"];
  onSearch?: (search: ResourceSearch) => void;
  searchInitialValues?: Record<string, unknown>;
  result?: PageResult<T>; loading: boolean; error: Error | null; request: ListRequest;
  onChange: (request: ListRequest) => void; onRetry: () => void; columns: ProColumns<T>[];
}) {
  const { t } = usePreferences();
  const tableRef = useRef<HTMLDivElement>(null);
  const searchFormRef = useRef<ProFormInstance | undefined>(undefined);
  const requestSearch = requestSearchValues(columns, request);
  const requestSearchKey = JSON.stringify(requestSearch);
  useEffect(() => {
    searchFormRef.current?.setFieldsValue({ ...requestSearchValues(columns, request), ...searchInitialValues });
  }, [requestSearchKey]);
  const searchSpan = useQueryFilterSpan(tableRef);
  // Search only fields deliberately selected by each page; data columns alone
  // do not automatically become query controls.
  // Use a stable actions key across pages; option columns follow the same right-edge policy.
  const tableColumns = columns.map(column => ({
    ...column,
    ...(column.key === "actions" || column.valueType === "option" ? {
      ...tableActionColumn,
      className: [column.className, tableActionColumn.className].filter(Boolean).join(" "),
    } : {}),
    search: column.search ?? false,
  }));
  const submitSearch = (values: Record<string, unknown>) => {
    const search = readColumnSearch(columns, values);
    if (onSearch) onSearch(search);
    else onChange({ ...request, page: 1, search: "", ...search });
  };
  const resetSearch = () => {
    const search: ResourceSearch = { filters: {}, filterModes: {} };
    if (onSearch) onSearch(search);
    else onChange({ ...request, page: 1, search: "", ...search });
    const clearedValues = Object.fromEntries(columns.flatMap(column =>
      column.search && typeof column.dataIndex === "string" ? [[column.dataIndex, null]] : [],
    ));
    searchFormRef.current?.setFieldsValue(clearedValues);
  };
  return <div className="resource-table" ref={tableRef} data-search-span={searchSpan}>
    <FeedbackBubble content={error ? t(error instanceof ApiError && error.status === 403 ? "forbidden" : "unavailable") : undefined}/>
    <ProTable<T>
      className="resource-data"
      formRef={searchFormRef}
      rowSelection={rowSelection}
      tableAlertOptionRender={tableAlertOptionRender}
      expandable={expandable}
      rowKey="id"
      loading={loading}
      dataSource={result?.items ?? []}
      search={searchable ? { className: "resource-search", defaultCollapsed: true, span: searchSpan, labelWidth: "auto" } : false}
      form={{ initialValues: { ...requestSearch, ...searchInitialValues } }}
      onSubmit={submitSearch}
      onReset={resetSearch}
      columns={tableColumns}
      cardBordered={false}
      cardProps={embedded ? { styles: { body: { padding: 0 } } } : undefined}
      defaultSize="small"
      options={{ reload: onRetry, density: true, setting: true }}
      toolBarRender={() => Children.toArray(actions)}
      scroll={scroll}
      pagination={{ current: request.page, pageSize: request.pageSize, total: result?.total ?? 0,
        showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS],
        showTotal: total => t("totalRecords", { count: total }),
        onChange: (page, pageSize) => onChange({ ...request, page: pageSize === request.pageSize ? page : 1, pageSize }) }} />
  </div>;
}
