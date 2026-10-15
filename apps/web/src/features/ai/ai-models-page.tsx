"use client";
import { messageKeyFor } from "@/platform/api/errors";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { App, Button, Card, ConfigProvider, Form, Input, Popconfirm, Select, Space, Spin, Switch, Table, Tag } from "antd";
import { TableActions, tableActionColumn } from "@/platform/components/table-actions";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { DrawerFormActions } from "@/platform/components/drawer-actions";
import { api, mutate } from "@/platform/api/client";
import { usePreferences } from "@/platform/providers";
import { QueryFailure } from "@/platform/components/query-failure";
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZE_OPTIONS } from "@/platform/api/pagination";
import type { ConnectionResult, ModelOption, Provider, ProviderKind } from "./types";
import { providerDefaultBaseUrl, providerKindLabel, providerRequiresCredential } from "./types";
import { discoveryErrorKey, modelOptionLabel, modelSelectionIsValid, selectDiscoveredModel, type DiscoveryErrorKey } from "./model-discovery";

type FormValues = { name: string; providerKind: ProviderKind; baseUrl?: string; modelName: string; enabled: boolean; credentialValue?: string };
type DiscoveryState = { models: ModelOption[]; status: "idle" | "success" | "empty" | "error"; error?: DiscoveryErrorKey; invalidated: boolean };
const initialDiscoveryState: DiscoveryState = { models: [], status: "idle", invalidated: false };
const providerPath = "/platform/ai/providers";

