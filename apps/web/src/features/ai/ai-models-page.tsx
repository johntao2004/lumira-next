"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { App, Button, Card, Form, Input, Popconfirm, Select, Space, Switch, Table, Tag } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
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

export function AiModelsPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [selected, setSelected] = useState<Provider | null | undefined>(undefined);
  const [testing, setTesting] = useState<string | null>(null);
  const [discoveringModels, setDiscoveringModels] = useState(false);
  const [discovery, setDiscovery] = useState<DiscoveryState>(initialDiscoveryState);
  const discoverySequence = useRef(0);
  const [form] = Form.useForm<FormValues>();
  const kind = Form.useWatch("providerKind", form) ?? selected?.providerKind ?? "OLLAMA";
  const baseUrl = Form.useWatch("baseUrl", form) ?? selected?.baseUrl;
  const selectedCredentialReusable = selected?.providerKind === kind && selected.baseUrl === baseUrl && selected.credentialConfigured;
  const query = useQuery({ queryKey: ["ai-providers"], queryFn: ({ signal }) => api<Provider[]>(providerPath, { signal }), staleTime: 0 });
  const requiresModelDiscovery = useMemo(() => kind !== "TYPESAFE_JEV", [kind]);

  useEffect(() => {
    if (selected === undefined) return;
    discoverySequence.current += 1;
    setDiscoveringModels(false);
    form.setFieldsValue(selected ? {
      name: selected.name, providerKind: selected.providerKind, baseUrl: selected.baseUrl, modelName: selected.modelName,
      enabled: selected.enabled,
      credentialValue: "",
    } : { name: "", providerKind: "OLLAMA", baseUrl: providerDefaultBaseUrl.OLLAMA, modelName: "", enabled: true, credentialValue: "" });
    form.setFields([{ name: "modelName", errors: [] }, { name: "baseUrl", errors: [] }, { name: "credentialValue", errors: [] }]);
    setDiscovery(initialDiscoveryState);
  }, [selected, form]);

  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: ["ai-providers"] }),
    client.invalidateQueries({ queryKey: ["ai-provider-models"] }),
  ]);
  const close = () => { discoverySequence.current += 1; setDiscoveringModels(false); setSelected(undefined); setDiscovery(initialDiscoveryState); form.resetFields(); };
  const save = async (values: FormValues) => {
    const credential = values.credentialValue ? { action: "REPLACE", value: values.credentialValue }
      : selected && selected.providerKind !== values.providerKind && !providerRequiresCredential(values.providerKind)
        ? { action: "CLEAR" } : { action: "KEEP" };
    const body = { name: values.name, providerKind: values.providerKind, baseUrl: values.providerKind === "TYPESAFE_JEV" ? providerDefaultBaseUrl.TYPESAFE_JEV : values.baseUrl,
      modelName: values.modelName || (values.providerKind === "TYPESAFE_JEV" ? "jev-latest" : ""),
      enabled: values.enabled, version: selected?.version, credential };
    try {
      await mutate(selected ? `${providerPath}/${selected.id}` : providerPath, selected ? "PUT" : "POST", body);
      message.success(t("aiProviderSaved")); close(); await refresh();
    } catch { message.error(t("invalidAiProvider")); }
  };
  const test = async (provider: Provider) => {
    setTesting(provider.id);
    try {
      const result = await mutate<ConnectionResult>(`${providerPath}/${provider.id}/test`, "POST");
      message.success(result.configuredModelAvailable ? t("aiProviderTestOk", { count: result.models.length }) : t("aiProviderTestMissing"));
    } catch { message.error(t("aiProviderUnavailable")); }
    finally { setTesting(null); }
  };
  const discoverModels = async () => {
    const sequence = ++discoverySequence.current;
    setDiscoveringModels(true);
    setDiscovery((current) => ({ ...current, status: "idle", error: undefined }));
    form.setFields([{ name: "modelName", errors: [] }]);
    try {
      const values = await form.validateFields([
        "providerKind",
        ...(kind === "TYPESAFE_JEV" ? [] : ["baseUrl" as const]),
        "credentialValue",
      ]);
      if (sequence !== discoverySequence.current) return;
      const reusableCredential = selected?.providerKind === values.providerKind &&
        selected.baseUrl === values.baseUrl && selected.credentialConfigured;
      const result = await mutate<ConnectionResult>(`${providerPath}/discover-models`, "POST", {
        providerKind: values.providerKind,
        baseUrl: values.providerKind === "TYPESAFE_JEV" ? providerDefaultBaseUrl.TYPESAFE_JEV : values.baseUrl,
        credentialValue: values.credentialValue || undefined,
        providerId: reusableCredential ? selected.id : undefined,
      });
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
    }
  };
  const remove = async (provider: Provider) => {
    try { await mutate(`${providerPath}/${provider.id}?version=${provider.version}`, "DELETE"); await refresh(); }
    catch { message.error(t("unavailable")); }
  };

  const kindOptions = (Object.keys(providerKindLabel) as ProviderKind[]).map((value) => ({ value, label: t(providerKindLabel[value] as never) }));
  const modelOptions = discovery.models.map((model) => ({ value: model.id, label: modelOptionLabel(model) }));
  const keepSavedModel = !!selected?.modelName && !discovery.invalidated &&
    (discovery.status === "idle" || discovery.status === "error") &&
    !modelOptions.some((option) => option.value === selected.modelName);
  if (keepSavedModel && selected) modelOptions.unshift({ value: selected.modelName, label: t("aiModelSavedSelection", { model: selected.modelName }) });
  return <Space orientation="vertical" size="middle" className="w-full ai-models-page">
    {query.error ? <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} /> : <Card className="ai-models-card" title={t("aiConfiguredProviders")} extra={canWrite && <Button type="primary" onClick={() => setSelected(null)}>{t("aiProviderAdd")}</Button>}>
      <Table<Provider> className="ai-models-table" rowKey="id" loading={query.isFetching} dataSource={query.data ?? []} pagination={{ defaultPageSize: DEFAULT_TABLE_PAGE_SIZE, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS] }} locale={{ emptyText: t("aiNoProviders") }} scroll={{ x: "max-content" }} columns={[
        { title: t("aiProviderName"), dataIndex: "name", render: (value: string) => <span className="ai-model-value">{value}</span> },
        { title: t("aiProviderKind"), render: (_, row) => <Tag>{t(providerKindLabel[row.providerKind] as never)}</Tag> },
        { title: t("aiModelName"), dataIndex: "modelName", render: (value: string) => <span className="ai-model-value">{value}</span> },
        { title: t("aiCredentialState"), render: (_, row) => {
          const credentialRequired = providerRequiresCredential(row.providerKind);
          return <Tag color={row.credentialConfigured || !credentialRequired ? "success" : "warning"}>{t(row.credentialConfigured ? "aiCredentialConfigured" : credentialRequired ? "aiCredentialMissing" : "aiCredentialOptional")}</Tag>;
        } },
        { title: t("status"), render: (_, row) => <Tag color={row.enabled ? "success" : "default"}>{t(row.enabled ? "enabled" : "disabled")}</Tag> },
        ...(canWrite ? [{ title: t("actions"), render: (_: unknown, row: Provider) => <TableActions wrap>
          <Button type="link" loading={testing === row.id} onClick={() => void test(row)}>{t("aiProviderTest")}</Button>
          <Button type="link" onClick={() => setSelected(row)}>{t("edit")}</Button>
          <Popconfirm title={t("aiProviderDeleteConfirm")} onConfirm={() => void remove(row)}><Button type="link" danger>{t("deleteRecord")}</Button></Popconfirm>
        </TableActions> }] : []),
      ]} />
    </Card>}

    <PlatformDrawer className="ai-provider-drawer" size={640} open={selected !== undefined} title={t(selected ? "aiProviderEdit" : "aiProviderAdd")} onClose={close} destroyOnHidden>
      <Form<FormValues> className="ai-provider-form" form={form} layout="vertical" onValuesChange={(changed) => {
        if (changed.providerKind !== undefined) {
          discoverySequence.current += 1;
          setDiscoveringModels(false);
        }
        if (changed.baseUrl !== undefined || changed.credentialValue !== undefined) {
          discoverySequence.current += 1;
          setDiscoveringModels(false);
          setDiscovery({ models: [], status: "idle", invalidated: true });
          form.setFieldValue("modelName", "");
          form.setFields([{ name: "modelName", errors: [] }]);
        }
      }} onFinish={(values) => void save(values)}>
        <Form.Item name="name" label={t("aiProviderName")} rules={[{ required: true, max: 120 }]}><Input maxLength={120} /></Form.Item>
        <Form.Item name="providerKind" label={t("aiProviderKind")} rules={[{ required: true }]}><Select options={kindOptions} disabled={!!selected} onChange={(value: ProviderKind) => {
          discoverySequence.current += 1;
          setDiscoveringModels(false);
          const previousKind = kind;
          const currentName = form.getFieldValue("name");
          if (!currentName || currentName === t(providerKindLabel[previousKind] as never)) {
            form.setFieldValue("name", t(providerKindLabel[value] as never));
          }
          form.setFieldsValue({ baseUrl: providerDefaultBaseUrl[value], modelName: value === "TYPESAFE_JEV" ? "jev-latest" : "", credentialValue: undefined });
          form.setFields([{ name: "modelName", errors: [] }, { name: "baseUrl", errors: [] }, { name: "credentialValue", errors: [] }]);
          setDiscovery({ models: [], status: "idle", invalidated: value !== "TYPESAFE_JEV" });
        }} /></Form.Item>
        {kind !== "TYPESAFE_JEV" && <Form.Item name="baseUrl" label={t("aiBaseUrl")} extra={kind === "OLLAMA" || kind === "LM_STUDIO" ? t("aiLocalOnly") : t("aiCloudCompatibleHint")} rules={[{ required: true }, { type: "url" }]}><Input placeholder={providerDefaultBaseUrl[kind] || t("aiCustomBaseUrlPlaceholder")} /></Form.Item>}
        {kind === "TYPESAFE_JEV" && <Form.Item label={t("aiBaseUrl")}><Input value={providerDefaultBaseUrl.TYPESAFE_JEV} disabled /></Form.Item>}
        <Form.Item name="credentialValue" label={t("aiCredential")} rules={[{ required: providerRequiresCredential(kind) && !selectedCredentialReusable, whitespace: true, message: t("aiProviderCredentialRequired") }, { max: 8192 }]} preserve={false}>
          <Input.Password autoComplete="new-password" maxLength={8192} placeholder={selectedCredentialReusable ? t("aiCredentialKeepIfEmpty") : t("aiCredentialPlaceholder")} />
        </Form.Item>
        {requiresModelDiscovery ? <Form.Item name="modelName" label={t("aiModelName")} required rules={[{ required: true, message: t("aiModelSelectionRequired") }, { max: 256 }, { validator: async (_, value: string | undefined) => {
          if (modelSelectionIsValid(selected?.modelName, value, discovery)) return;
          throw new Error(t("aiModelSelectionRequired"));
        } }]}>
          <Select
            showSearch
            optionFilterProp="label"
            options={modelOptions}
            placeholder={t("aiModelNamePlaceholder")}
            notFoundContent={discovery.status === "success" ? t("aiNoDiscoveredModels") : t("aiModelNamePlaceholder")}
          />
        </Form.Item> : <Form.Item name="modelName" label={t("aiModelName")}><Input disabled /></Form.Item>}
        {requiresModelDiscovery && <Space className="ai-model-discovery-row" align="center">
          <Button htmlType="button" loading={discoveringModels} onClick={() => void discoverModels()}>{t("aiDiscoverModels")}</Button>
          {discovery.status === "success" && <span role="status">{t("aiModelDiscoverySuccess", { count: discovery.models.length })}</span>}
          {discovery.status === "empty" && <span role="alert">{t("aiNoDiscoveredModels")} <Button type="link" size="small" onClick={() => void discoverModels()}>{t("retry")}</Button></span>}
          {discovery.status === "error" && discovery.error && <span role="alert">{t(discovery.error)} <Button type="link" size="small" onClick={() => void discoverModels()}>{t("retry")}</Button></span>}
        </Space>}
        <Form.Item name="enabled" label={t("aiEnabled")} valuePropName="checked"><Switch /></Form.Item>
        <Form.Item className="mb-0"><Space><Button onClick={close}>{t("cancel")}</Button><Button type="primary" htmlType="submit">{t("save")}</Button></Space></Form.Item>
      </Form>
    </PlatformDrawer>
  </Space>;
}
