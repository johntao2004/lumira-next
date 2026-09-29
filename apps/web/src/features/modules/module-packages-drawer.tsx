"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import {useMemo, useState} from "react";
import {useQuery, useQueryClient} from "@tanstack/react-query";
import {Alert, App, Button, Input, Space, Tag, Typography} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import {api, mutate} from "@/platform/api/client";
import type {ModulePackage} from "@/platform/modules/api-types";
import {messageKeyFor} from "@/platform/api/errors";
import {FormDrawer} from "@/platform/components/form-drawer";
import {PlatformDrawer} from "@/platform/components/platform-drawer";
import {ResourceTable} from "@/platform/components/resource-table";
import {usePreferences} from "@/platform/providers";
import type {MessageKey} from "@/platform/i18n/messages";
import {formatDateTime} from "@/platform/i18n/format";
import {useListRequest} from "@/platform/routing/use-list-request";
import {matchesListFilters} from "@/platform/api/pagination";

type StageFields = {
  moduleId: string;
  name: string;
  version: string;
  schemaVersion: string;
  apiVersion: string;
  artifactDigest: string;
  artifactRef: string;
  dependencies: string;
  permissions: string;
  routes: string;
  reason: string;
};
type ActionFields = {reason: string};
type Action = {kind: "activate" | "reject"; item: ModulePackage};

const split = (value: string | undefined) => (value ?? "").split(/[\n,]/).map(item => item.trim()).filter(Boolean);

