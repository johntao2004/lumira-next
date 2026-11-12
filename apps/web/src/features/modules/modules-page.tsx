"use client";

import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, App, Button, Card, Descriptions, Empty, Input, Pagination, Select, Space, Spin, Switch, Tabs, Tag, Tooltip } from "antd";
import { usePluginManager } from "./plugin-manager";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { api, mutate, ApiError } from "@/platform/api/client";
import type { ModuleInfo as ApiModuleInfo } from "@/platform/modules/api-types";
import type { PluginView } from "@/platform/modules/plugin-runtime";
import { ResourceTable } from "@/platform/components/resource-table";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { matchesListFilters, TABLE_PAGE_SIZE_OPTIONS } from "@/platform/api/pagination";
import { useListRequest } from "@/platform/routing/use-list-request";
import { useTabAnchor } from "@/platform/routing/use-tab-anchor";
import { accountAvatarKey } from "@/platform/api/account-avatar-contract";
import "./modules-page.css";

const moduleErrorKeys = ["requiredModule", "moduleDependencyConflict", "invalidModuleChange", "staleRecord", "pluginFixedGraph", "pluginClusterLifecycleUnsupported", "pluginClusterOperationActive", "pluginGraphChanged"] as const satisfies readonly MessageKey[];

type ModuleInfo = ApiModuleInfo & { core: boolean; moduleDisplayName: string };
type PluginManifestWithDependencies = PluginView["manifest"] & { dependencies?: readonly { id: string; version?: string }[] };
type ManagedModule = ApiModuleInfo & {
  core: false;
  hasModuleState: boolean;
  installed: boolean;
  plugin: PluginView | null;
  moduleDisplayName: string;
};

function isCoreModule(row: ApiModuleInfo) {
  return Boolean(row.core ?? row.required);
}

function pluginDependencies(plugin: PluginView) {
  return (plugin.manifest as PluginManifestWithDependencies).dependencies?.map(dependency => dependency.id);
}

/** Merge the registered module state with every imported plugin artifact by id. */
function mergeManageableModules(modules: ApiModuleInfo[], pluginViews: PluginView[]): Omit<ManagedModule, "moduleDisplayName">[] {
  const modulesById = new Map(modules.map(module => [module.id, module]));
  const pluginsById = new Map(pluginViews.map(plugin => [plugin.id, plugin]));
  const merged = new Map<string, Omit<ManagedModule, "moduleDisplayName">>();

  for (const module of modules) {
    if (isCoreModule(module)) continue;
    const plugin = pluginsById.get(module.id) ?? null;
    const dependencies = plugin ? pluginDependencies(plugin) ?? module.dependencies : module.dependencies;
    merged.set(module.id, {
      ...module,
      name: plugin?.name ?? module.name,
      version: plugin?.version ?? module.version,
      dependencies,
      enabled: plugin && !plugin.installed ? false : module.enabled,
      core: false,
      hasModuleState: true,
      installed: plugin ? plugin.installed : true,
      plugin,
    });
  }

  for (const plugin of pluginViews) {
    const module = modulesById.get(plugin.id);
    if (merged.has(plugin.id) || module && isCoreModule(module)) continue;
    merged.set(plugin.id, {
      id: plugin.id,
      name: plugin.name,
      version: plugin.version,
      dependencies: pluginDependencies(plugin) ?? [],
      required: false,
      core: false,
      enabled: plugin.installed && plugin.enabled,
      stateVersion: 0,
      hasModuleState: false,
      installed: plugin.installed,
      plugin,
    });
  }

  return [...merged.values()];
}