export function AiModelsPage({ canCreate, canUpdate, canDelete, canTest }: { canCreate: boolean; canUpdate: boolean; canDelete: boolean; canTest: boolean }) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [selected, setSelected] = useState<Provider | null | undefined>(undefined);
  const [testing, setTesting] = useState<string | null>(null);
  const [discoveringModels, setDiscoveringModels] = useState(false);
  const [discovery, setDiscovery] = useState<DiscoveryState>(initialDiscoveryState);
  const [saving, setSaving] = useState(false);
  const saveBusy = useRef(false);
  const discoverySequence = useRef(0);
  const discoveryAbort = useRef<AbortController | null>(null);
  const [form] = Form.useForm<FormValues>();
  const kind: ProviderKind | undefined = Form.useWatch("providerKind", form) ?? selected?.providerKind;
  const baseUrl = Form.useWatch("baseUrl", form) ?? selected?.baseUrl;
  const enabled = Form.useWatch("enabled", form) ?? selected?.enabled ?? true;
  const credentialValue: string | undefined = Form.useWatch("credentialValue", form);
  const selectedCredentialReusable = Boolean(selected && selected.providerKind === kind && selected.baseUrl === baseUrl && selected.credentialConfigured);
  const canSave = selected ? canUpdate : canCreate;
  const query = useQuery({ queryKey: ["ai-providers"], queryFn: ({ signal }) => api<Provider[]>(providerPath, { signal }), staleTime: 0 });
  const requiresModelDiscovery = useMemo(() => Boolean(kind) && kind !== "TYPESAFE_JEV", [kind]);
  const cancelDiscovery = useCallback(() => {
    discoverySequence.current += 1;
    discoveryAbort.current?.abort();
    discoveryAbort.current = null;
    setDiscoveringModels(false);
  }, []);

  useEffect(() => {
    if (selected === undefined) return;
    cancelDiscovery();
    form.setFieldsValue(selected ? {
      name: selected.name, providerKind: selected.providerKind, baseUrl: selected.baseUrl, modelName: selected.modelName,
      enabled: selected.enabled,
      credentialValue: "",
    } : { name: "", providerKind: undefined, baseUrl: "", modelName: "", enabled: true, credentialValue: "" });
    form.setFields((["name", "providerKind", "modelName", "baseUrl", "credentialValue"] as const).map(name => ({ name, errors: [] })));
    setDiscovery(initialDiscoveryState);
  }, [selected, form, cancelDiscovery]);

  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: ["ai-providers"] }),
    client.invalidateQueries({ queryKey: ["ai-provider-models"] }),
  ]);
  const resetEditor = () => { cancelDiscovery(); setSelected(undefined); setDiscovery(initialDiscoveryState); form.resetFields(); };
  const close = () => { if (!saveBusy.current) resetEditor(); };
  const save = async (values: FormValues) => {
    if (!canSave || saveBusy.current || discoveringModels) return;
    saveBusy.current = true;
    setSaving(true);
    const credential = values.credentialValue ? { action: "REPLACE", value: values.credentialValue }
      : selected && selected.providerKind !== values.providerKind && !providerRequiresCredential(values.providerKind)
        ? { action: "CLEAR" } : { action: "KEEP" };
    const body = { name: values.name, providerKind: values.providerKind, baseUrl: values.providerKind === "TYPESAFE_JEV" ? providerDefaultBaseUrl.TYPESAFE_JEV : values.baseUrl,
      modelName: values.modelName || (values.providerKind === "TYPESAFE_JEV" ? "jev-latest" : ""),
      enabled: values.enabled, version: selected?.version, credential };
    try {
      await mutate(selected ? `${providerPath}/${selected.id}` : providerPath, selected ? "PUT" : "POST", body);
      message.success(t("aiProviderSaved")); resetEditor(); await refresh();
    } catch (error) { message.error(t(messageKeyFor(error,["invalidAiProvider"],"invalidAiProvider"))); }
    finally { saveBusy.current = false; setSaving(false); }
  };
  const test = async (provider: Provider) => {
    if (!canTest) return;
    setTesting(provider.id);
    try {
      const result = await mutate<ConnectionResult>(`${providerPath}/${provider.id}/test`, "POST");
      message.success(result.configuredModelAvailable ? t("aiProviderTestOk", { count: result.models.length }) : t("aiProviderTestMissing"));
    } catch { message.error(t("aiProviderUnavailable")); }
    finally { setTesting(null); }
  };
  const discoverModels = useCallback(async () => {
    if (!canTest || !canSave || !enabled || form.getFieldValue("enabled") === false || !kind || kind === "TYPESAFE_JEV" || saveBusy.current) return;
    const sequence = ++discoverySequence.current;
    discoveryAbort.current?.abort();
    const controller = new AbortController();
    discoveryAbort.current = controller;
    setDiscoveringModels(true);
    setDiscovery((current) => ({ ...current, status: "idle", error: undefined }));
    form.setFields([{ name: "modelName", errors: [] }]);
    try {
      const values = await form.validateFields([
        "providerKind",
        "baseUrl",
        "credentialValue",
      ]);
      if (sequence !== discoverySequence.current) return;
      const reusableCredential = Boolean(selected && selected.providerKind === values.providerKind &&
        selected.baseUrl === values.baseUrl && selected.credentialConfigured);
      const result = await mutate<ConnectionResult>(`${providerPath}/discover-models`, "POST", {
        providerKind: values.providerKind,
        baseUrl: values.providerKind === "TYPESAFE_JEV" ? providerDefaultBaseUrl.TYPESAFE_JEV : values.baseUrl,
        credentialValue: values.credentialValue || undefined,
        providerId: reusableCredential ? selected?.id : undefined,
      }, controller.signal);
      if (sequence !== discoverySequence.current) return;
      const current = form.getFieldValue("modelName");
      const models = result.models ?? [];
      const modelName = selectDiscoveredModel(models, current);
      form.setFieldValue("modelName", modelName ?? "");
      form.setFields([{ name: "modelName", errors: [] }]);
      setDiscovery({ models, status: models.length ? "success" : "empty", invalidated: false });
    } catch (error) {
      if (sequence !== discoverySequence.current) return;
      if (error && typeof error === "object" && "errorFields" in error) return;
      setDiscovery((current) => ({ models: [], status: "error", error: discoveryErrorKey(error), invalidated: current.invalidated }));
    } finally {
      if (sequence === discoverySequence.current) setDiscoveringModels(false);
      if (discoveryAbort.current === controller) discoveryAbort.current = null;
    }
  }, [enabled, kind, selected, form, canSave, canTest]);

  useEffect(() => {
    if (selected === undefined || !canTest || saving || !enabled || form.getFieldValue("enabled") === false || !kind || kind === "TYPESAFE_JEV" || !baseUrl?.trim()) {
      if (!enabled || form.getFieldValue("enabled") === false) cancelDiscovery();
      return;
    }
    if (providerRequiresCredential(kind) && !credentialValue?.trim() && !selectedCredentialReusable) return;
    try {
      const url = new URL(baseUrl.trim());
      if (!["http:", "https:"].includes(url.protocol) || !url.hostname) return;
    } catch { return; }
    const timeout = setTimeout(() => void discoverModels(), 650);
    return () => { clearTimeout(timeout); cancelDiscovery(); };
  }, [selected, canTest, saving, enabled, kind, baseUrl, credentialValue, selectedCredentialReusable, discoverModels, cancelDiscovery, form]);
  const remove = async (provider: Provider) => {
    if (!canDelete) return;
    try { await mutate(`${providerPath}/${provider.id}?version=${provider.version}`, "DELETE"); await refresh(); }
    catch { message.error(t("unavailable")); }
  };

  const kindOptions = (Object.keys(providerKindLabel) as ProviderKind[]).map((value) => ({ value, label: t(providerKindLabel[value] as never) }));
  const modelOptions = discovery.models.map((model) => ({ value: model.id, label: modelOptionLabel(model) }));
  const keepSavedModel = !!selected?.modelName && !discovery.invalidated &&
    (discovery.status === "idle" || discovery.status === "error") &&
    !modelOptions.some((option) => option.value === selected.modelName);
  if (keepSavedModel && selected) modelOptions.unshift({ value: selected.modelName, label: t("aiModelSavedSelection", { model: selected.modelName }) });
  const modelDiscoveryDescription = canTest && requiresModelDiscovery && enabled ? <div className="ai-model-discovery-row">
    {discoveringModels ? <span role="status"><Spin size="small" /> {t("aiModelDiscovering")}</span> : discovery.status === "idle" && <span>{t("aiModelAutoDiscoveryHint")}</span>}
    {discovery.status === "success" && <span role="status">{t("aiModelDiscoverySuccess", { count: discovery.models.length })}</span>}
    {discovery.status === "empty" && <span role="alert">{t("aiNoDiscoveredModels")} <Button type="link" size="small" onClick={() => void discoverModels()}>{t("retry")}</Button></span>}
    {discovery.status === "error" && discovery.error && <span role="alert">{t(discovery.error)} <Button type="link" size="small" onClick={() => void discoverModels()}>{t("retry")}</Button></span>}
  </div> : undefined;
  return <Space orientation="vertical" size="middle" className="w-full ai-models-page">
    {query.error ? <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} /> : <Card className="ai-models-card" title={t("aiConfiguredProviders")} extra={canCreate && <Button type="primary" onClick={() => setSelected(null)}>{t("aiProviderAdd")}</Button>}>
      <Table<Provider> className="ai-models-table" size="medium" rowKey="id" loading={query.isFetching} dataSource={query.data ?? []} pagination={{ defaultPageSize: DEFAULT_TABLE_PAGE_SIZE, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS] }} locale={{ emptyText: t("aiNoProviders") }} scroll={{ x: "max-content" }} columns={[
        { title: t("aiProviderName"), dataIndex: "name", render: (value: string) => <span className="ai-model-value">{value}</span> },
        { title: t("aiProviderKind"), render: (_, row) => <Tag>{t(providerKindLabel[row.providerKind] as never)}</Tag> },
        { title: t("aiModelName"), dataIndex: "modelName", render: (value: string) => <span className="ai-model-value">{value}</span> },
        { title: t("aiCredentialState"), render: (_, row) => {
          const credentialRequired = providerRequiresCredential(row.providerKind);
          return <Tag color={row.credentialConfigured || !credentialRequired ? "success" : "warning"}>{t(row.credentialConfigured ? "aiCredentialConfigured" : credentialRequired ? "aiCredentialMissing" : "aiCredentialOptional")}</Tag>;
        } },
        { title: t("status"), render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
        ...(canTest || canUpdate || canDelete ? [{ title: t("actions"), key: "actions", ...tableActionColumn, render: (_: unknown, row: Provider) => <TableActions wrap>
          {canTest && <Button type="link" loading={testing === row.id} onClick={() => void test(row)}>{t("aiProviderTest")}</Button>}
          {canUpdate && <Button type="link" onClick={() => setSelected(row)}>{t("edit")}</Button>}
          {canDelete && <Popconfirm title={t("aiProviderDeleteConfirm")} onConfirm={() => void remove(row)}><Button type="link" danger>{t("deleteRecord")}</Button></Popconfirm>}
        </TableActions> }] : []),
      ]} />
    </Card>}

    <PlatformDrawer className="ai-provider-drawer" size={640} open={selected !== undefined} title={t(selected ? "aiProviderEdit" : "aiProviderAdd")} onClose={close} closable={{ disabled: saving }} destroyOnHidden footer={<DrawerFormActions formId="ai-provider-editor" onClose={close} pending={saving} submitDisabled={!canSave || !kind || discoveringModels} />}>
      <Form<FormValues> id="ai-provider-editor" className="platform-form ai-provider-form" form={form} layout="vertical" disabled={saving || !canSave} onValuesChange={(changed) => {
        if (changed.providerKind !== undefined) {
          cancelDiscovery();
        }
        if (changed.enabled === false) {
          cancelDiscovery();
        }
        if (changed.baseUrl !== undefined || changed.credentialValue !== undefined) {
          cancelDiscovery();
          setDiscovery({ models: [], status: "idle", invalidated: true });
          form.setFieldValue("modelName", "");
          form.setFields([{ name: "modelName", errors: [] }]);
        }
      }} onFinish={(values) => void save(values)}>
        <Form.Item name="enabled" label={t("aiEnabled")} valuePropName="checked"><Switch /></Form.Item>
        <ConfigProvider componentDisabled={saving || !enabled}>
        <Form.Item name="providerKind" label={t("aiProviderKind")} rules={[{ required: true }]}><Select options={kindOptions} disabled={!!selected || !enabled} onChange={(value: ProviderKind) => {
          cancelDiscovery();
          const previousKind = kind;
          const currentName = form.getFieldValue("name");
          if (!currentName || (previousKind && currentName === t(providerKindLabel[previousKind] as never))) {
            form.setFieldValue("name", t(providerKindLabel[value] as never));
          }
          form.setFieldsValue({ baseUrl: providerDefaultBaseUrl[value], modelName: value === "TYPESAFE_JEV" ? "jev-latest" : "", credentialValue: undefined });
          form.setFields((["name", "modelName", "baseUrl", "credentialValue"] as const).map(name => ({ name, errors: [] })));
          setDiscovery({ models: [], status: "idle", invalidated: value !== "TYPESAFE_JEV" });
        }} /></Form.Item>
        {kind !== "TYPESAFE_JEV" && <Form.Item name="baseUrl" label={t("aiBaseUrl")} extra={kind ? (kind === "OLLAMA" || kind === "LM_STUDIO" ? t("aiLocalOnly") : t("aiCloudCompatibleHint")) : undefined} rules={[{ required: true }, { type: "url" }]}><Input disabled={!kind || !enabled} placeholder={kind ? providerDefaultBaseUrl[kind] || t("aiCustomBaseUrlPlaceholder") : undefined} /></Form.Item>}
        {kind === "TYPESAFE_JEV" && <Form.Item label={t("aiBaseUrl")}><Input value={providerDefaultBaseUrl.TYPESAFE_JEV} disabled /></Form.Item>}
        <Form.Item name="name" label={t("aiProviderName")} rules={[{ required: true, max: 120 }]}><Input maxLength={120} /></Form.Item>
        <Form.Item name="credentialValue" label={t("aiCredential")} rules={[{ required: Boolean(kind && providerRequiresCredential(kind)) && !selectedCredentialReusable, whitespace: true, message: t("aiProviderCredentialRequired") }, { max: 8192 }]} preserve={false}>
          <Input.Password disabled={!kind || !enabled} autoComplete="new-password" maxLength={8192} placeholder={selectedCredentialReusable ? t("aiCredentialKeepIfEmpty") : t("aiCredentialPlaceholder")} />
        </Form.Item>
        {kind !== "TYPESAFE_JEV" ? <Form.Item name="modelName" label={t("aiModelName")} extra={modelDiscoveryDescription} required rules={[{ required: true, message: t("aiModelSelectionRequired") }, { max: 256 }, { validator: async (_, value: string | undefined) => {
          if (value === undefined || value === "") return;
          if (modelSelectionIsValid(selected?.modelName, value, discovery)) return;
          throw new Error(t("aiModelSelectionRequired"));
        } }]}>
          {canTest ? <Select
            showSearch
            allowClear
            loading={discoveringModels}
            disabled={!kind || !enabled}
            optionFilterProp="label"
            options={modelOptions}
            placeholder={t("aiModelNamePlaceholder")}
            notFoundContent={discovery.status === "success" ? t("aiNoDiscoveredModels") : t("aiModelNamePlaceholder")}
          /> : <Input disabled={!kind || !enabled} placeholder={t("aiModelNamePlaceholder")} />}
        </Form.Item> : <Form.Item name="modelName" label={t("aiModelName")}><Input disabled /></Form.Item>}

        </ConfigProvider>
      </Form>
    </PlatformDrawer>
  </Space>;
}