export function ModulePackagesDrawer({open, onClose, canWrite}: {open: boolean; onClose: () => void; canWrite: boolean}) {
  const {t, locale} = usePreferences();
  const {message} = App.useApp();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [stageOpen, setStageOpen] = useState(false);
  const [action, setAction] = useState<Action | null>(null);
  const [pending, setPending] = useState(false);
  const [stageForm] = Form.useForm<StageFields>();
  const [actionForm] = Form.useForm<ActionFields>();
  const query = useQuery({
    queryKey: ["module-packages"],
    enabled: open,
    staleTime: 0,
    queryFn: ({signal}) => api<ModulePackage[]>("/platform/modules/packages", {signal}),
  });
  const rows = useMemo(() => (query.data ?? []).map(item => ({ ...item, moduleLabel: `${item.name} (${item.moduleId})` }))
    .filter(item => matchesListFilters(item, request.filters, request.filterModes)), [query.data, request.filters, request.filterModes]);
  const page = {items: rows.slice((request.page - 1) * request.pageSize, request.page * request.pageSize), total: rows.length, page: request.page, pageSize: request.pageSize};
  const packageErrorKeys = [
      "invalidModulePackage", "packageNotRegistered", "packageCodeVersionMismatch", "packageApiVersionMismatch",
      "packageDependencyConflict", "packageStateConflict", "packageAlreadyRejected", "staleRecord",
    ] as const satisfies readonly MessageKey[];
  const refresh = async () => {
    await Promise.all([client.invalidateQueries({queryKey: ["module-packages"]}), client.invalidateQueries({queryKey: ["modules"]}), client.invalidateQueries({queryKey: ["navigation-runtime"]})]);
  };
  const stage = async (values: StageFields) => {
    if (pending || !canWrite) return;
    setPending(true);
    try {
      await mutate<ModulePackage>("/platform/modules/packages", "POST", {
        manifest: {
          moduleId: values.moduleId.trim(), name: values.name.trim(), version: values.version.trim(),
          schemaVersion: values.schemaVersion.trim(), apiVersion: values.apiVersion.trim(), artifactDigest: values.artifactDigest.trim(),
          artifactRef: values.artifactRef.trim(), dependencies: split(values.dependencies), permissions: split(values.permissions), routes: split(values.routes),
        }, reason: values.reason,
      });
      setStageOpen(false); stageForm.resetFields(); await refresh();
    } catch (error) { void message.error(t(messageKeyFor(error, packageErrorKeys))); }
    finally { setPending(false); }
  };
  const lifecycle = async (values: ActionFields) => {
    if (!action || pending || !canWrite) return;
    setPending(true);
    try {
      await mutate<ModulePackage>(`/platform/modules/packages/${action.item.id}/${action.kind}`, "POST", {revision: action.item.revision, reason: values.reason});
      setAction(null); actionForm.resetFields(); await refresh();
    } catch (error) { void message.error(t(messageKeyFor(error, packageErrorKeys))); }
    finally { setPending(false); }
  };
  const formatDate = (value: string) => formatDateTime(value, locale, {dateStyle: "medium", timeStyle: "short"});
  const status = (item: ModulePackage) => {
    const labels: Record<ModulePackage["status"], MessageKey> = {STAGED: "packageStaged", ACTIVE: "packageActive", SUPERSEDED: "packageSuperseded", REJECTED: "packageRejected"};
    return <Tag color={item.status === "ACTIVE" ? "success" : item.status === "REJECTED" ? "error" : "default"}>{t(labels[item.status])}</Tag>;
  };
  return <>
    <PlatformDrawer open={open} onClose={onClose} title={t("modulePackages")} size={1080} destroyOnHidden>
      <ResourceTable<ModulePackage> result={page} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={() => void query.refetch()}
        searchInitialValues={{ moduleLabel: request.filters?.moduleLabel, status: request.filters?.status }} actions={canWrite && <Button type="primary" onClick={() => {stageForm.resetFields(); stageForm.setFieldsValue({apiVersion: "lumira.platform/v1"}); setStageOpen(true);}}>{t("registerPackage")}</Button>}
        columns={[
          {title: t("module"), dataIndex: "moduleLabel", search: true, render: (_: unknown, item: ModulePackage) => <span>{item.name} <Typography.Text type="secondary">({item.moduleId})</Typography.Text></span>},
          {title: t("packageVersion"), dataIndex: "packageVersion"},
          {title: t("status"), dataIndex: "status", search: true, valueEnum: { STAGED: { text: t("packageStaged") }, ACTIVE: { text: t("packageActive") }, SUPERSEDED: { text: t("packageSuperseded") }, REJECTED: { text: t("packageRejected") } }, render: (_: unknown, item: ModulePackage) => status(item)},
          {title: t("packageRuntimeReady"), dataIndex: "runtimeReady", valueEnum: { true: { text: t("packageRuntimeReady") }, false: { text: t("packageRuntimePending") } }, render: (_: unknown, item: ModulePackage) => <Tag color={item.runtimeReady ? "success" : "warning"}>{t(item.runtimeReady ? "packageRuntimeReady" : "packageRuntimePending")}</Tag>},
          {title: t("artifactDigest"), dataIndex: "artifactDigest", render: (_: unknown, item: ModulePackage) => <Typography.Text code title={item.artifactDigest}>{`${item.artifactDigest.slice(0, 12)}…`}</Typography.Text>},
          {title: t("eventTime"), dataIndex: "createdAt", valueType: "dateTimeRange", render: (_: unknown, item: ModulePackage) => formatDate(item.createdAt)},
          ...(canWrite ? [{title: t("actions"), search: false, render: (_: unknown, item: ModulePackage) => item.status === "STAGED" && <TableActions wrap>
            <Button type="link" disabled={!item.runtimeReady} onClick={() => {actionForm.resetFields(); setAction({kind: "activate", item});}}>{t("activatePackage")}</Button>
            <Button type="link" danger onClick={() => {actionForm.resetFields(); setAction({kind: "reject", item});}}>{t("rejectPackage")}</Button>
          </TableActions>}] : []),
        ]} />
    </PlatformDrawer>
    <FormDrawer open={stageOpen} title={t("registerPackage")} pending={pending} size={620} formId="module-package-stage" onClose={() => {if (!pending) setStageOpen(false);}}>
      <Alert type="info" showIcon title={t("packageLifecycleHint")} className="mb-4" />
      <Form form={stageForm} id="module-package-stage" layout="vertical" onFinish={stage}>
        <Form.Item name="moduleId" label={t("module")} rules={[{required: true}, {pattern: /^[a-z][a-z0-9-]{0,99}$/}]}><Input disabled={pending} /></Form.Item>
        <Form.Item name="name" label={t("name")} rules={[{required: true, whitespace: true, max: 200}]}><Input disabled={pending} /></Form.Item>
        <Space className="w-full" orientation="horizontal" wrap>
          <Form.Item name="version" label={t("packageVersion")} rules={[{required: true, pattern: /^\d+\.\d+\.\d+([-.+][0-9A-Za-z.-]+)?$/}]}><Input disabled={pending} /></Form.Item>
          <Form.Item name="schemaVersion" label={t("schemaVersion")} rules={[{required: true}]}><Input disabled={pending} /></Form.Item>
          <Form.Item name="apiVersion" label={t("apiVersion")} rules={[{required: true}]}><Input disabled={pending} /></Form.Item>
        </Space>
        <Form.Item name="artifactDigest" label={t("artifactDigest")} rules={[{required: true, len: 64, pattern: /^[a-fA-F0-9]{64}$/}]}><Input disabled={pending} /></Form.Item>
        <Form.Item name="artifactRef" label={t("artifactRef")} extra={t("packageManifestHint")} rules={[{required: true, max: 2048, pattern: /^(https|oci|s3):\/\/[^\s]+$/}]}><Input disabled={pending} /></Form.Item>
        <Form.Item name="dependencies" label={t("dependencies")}><Input.TextArea rows={2} placeholder="platform\nidentity" disabled={pending} /></Form.Item>
        <Form.Item name="permissions" label={t("packagePermissions")}><Input.TextArea rows={2} placeholder="module:feature:read" disabled={pending} /></Form.Item>
        <Form.Item name="routes" label={t("packageRoutes")}><Input.TextArea rows={2} placeholder="/dashboard/example" disabled={pending} /></Form.Item>
        <Form.Item name="reason" label={t("packageInstallReason")} rules={[{required: true, whitespace: true, max: 1000}]}><Input.TextArea rows={3} maxLength={1000} disabled={pending} /></Form.Item>
      </Form>
    </FormDrawer>
    <FormDrawer open={!!action} title={t(action?.kind === "activate" ? "activatePackage" : "rejectPackage")} pending={pending} formId="module-package-action" onClose={() => {if (!pending) setAction(null);}} submitDisabled={action?.kind === "activate" && !action.item.runtimeReady}>
      <Form form={actionForm} id="module-package-action" layout="vertical" onFinish={lifecycle}>
        <Form.Item name="reason" label={t("packageActivationReason")} rules={[{required: true, whitespace: true, max: 1000}]}><Input.TextArea rows={4} maxLength={1000} disabled={pending} /></Form.Item>
      </Form>
    </FormDrawer>
  </>;
}
