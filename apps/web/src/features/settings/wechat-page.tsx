/** 编辑微信配置快照与显式密钥动作。
 * Edit WeChat configuration snapshots and explicit secret actions. */
"use client";

import "./authentication-settings-panels.css";
import "./wechat-page.css";

import { useEffect, useState } from "react";
import { useSettingsDraft } from "@/platform/forms/use-settings-draft";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Popconfirm, Alert, Input, InputNumber, Select, Spin, Switch, Tag, Typography } from "antd";
import { api, mutate } from "@/platform/api/client";
import type { WechatSettings, WechatSettingsUpdate } from "@/platform/auth/api-types";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SettingsEnabledControls } from "@/platform/components/settings-enabled-controls";
import { QueryFailure } from "@/platform/components/query-failure";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";

type WechatSettingsForm = {
  enabled: boolean;
  appId: string;
  publicOrigin: string;
  timeoutSeconds: number;
  appSecretAction: "KEEP" | "REPLACE" | "CLEAR";
  appSecretValue?: string;
};

const settingErrors = ["staleRecord", "invalidChangeReason", "wechatAppInUse", "invalidWechatSettings", "wechatOriginUnavailable", "secretKeyUnavailable", "forbidden", "unavailable"] as const satisfies readonly MessageKey[];

export function WechatPage({ canWrite, onSaved }: { canWrite: boolean; onSaved?: () => void | Promise<void> }) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm<WechatSettingsForm>();
  const [saved, setSaved] = useState(false);

  const submit = useSubmitAction({ errorKeys: settingErrors, onError: async key => {
    if (key === "staleRecord") await query.refetch();
  } });
  const query = useQuery({
    queryKey: ["settings", "wechat"],
    queryFn: ({ signal }) => api<WechatSettings>("/platform/settings/wechat", { signal }),
  });
  const enabled = Form.useWatch("enabled", form);
  const secretAction = Form.useWatch("appSecretAction", form) ?? "KEEP";

  const { snapshot, revision, commit: setSnapshot, reload, operationSignal } = useSettingsDraft(query.data,
    () => query.refetch({ throwOnError: true }), () => { form.resetFields(); setSaved(false); });
  // 草稿与首次读到的版本绑定，后台刷新只更新状态提示。
  // Bind drafts to their initial version; background refresh updates status only.
  useEffect(() => {
    if (!snapshot) return;
    form.setFieldsValue({
      enabled: snapshot.enabled,
      appId: snapshot.appId,
      publicOrigin: snapshot.publicOrigin,
      timeoutSeconds: snapshot.timeoutSeconds,
      appSecretAction: "KEEP",
      appSecretValue: undefined,
    });
  }, [form, snapshot, revision]);

  async function save(values: WechatSettingsForm) {
    if (!canWrite || !snapshot) return;
    const signal = operationSignal();
    if (signal.aborted) return;
    await submit.run(async () => {
      const payload: WechatSettingsUpdate = {
        version: snapshot.version,
        enabled: values.enabled,
        appId: values.appId.trim(),
        publicOrigin: normalizeOrigin(values.publicOrigin),
        timeoutSeconds: values.timeoutSeconds,
        appSecret: {
          action: values.appSecretAction,
          ...(values.appSecretAction === "REPLACE" ? { value: values.appSecretValue } : {}),
        },
      };
      const next = await mutate<WechatSettings>("/platform/settings/wechat", "PUT", payload, signal);
      if (signal.aborted) return;
      setSnapshot(next);
      client.setQueryData(["settings", "wechat"], next);
      form.setFieldsValue({
        enabled: next.enabled,
        appId: next.appId,
        publicOrigin: next.publicOrigin,
        timeoutSeconds: next.timeoutSeconds,
        appSecretAction: "KEEP",
        appSecretValue: undefined,
      });
      await client.invalidateQueries({ queryKey: ["auth-methods"] });
      if (signal.aborted) return;
      setSaved(true);
      await onSaved?.();
    });
  }

  if (query.isPending) return <Spin />;
  if (query.error) return <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />;
  if (!query.data) return null;

  return <>
    <Form form={form} name="settings-wechat" className="settings-form auth-config-form wechat-settings-form" layout="vertical" initialValues={{ enabled: query.data.enabled, appId: query.data.appId, publicOrigin: query.data.publicOrigin, timeoutSeconds: query.data.timeoutSeconds, appSecretAction: "KEEP" }} disabled={!canWrite || submit.pending} onValuesChange={() => setSaved(false)} onFinish={save}>
      <Form.Item name="enabled" label={t("wechatEnableLogin")} valuePropName="checked" className="auth-settings-switch-row wechat-enable-row"><Switch /></Form.Item>
      <SettingsEnabledControls name="enabled">
        <div className="auth-config-fields">
          {query.data.ready && <Alert className="wechat-ready" type="success" showIcon message={t("wechatReady")} />}
          {!query.data.publicOrigin && <Alert type="warning" showIcon message={t("wechatOriginUnavailable")} />}
          <Form.Item name="appId" label="AppID" rules={[{ required: enabled === true, whitespace: true, message: t("invalidWechatSettings") }, { max: 128, message: t("invalidWechatSettings") }]}>
            <Input autoComplete="off" maxLength={128} />
          </Form.Item>
          <Form.Item name="timeoutSeconds" label={t("smsTimeout")} rules={[{ required: true, type: "integer", min: 1, max: 30, message: t("invalidWechatSettings") }]}>
            <InputNumber precision={0} min={1} max={30} />
          </Form.Item>
          <Form.Item name="publicOrigin" label={t("wechatPublicOrigin")} rules={[{ required: enabled === true, whitespace: true, message: t(query.data.publicOrigin ? "invalidWechatSettings" : "wechatOriginUnavailable") }, { validator: async (_rule: unknown, value: string) => {
            if (enabled === true && !query.data.publicOrigin) throw new Error(t("wechatOriginUnavailable"));
            if (!value || isPublicOrigin(value)) return;
            throw new Error(t("invalidWechatSettings"));
          } }]} extra={<Typography.Paragraph className="auth-config-note" type="secondary">{t("wechatOriginHint")}</Typography.Paragraph>}>
            <Input autoComplete="url" maxLength={253} disabled={!query.data.publicOrigin} />
          </Form.Item>
          <Form.Item label={t("smsAccessSecret")}>
            <Tag className="wechat-secret-status" color={query.data.appSecretConfigured ? "success" : "default"}>{t(query.data.appSecretConfigured ? "wechatSecretConfigured" : "wechatSecretNotConfigured")}</Tag>
          </Form.Item>
          <Form.Item name="appSecretAction" label={t("secretAction")}>
            <Select options={[
              { value: "KEEP", label: t("keepExistingSecret") },
              { value: "REPLACE", label: t("replaceSecret") },
              { value: "CLEAR", label: t("clearSecret") },
            ]} />
          </Form.Item>
          {secretAction === "REPLACE" && <Form.Item name="appSecretValue" label={t("smsAccessSecret")} rules={[{ required: true, whitespace: true, message: t("invalidWechatSettings") }, { max: 4096, message: t("invalidWechatSettings") }]}>
            <Input.Password autoComplete="new-password" maxLength={4096} />
          </Form.Item>}
        </div>
      </SettingsEnabledControls>
      <Popconfirm title={t("reloadSettingsHint")} okText={t("reloadSettings")} cancelText={t("cancel")} onConfirm={() => submit.run(reload)} disabled={submit.pending}>
        <Button disabled={submit.pending}>{t("reloadSettings")}</Button>
      </Popconfirm>
      <FeedbackBubble id="settings-wechat-error" content={submit.error ? t(submit.error) : undefined} />
      <FeedbackBubble kind="success" id="settings-wechat-saved" content={saved ? t("settingsSaved") : undefined} />
      {!canWrite && <Typography.Text type="secondary">{t("settingsReadOnly")}</Typography.Text>}
      <div className="form-actions auth-config-actions"><AsyncButton htmlType="submit" type="primary" pending={submit.pending} disabled={!canWrite} label={t("save")} /></div>
    </Form>
  </>;
}

function isPublicOrigin(value: string) {
  try {
    const url = new URL(value);
    const localHttp = url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname);
    return (url.protocol === "https:" || localHttp)
      && !url.username && !url.password
      && (url.port === "" || url.port === "443" || localHttp)
      && (url.pathname === "/" || url.pathname === "")
      && !url.search && !url.hash
      && value.replace(/\/$/, "") === url.origin;
  } catch {
    return false;
  }
}

function normalizeOrigin(value: string) {
  return value.trim().replace(/\/$/, "");
}
