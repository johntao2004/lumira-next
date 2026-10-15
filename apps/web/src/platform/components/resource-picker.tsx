"use client";
import { useState } from "react";
import { ConfigProvider, Input, Table, Typography } from "antd";
import { useQuery } from "@tanstack/react-query";
import { ApiError, api } from "../api/client";
import { usePreferences } from "../providers";
import { useListRequest } from "../routing/use-list-request";
import { listRequestParams, TABLE_PAGE_SIZE_OPTIONS, type PageResult } from "../api/pagination";
import { QueryFailure } from "./query-failure";

type ResourceOption = { id: string; name: string; enabled: boolean };

/** A form-compatible paginated multi-picker; selection survives page/search changes. */
export function ResourcePicker({ endpoint, value = [], onChange, id, max = 100 }: { endpoint: string; value?: string[]; onChange?: (ids: string[]) => void; id?: string; max?: number }) {
  const { t } = usePreferences();
  const { componentDisabled } = ConfigProvider.useConfig();
  const [request, setRequest] = useListRequest();
  const [search, setSearch] = useState("");
  const query = useQuery({
    queryKey: ["resource-picker", endpoint, request],
    queryFn: ({ signal }) => api<PageResult<ResourceOption>>(`${endpoint}?${listRequestParams(request)}`, { signal }),
  });

  return <div className="resource-picker">
    <ConfigProvider componentDisabled={false}>
      <div className="resource-picker-toolbar">
        <Input.Search
          id={id}
          aria-label={t("searchCodes")}
          placeholder={t("searchCodes")}
          value={search}
          allowClear
          maxLength={200}
          onChange={event => setSearch(event.target.value)}
          onPressEnter={event => event.preventDefault()}
          onSearch={text => setRequest(current => ({ ...current, page: 1, search: text.trim() }))}
        />
        <Typography.Text className="resource-picker-count" type="secondary" aria-live="polite">
          {t("selectedRoles", { count: value.length })} ({value.length} / {max})
        </Typography.Text>
      </div>
      {query.error && <QueryFailure title={t(query.error instanceof ApiError && query.error.status === 403 ? "forbidden" : "unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />}
      <Table<ResourceOption>
        size="medium"
        rowKey="id"
        loading={query.isFetching}
        dataSource={query.data?.items ?? []}
        columns={[{ title: t("name"), dataIndex: "name" }]}
        rowSelection={{
          selectedRowKeys: value,
          preserveSelectedRowKeys: true,
          onChange: keys => { if (!componentDisabled) onChange?.(keys.map(String).slice(0, max)); },
          getCheckboxProps: row => ({ disabled: componentDisabled || (!row.enabled && !value.includes(row.id)) || (!value.includes(row.id) && value.length >= max) }),
        }}
        pagination={{
          current: request.page,
          pageSize: request.pageSize,
          total: query.data?.total ?? 0,
          size: "small",
          responsive: true,
          showSizeChanger: true,
          pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS],
          showTotal: total => t("totalRecords", { count: total }),
          onChange: (page, pageSize) => setRequest(current => ({ ...current, page: pageSize === current.pageSize ? page : 1, pageSize })),
        }}
      />
    </ConfigProvider>
  </div>;
}
