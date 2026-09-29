"use client";

import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Card, Space, Table, Tag } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api } from "@/platform/api/client";
import { usePreferences } from "@/platform/providers";
import { QueryFailure } from "@/platform/components/query-failure";
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZE_OPTIONS } from "@/platform/api/pagination";
import type { Capability } from "./types";

type CapabilityRow = { code: string; module: string; operations: string[]; permission: string; status: "ready" | "no-permission" | "planned"; link?: string };

export function AiCapabilitiesPage({ canManageModels, canReadUsers, canWriteUsers }: { canManageModels: boolean; canReadUsers: boolean; canWriteUsers: boolean }) {
  const { t } = usePreferences();
  const query = useQuery({ queryKey: ["ai-capabilities"], queryFn: ({ signal }) => api<Capability[]>("/platform/ai/capabilities", { signal }) });
  const connected: CapabilityRow[] = (query.data ?? []).map((capability) => ({
    ...capability,
    status: !capability.available ? "no-permission" : capability.permission === "platform:users:write" && !canWriteUsers ? "no-permission" : capability.permission === "platform:users:read" && !canReadUsers ? "no-permission" : "ready",
    link: "/dashboard/users",
  }));
  const planned: CapabilityRow[] = [
    { code: "roles.manage", module: "roleManagement", operations: ["query", "create", "update", "delete", "assign"], permission: "platform:roles:write", status: "planned" },
    { code: "organizations.manage", module: "organizationManagement", operations: ["query", "create", "update", "delete", "membership"], permission: "platform:organizations:write", status: "planned" },
    { code: "content.manage", module: "contentManagement", operations: ["module-specific"], permission: "module-permissions", status: "planned" },
    { code: "platform.manage", module: "systemSettings", operations: ["module-specific"], permission: "settings-permissions", status: "planned" },
  ];
  const columns = [
    { title: t("code"), dataIndex: "code", width: 180 },
    { title: t("module"), render: (_: unknown, row: CapabilityRow) => t(row.module as never), width: 160 },
    { title: t("operations"), render: (_: unknown, row: CapabilityRow) => <Space wrap>{row.operations.map((operation) => <Tag key={operation}>{operation === "module-specific" ? t("aiCapabilityModuleSpecific") : operation === "query" ? t("searchRecords") : operation === "create" ? t("createUser") : operation === "update" ? t("edit") : operation === "delete" || operation === "soft-delete" ? t("deleteRecord") : operation === "assign" ? t("assignRoles") : operation === "membership" ? t("organizations") : operation}</Tag>)}</Space>, width: 250 },
    { title: t("permission"), render: (_: unknown, row: CapabilityRow) => row.permission === "module-permissions" ? t("aiCapabilityModulePermissions") : row.permission === "settings-permissions" ? t("aiCapabilitySettingsPermissions") : row.permission, width: 210 },
    { title: t("status"), render: (_: unknown, row: CapabilityRow) => <Tag color={row.status === "ready" ? "success" : row.status === "planned" ? "default" : "warning"}>{t(row.status === "ready" ? "aiCapabilityRegistered" : row.status === "planned" ? "aiCapabilityNotReady" : "aiCapabilityUnavailable")}</Tag>, width: 160 },
    { title: t("actions"), render: (_: unknown, row: CapabilityRow) => row.link ? <TableActions><Button type="link" href={row.link}>{t("userList")}</Button></TableActions> : null, width: 120 },
  ];
  return <Space orientation="vertical" size="middle" className="w-full">
    <Alert type="info" showIcon message={t("aiCapabilitiesOverview")} />
    {query.error && <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />}
    <Card title={t("aiCapabilityRegistered")} extra={canManageModels && <Button href="/dashboard/ai-models">{t("aiModels")}</Button>}>
      <Table<CapabilityRow> rowKey="code" loading={query.isFetching} dataSource={connected} pagination={{ defaultPageSize: DEFAULT_TABLE_PAGE_SIZE, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS] }} scroll={{ x: 920 }} columns={columns} />
    </Card>
    <Card title={t("aiCapabilityNotReady")}>
      <Table<CapabilityRow> rowKey="code" dataSource={planned} pagination={{ defaultPageSize: DEFAULT_TABLE_PAGE_SIZE, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS] }} scroll={{ x: 920 }} columns={columns.filter((column) => column.title !== t("actions"))} />
    </Card>
  </Space>;
}