export function ModulesPage({ canInstall, canUpdate, canUninstall }: { canInstall: boolean; canUpdate: boolean; canUninstall: boolean }) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [request, setRequest] = useListRequest();
  const [moduleTab, setModuleTab] = useTabAnchor("", ["core", "manageable"], "core");
  const [pendingSwitch, setPendingSwitch] = useState<{ id: string; enabled: boolean } | null>(null);
  const [selectedModuleId, setSelectedModuleId] = useState<string | null>(null);
  const plugins = usePluginManager(canInstall, canUninstall);
  const query = useQuery({ queryKey: ["modules"], queryFn: ({ signal }) => api<ApiModuleInfo[]>("/platform/modules", { signal }), staleTime: 0 });
  const { pending, run, isPending } = useSubmitAction({
    errorKeys: moduleErrorKeys,
    onError: async key => {
      void message.error(t(key));
      await Promise.all([query.refetch(), plugins.query.refetch()]);
    },
  });

  useEffect(() => { setRequest(current => ({ ...current, page: 1 })); }, [moduleTab, setRequest]);

  const moduleName = (row: ApiModuleInfo) => row.id === "identity" ? t("identityModule")
    : row.id === "platform" ? t("platformModule")
      : row.id === "audit-console" ? t("auditConsole") : row.name;
  const coreRows: ModuleInfo[] = (query.data ?? []).map(row => ({ ...row, core: isCoreModule(row), moduleDisplayName: moduleName(row) }))
    .filter(row => row.core && matchesListFilters(row, request.filters, request.filterModes));
  const manageableRows: ManagedModule[] = mergeManageableModules(query.data ?? [], plugins.query.data ?? [])
    .map(row => ({ ...row, moduleDisplayName: moduleName(row) }))
    .filter(row => matchesListFilters(row, request.filters, request.filterModes));
  const pageRows = manageableRows.slice((request.page - 1) * request.pageSize, request.page * request.pageSize);
  const selectedModule = selectedModuleId ? manageableRows.find(row => row.id === selectedModuleId) ?? null : null;
  const pageError = query.error ?? plugins.query.error;

  const updateFilter = (field: string, value: string | undefined, mode: "contains" | "exact") => {
    setRequest(current => {
      const filters = { ...current.filters };
      const filterModes = { ...current.filterModes };
      if (value === undefined || value === "") {
        delete filters[field];
        delete filterModes[field];
      } else {
        filters[field] = value;
        filterModes[field] = mode;
      }
      return { ...current, page: 1, filters, filterModes };
    });
  };

  const save = async (row: ManagedModule, enabled: boolean) => {
    if (row.core || !row.hasModuleState || row.plugin && !row.plugin.installed || !canUpdate || pending || isPending() || plugins.pending) return;
    setPendingSwitch({ id: row.id, enabled });
    try {
      await run(async () => {
        await mutate(`/platform/modules/${encodeURIComponent(row.id)}/state`, "PUT", { enabled, version: row.stateVersion });
        await Promise.all([
          client.invalidateQueries({ queryKey: ["modules"] }),
          client.invalidateQueries({ queryKey: ["plugins"] }),
          client.invalidateQueries({ queryKey: ["session"] }),
          client.invalidateQueries({ queryKey: ["permission-catalog"] }),
          client.invalidateQueries({ queryKey: accountAvatarKey }),
        ]);
        client.removeQueries({ queryKey: ["audit"] });
        if (typeof BroadcastChannel !== "undefined") {
          const channel = new BroadcastChannel("lumira:modules");
          channel.postMessage("changed");
          channel.close();
        }
        void message.success(t("settingsSaved"));
      });
    } finally {
      setPendingSwitch(current => current?.id === row.id ? null : current);
    }
  };

  const moduleStatus = (row: ManagedModule) => {
    if (row.plugin && !row.plugin.installed) return { label: t("pluginUninstalled"), color: "default" as const };
    if (row.plugin?.status === "FAILED") return { label: t("moduleUnavailable"), color: "error" as const };
    const enabled = row.plugin ? row.plugin.status === "RUNNING" : row.enabled;
    return { label: t(enabled ? "enabled" : "disabled"), color: enabled ? "success" as const : "default" as const };
  };

  const lifecycleBlocked = plugins.pending || !plugins.lifecycleChangesAllowed;
  const updateBlocked = plugins.query.isPending || plugins.query.isError || pending || plugins.pending
    || !plugins.lifecycleChangesAllowed || Boolean(plugins.lifecycleBlockReason) || !canUpdate;

  return <>
    {plugins.drawer}
    {plugins.lifecycleBlockReason && <Alert className="mb-4" type="info" showIcon title={t(plugins.lifecycleBlockReason)} />}
    <Tabs activeKey={moduleTab} onChange={key => { setModuleTab(key as "core" | "manageable"); setRequest(current => ({ ...current, page: 1 })); }} items={[
      { key: "core", label: t("coreModules") },
      { key: "manageable", label: t("manageableModules") },
    ]} />

    {moduleTab === "core" ? <ResourceTable<ModuleInfo>
      searchInitialValues={{ moduleDisplayName: request.filters?.moduleDisplayName, enabled: request.filters?.enabled }}
      result={{ items: coreRows.slice((request.page - 1) * request.pageSize, request.page * request.pageSize), total: coreRows.length, page: request.page, pageSize: request.pageSize }}
      loading={query.isFetching}
      error={query.error}
      request={request}
      onChange={setRequest}
      onRetry={() => void query.refetch()}
      columns={[
        { title: t("module"), dataIndex: "moduleDisplayName", search: true, render: (_, row) => moduleName(row) },
        { title: t("id"), dataIndex: "id" },
        { title: t("version"), dataIndex: "version" },
        { title: t("status"), dataIndex: "enabled", search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
        { title: t("dependencies"), dataIndex: "dependencies", width: 280, render: (_, row) => {
          const dependencies = row.dependencies.join(", ") || "—";
          return <Tooltip title={dependencies} placement="topLeft"><span tabIndex={0} aria-label={`${t("dependencies")}: ${dependencies}`} style={{ display: "block", maxWidth: 260, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{dependencies}</span></Tooltip>;
        } },
      ]}
    /> : <section className="modules-manageable" aria-label={t("manageableModules")}>
      <FeedbackBubble content={pageError ? t(pageError instanceof ApiError && pageError.status === 403 ? "forbidden" : "unavailable") : undefined} />
      <div className="modules-manageable__toolbar">
        <Input
          aria-label={t("module")}
          placeholder={t("module")}
          allowClear
          value={request.filters?.moduleDisplayName ?? ""}
          onChange={event => updateFilter("moduleDisplayName", event.target.value || undefined, "contains")}
        />
        <Select
          aria-label={t("status")}
          placeholder={t("status")}
          allowClear
          value={request.filters?.enabled}
          options={[{ label: t("enabled"), value: "true" }, { label: t("disabled"), value: "false" }]}
          onChange={value => updateFilter("enabled", value, "exact")}
        />
        <Space className="modules-manageable__toolbar-actions" wrap>
          {pageError && <Button loading={query.isFetching || plugins.query.isFetching} onClick={() => { void query.refetch(); void plugins.query.refetch(); }}>{t("reload")}</Button>}
          {plugins.controls}
        </Space>
      </div>

      <Spin spinning={query.isFetching || plugins.query.isFetching}>
        {manageableRows.length ? <div className="modules-card-grid">
          {pageRows.map(row => {
            const switchDisabled = updateBlocked || !row.hasModuleState || row.core || row.plugin !== null && !row.installed;
            const displayedEnabled = pendingSwitch?.id === row.id ? pendingSwitch.enabled : row.enabled;
            const installDisabled = lifecycleBlocked || plugins.query.isPending || plugins.query.isError;
            return <Card key={row.id} className="modules-card" size="small">
              <button type="button" className="modules-card__details-trigger" onClick={() => setSelectedModuleId(row.id)} aria-label={`${row.moduleDisplayName} ${t("details")}`}>
                <span className="modules-card__heading">
                  <span className="modules-card__name">{row.moduleDisplayName}</span>
                  {row.plugin && <Tag color={row.installed ? "blue" : "default"}>{row.installed ? t("installed") : t("pluginUninstalled")}</Tag>}
                </span>
                <span className="modules-card__id">{row.id}</span>
                <span className="modules-card__summary">
                  <span>{t("version")} {row.version}</span>
                </span>
              </button>
              <div className="modules-card__operations">
                <div className="modules-card__switch-label">
                  <span>{t("status")}</span>
                  <Switch
                    checked={displayedEnabled}
                    loading={pending && pendingSwitch?.id === row.id}
                    disabled={switchDisabled}
                    aria-label={`${t(displayedEnabled ? "disableModule" : "enableModule")} ${row.moduleDisplayName}`}
                    onChange={enabled => void save(row, enabled)}
                  />
                </div>
                {row.plugin && <Space size="small" wrap>
                  {!row.installed && canInstall && <Button size="small" disabled={installDisabled} loading={plugins.pending} onClick={() => void plugins.prepare(row.id, "install")}>{t("installPlugin")}</Button>}
                  {row.installed && canUninstall && <Button size="small" disabled={installDisabled} loading={plugins.pending} onClick={() => void plugins.prepare(row.id, "uninstall")}>{t("uninstallPlugin")}</Button>}
                </Space>}
              </div>
            </Card>;
          })}
        </div> : !query.isFetching && !plugins.query.isFetching && <Empty />}
      </Spin>

      {manageableRows.length > 0 && <div className="modules-manageable__pagination">
        <Pagination
          size="small"
          current={request.page}
          pageSize={request.pageSize}
          pageSizeOptions={[...TABLE_PAGE_SIZE_OPTIONS]}
          total={manageableRows.length}
          showSizeChanger
          showTotal={total => t("totalRecords", { count: total })}
          onChange={(page, pageSize) => setRequest(current => ({ ...current, page: pageSize === current.pageSize ? page : 1, pageSize }))}
        />
      </div>}
    </section>}

    <PlatformDrawer open={Boolean(selectedModule)} title={t("details")} onClose={() => setSelectedModuleId(null)} width={560}>
      {selectedModule && <Descriptions size="small" bordered column={1} items={[
        { key: "id", label: t("id"), children: selectedModule.id },
        { key: "name", label: t("module"), children: selectedModule.moduleDisplayName },
        { key: "version", label: t("version"), children: selectedModule.version },
        { key: "dependencies", label: t("dependencies"), children: selectedModule.dependencies.join(", ") || "—" },
        { key: "type", label: t("moduleType"), children: t("manageableModules") },
        { key: "installed", label: t("installation"), children: selectedModule.plugin ? selectedModule.installed ? t("installed") : t("pluginUninstalled") : t("registered") },
        { key: "status", label: t("status"), children: <Tag color={moduleStatus(selectedModule).color}>{moduleStatus(selectedModule).label}</Tag> },
      ]} />}
    </PlatformDrawer>
  </>;
}
