"use client";

import { lazy, Suspense, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Modal, Space, Spin, Switch, Tag, Typography } from "antd";
import { api, mutate } from "@/platform/api/client";
import type { AuthenticationSettings, PrimaryMethod } from "@/platform/auth/api-types";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { QueryFailure } from "@/platform/components/query-failure";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import type { MessageKey } from "@/platform/i18n/messages";
import {
  authenticationMethodOrder,
  beginAuthenticationMethodEnable,
  finishAuthenticationMethodConfiguration,
  readyEnabledMethods,
  reconcileAuthenticationMethodDraft,
  type AuthenticationConfigurationRoute,
  type AuthenticationMethodConfiguration,
  type AuthenticationMethodDraft,
} from "./authentication-method-flow";

const PasskeyPage = lazy(() => import("./passkey-page").then(({ PasskeyPage: Page }) => ({ default: Page })));
const SmsPage = lazy(() => import("./sms-page").then(({ SmsPage: Page }) => ({ default: Page })));
const SmtpPage = lazy(() => import("./smtp-page").then(({ SmtpPage: Page }) => ({ default: Page })));
const WechatPage = lazy(() => import("./wechat-page").then(({ WechatPage: Page }) => ({ default: Page })));

type Values = Pick<AuthenticationSettings, "enabledMethods" | "mfaRequired">;
type RouteAccessCheck = (routeId: string) => boolean;
type RouteOperationCheck = (routeId: string, actionId: string) => boolean;

const methodLabel = {
  PASSWORD: "passwordLogin",
  PASSKEY: "passkeyLogin",
  EMAIL_OTP: "emailOtpLogin",
  SMS_OTP: "smsOtpLogin",
  WECHAT: "wechatLogin",
} as const;

const configurationRoute: Partial<Record<PrimaryMethod, AuthenticationConfigurationRoute>> = {
  PASSKEY: "passkey",
  EMAIL_OTP: "smtp",
  SMS_OTP: "sms",
  WECHAT: "wechat",
};

type MethodSwitchGroupProps = {
  value?: PrimaryMethod[];
  onToggle?: (method: PrimaryMethod, checked: boolean, next: PrimaryMethod[]) => void;
  disabled?: boolean;
  availableMethods?: PrimaryMethod[];
};

function MethodSwitchGroup({ value, onToggle, disabled, availableMethods }: MethodSwitchGroupProps) {
  const { t } = usePreferences();
  const enabledMethods = new Set(value ?? []);

  return <div role="group" aria-label={t("globalLoginMethods")} className="auth-method-list">
    {authenticationMethodOrder.map(method => (
      <div key={method} className="auth-method-row">
        <span>{t(methodLabel[method])}</span>
        {!availableMethods
          ? <Tag>{t("loginMethodAvailabilityUnknown")}</Tag>
          : availableMethods.includes(method)
            ? <Tag color="success">{t("loginMethodProviderReady")}</Tag>
            : <Tag>{t("loginMethodProviderPending")}</Tag>}
        <Switch
          checked={enabledMethods.has(method)}
          disabled={disabled}
          aria-label={t(methodLabel[method])}
          onChange={checked => {
            const next = new Set(value ?? []);
            if (checked) next.add(method);
            else next.delete(method);
            onToggle?.(method, checked, authenticationMethodOrder.filter(candidate => next.has(candidate)));
          }}
        />
      </div>
    ))}
  </div>;
}

export function AuthenticationPage({ canWrite, hasRouteAccess, hasRouteOperation }: {
  canWrite: boolean;
  hasRouteAccess: RouteAccessCheck;
  hasRouteOperation: RouteOperationCheck;
}) {
  const query = useQuery({
    queryKey: ["settings", "authentication"],
    queryFn: ({ signal }) => api<AuthenticationSettings>("/platform/settings/authentication", { signal }),
  });
  const { t } = usePreferences();

  if (!query.data) return query.error
    ? <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />
    : <Spin />;

  return <AuthenticationEditor
    initial={query.data}
    canWrite={canWrite}
    hasRouteAccess={hasRouteAccess}
    hasRouteOperation={hasRouteOperation}
  />;
}

function AuthenticationEditor({ initial, canWrite, hasRouteAccess, hasRouteOperation }: {
  initial: AuthenticationSettings;
  canWrite: boolean;
  hasRouteAccess: RouteAccessCheck;
  hasRouteOperation: RouteOperationCheck;
}) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm<Values>();
  const policyVersion = useRef(initial.version);
  const latestAvailableMethods = useRef(initial.availableMethods);
  const action = useSubmitAction({
    errorKeys: ["staleRecord", "invalidChangeReason", "authenticationPolicyConflict", "authenticationPolicyUnreachable", "authenticationMethodUnavailable", "secondFactorUnavailable", "forbidden", "unavailable"],
    onError: async key => {
      if (key !== "authenticationMethodUnavailable" && key !== "authenticationPolicyConflict" && key !== "staleRecord" && key !== "unavailable") return;
      try {
        const fresh = await api<AuthenticationSettings>("/platform/settings/authentication");
        latestAvailableMethods.current = fresh.availableMethods;
        client.setQueryData(["settings", "authentication"], fresh);
        const current = form.getFieldValue("enabledMethods") ?? [];
        const reconciled = reconcileAuthenticationMethodDraft({ enabledMethods: current, configuration: null }, fresh.availableMethods);
        form.setFieldValue("enabledMethods", reconciled.enabledMethods);
        if (key === "authenticationPolicyConflict" || key === "staleRecord") policyVersion.current = fresh.version;
      } catch {
        const current = form.getFieldValue("enabledMethods") ?? [];
        const reconciled = reconcileAuthenticationMethodDraft({ enabledMethods: current, configuration: null }, latestAvailableMethods.current);
        form.setFieldValue("enabledMethods", reconciled.enabledMethods);
      }
    },
  });
  const [configuration, setConfiguration] = useState<AuthenticationMethodConfiguration | null>(null);
  const [checkingConfigurationId, setCheckingConfigurationId] = useState<number | null>(null);
  const [configurationError, setConfigurationError] = useState<MessageKey | null>(null);
  const configurationRef = useRef<AuthenticationMethodConfiguration | null>(null);
  const checkingConfigurationRef = useRef<number | null>(null);
  const nextConfigurationId = useRef(0);
  const methodToConfigure = useRef<PrimaryMethod | null>(null);
  const mfaRequired = Form.useWatch("mfaRequired", form);

  useEffect(() => {
    latestAvailableMethods.current = initial.availableMethods;
    const current = form.getFieldValue("enabledMethods") ?? readyEnabledMethods(initial.enabledMethods, initial.availableMethods);
    const draft: AuthenticationMethodDraft = {
      enabledMethods: current,
      configuration: configurationRef.current,
    };
    const reconciled = reconcileAuthenticationMethodDraft(draft, initial.availableMethods);
    if (reconciled.enabledMethods.length !== current.length
      || reconciled.enabledMethods.some((method, index) => method !== current[index])) {
      form.setFieldValue("enabledMethods", reconciled.enabledMethods);
    }
  }, [form, initial.availableMethods, initial.enabledMethods]);

  useEffect(() => () => {
    configurationRef.current = null;
    checkingConfigurationRef.current = null;
  }, []);

  function commitConfigurationDraft(next: AuthenticationMethodDraft) {
    configurationRef.current = next.configuration;
    setConfiguration(next.configuration);
    form.setFieldValue("enabledMethods", next.enabledMethods);
  }

  function beginConfiguration(method: PrimaryMethod) {
    if (!canWrite || action.isPending() || configurationRef.current) return;
    setConfigurationError(null);
    const route = configurationRoute[method] ?? null;
    const started = beginAuthenticationMethodEnable({
      enabledMethods: form.getFieldValue("enabledMethods") ?? readyEnabledMethods(initial.enabledMethods, initial.availableMethods),
      configuration: null,
    }, {
      id: ++nextConfigurationId.current,
      method,
      // A click always starts a fresh server check. The cached list is display
      // state only and cannot authorize an enable operation.
      availableMethods: undefined,
      configurationRoute: route,
      canAccessConfiguration: route !== null && hasRouteAccess(route),
      canUpdateConfiguration: route !== null && hasRouteOperation(route, "update"),
    });
    if (started.result === "busy") return;
    const id = started.draft.configuration!.id;
    commitConfigurationDraft(started.draft);
    checkingConfigurationRef.current = id;
    setCheckingConfigurationId(id);
    void (async () => {
      try {
        const fresh = await api<AuthenticationSettings>("/platform/settings/authentication");
        if (configurationRef.current?.id !== id) return;
        latestAvailableMethods.current = fresh.availableMethods;
        client.setQueryData(["settings", "authentication"], fresh);
        void client.invalidateQueries({ queryKey: ["auth-methods"] });
        void client.invalidateQueries({ queryKey: ["session"] });
        const active = configurationRef.current;
        if (!active) return;
        if (fresh.availableMethods?.includes(method)) {
          const completed = finishAuthenticationMethodConfiguration({
            enabledMethods: form.getFieldValue("enabledMethods") ?? [],
            configuration: active,
          }, id, true);
          commitConfigurationDraft(reconcileAuthenticationMethodDraft(completed.draft, fresh.availableMethods));
        }
        checkingConfigurationRef.current = null;
        setCheckingConfigurationId(null);
      } catch {
        if (configurationRef.current?.id !== id) return;
        const active = configurationRef.current;
        if (!active) return;
        const completed = finishAuthenticationMethodConfiguration({
          enabledMethods: form.getFieldValue("enabledMethods") ?? [],
          configuration: active,
        }, id, false);
        commitConfigurationDraft(reconcileAuthenticationMethodDraft(completed.draft, latestAvailableMethods.current));
        checkingConfigurationRef.current = null;
        setCheckingConfigurationId(null);
        setConfigurationError("unavailable");
      }
    })();
  }

  function handleMethodToggle(method: PrimaryMethod, checked: boolean, nextMethods: PrimaryMethod[]) {
    if (!canWrite || action.isPending() || configurationRef.current) return;
    if (checked) {
      // The transaction selects the method temporarily while its fresh status
      // is checked; it opens the provider form only if the server says it is not ready.
      beginConfiguration(method);
      return;
    }
    form.setFieldValue("enabledMethods", nextMethods);
  }

  function cancelConfiguration(id: number) {
    const active = configurationRef.current;
    if (!active || active.id !== id) return;
    checkingConfigurationRef.current = null;
    setCheckingConfigurationId(null);
    const cancelled = finishAuthenticationMethodConfiguration({
      enabledMethods: form.getFieldValue("enabledMethods") ?? [],
      configuration: active,
    }, id, false);
    commitConfigurationDraft(reconcileAuthenticationMethodDraft(cancelled.draft, latestAvailableMethods.current));
    setConfigurationError(null);
  }

  async function verifyConfigurationSaved(id: number, method: PrimaryMethod) {
    if (configurationRef.current?.id !== id || checkingConfigurationRef.current === id) return;
    setConfigurationError(null);
    checkingConfigurationRef.current = id;
    setCheckingConfigurationId(id);
    try {
      // This direct request bypasses any short-lived query cache. Readiness is
      // decided only by the server's availableMethods response.
      const fresh = await api<AuthenticationSettings>("/platform/settings/authentication");
      if (configurationRef.current?.id !== id) return;
      latestAvailableMethods.current = fresh.availableMethods;
      client.setQueryData(["settings", "authentication"], fresh);
      void client.invalidateQueries({ queryKey: ["auth-methods"] });
      void client.invalidateQueries({ queryKey: ["session"] });

      const active = configurationRef.current;
      if (!active) return;
      const completed = finishAuthenticationMethodConfiguration({
        enabledMethods: form.getFieldValue("enabledMethods") ?? [],
        configuration: active,
      }, id, fresh.availableMethods?.includes(method) === true);
      commitConfigurationDraft(reconcileAuthenticationMethodDraft(completed.draft, fresh.availableMethods));
      checkingConfigurationRef.current = null;
      setCheckingConfigurationId(null);
      if (completed.result === "unavailable") setConfigurationError("authenticationMethodUnavailable");
      else if (completed.result === "ready") setConfigurationError(null);
    } catch {
      if (configurationRef.current?.id !== id) return;
      const active = configurationRef.current;
      if (!active) return;
      const completed = finishAuthenticationMethodConfiguration({
        enabledMethods: form.getFieldValue("enabledMethods") ?? [],
        configuration: active,
      }, id, false);
      commitConfigurationDraft(reconcileAuthenticationMethodDraft(completed.draft, latestAvailableMethods.current));
      checkingConfigurationRef.current = null;
      setCheckingConfigurationId(null);
      setConfigurationError("unavailable");
    }
  }

  const route = configuration ? configurationRoute[configuration.method] : undefined;
  const canRenderConfiguration = Boolean(
    configuration
    && !configuration.blockReason
    && route
    && hasRouteAccess(route)
    && hasRouteOperation(route, "update"),
  );
  const submitConfigurationSaved = configuration && checkingConfigurationId !== configuration.id
    ? () => verifyConfigurationSaved(configuration.id, configuration.method)
    : undefined;
  const permissionMessage = configuration?.blockReason === "access"
    ? t("authenticationProviderConfigurationAccessDenied")
    : configuration?.blockReason === "update"
      ? t("authenticationProviderConfigurationUpdateDenied")
      : t("authenticationProviderConfigurationUnavailable");

  async function savePolicy(values: Values) {
    if (!canWrite || configurationRef.current || action.isPending()) return;
    setConfigurationError(null);
    methodToConfigure.current = null;
    await action.run(async () => {
      const fresh = await api<AuthenticationSettings>("/platform/settings/authentication");
      if (configurationRef.current) return false;
      latestAvailableMethods.current = fresh.availableMethods;
      client.setQueryData(["settings", "authentication"], fresh);

      const selected = values.enabledMethods ?? [];
      const reconciled = reconcileAuthenticationMethodDraft({ enabledMethods: selected, configuration: null }, fresh.availableMethods);
      if (fresh.version !== policyVersion.current) {
        policyVersion.current = fresh.version;
        form.setFieldValue("enabledMethods", reconciled.enabledMethods);
        action.setError("authenticationPolicyConflict");
        return false;
      }

      const available = new Set(fresh.availableMethods ?? []);
      methodToConfigure.current = selected.find(method => !available.has(method)) ?? null;
      if (methodToConfigure.current) {
        form.setFieldValue("enabledMethods", reconciled.enabledMethods);
        setConfigurationError("authenticationMethodUnavailable");
        return false;
      }

      const result = await mutate<AuthenticationSettings>("/platform/settings/authentication", "PUT", {
        version: policyVersion.current,
        enabledMethods: selected,
        mfaRequired: values.mfaRequired,
      });
      policyVersion.current = result.version;
      client.setQueryData(["settings", "authentication"], result);
      form.setFieldsValue({
        enabledMethods: readyEnabledMethods(result.enabledMethods, result.availableMethods),
        mfaRequired: result.mfaRequired,
      });
      await Promise.all([
        client.invalidateQueries({ queryKey: ["auth-methods"] }),
        client.invalidateQueries({ queryKey: ["session"] }),
      ]);
    });
    if (methodToConfigure.current) beginConfiguration(methodToConfigure.current);
  }

  return <>
    <Form
      form={form}
      className="settings-form"
      layout="vertical"
      initialValues={{
        enabledMethods: readyEnabledMethods(initial.enabledMethods, initial.availableMethods),
        mfaRequired: initial.mfaRequired,
      }}
      disabled={!canWrite || action.pending || configuration !== null}
      onFinish={values => void savePolicy(values)}
    >
      <section className="auth-settings-section">
        <Typography.Title level={5}>{t("globalLoginMethods")}</Typography.Title>
        <Typography.Paragraph type="secondary">{t("globalLoginMethodsHint")}</Typography.Paragraph>
        <Form.Item name="enabledMethods" rules={[
          { type: "array", min: 1, required: true, message: t("authenticationMethodRequired") },
        ]}>
          <MethodSwitchGroup
            availableMethods={initial.availableMethods}
            disabled={!canWrite || action.pending || configuration !== null}
            onToggle={handleMethodToggle}
          />
        </Form.Item>
      </section>
      <section className="auth-settings-section">
        <Typography.Title level={5}>{t("globalMfaRequired")}</Typography.Title>
        <Typography.Paragraph type="secondary">{t("globalMfaRequiredHint")}</Typography.Paragraph>
        <Form.Item name="mfaRequired" label={t("globalMfaRequired")} valuePropName="checked" className="auth-settings-switch-row">
          <Switch />
        </Form.Item>
        {mfaRequired === true && Number.isSafeInteger(initial.affectedAccountCount) && <Alert type="warning" showIcon title={t("globalMfaAffectedAccounts", { count: initial.affectedAccountCount })} />}
      </section>
      <FeedbackBubble id="authentication-settings-error" content={action.errorMessage} />
      <FeedbackBubble id="authentication-method-configuration-error" content={!configuration && configurationError ? t(configurationError) : undefined} />
      <Space className="form-actions auth-settings-actions" wrap>
        <AsyncButton htmlType="submit" type="primary" pending={action.pending} disabled={!canWrite || configuration !== null} label={t("save")} />
      </Space>
    </Form>
    <Modal
      title={configuration ? t("authenticationConfigureMethodTitle", { method: t(methodLabel[configuration.method]) }) : t("authenticationConfigureMethodTitle")}
      open={configuration !== null}
      onCancel={() => { if (configuration) cancelConfiguration(configuration.id); }}
      footer={<Button onClick={() => { if (configuration) cancelConfiguration(configuration.id); }}>{t("cancel")}</Button>}
      destroyOnHidden
      width={900}
      styles={{ body: { maxHeight: "72vh", overflowY: "auto" } }}
    >
      <div className="authentication-security-workspace">
        {configuration && <Typography.Paragraph type="secondary">{t("authenticationConfigureMethodHint")}</Typography.Paragraph>}
        {configuration && checkingConfigurationId === configuration.id && <Spin />}
        {configurationError && configuration && <Alert type="error" showIcon message={t(configurationError)} />}
        {configuration?.blockReason && checkingConfigurationId !== configuration.id && <Alert type="warning" showIcon message={permissionMessage} />}
        {canRenderConfiguration && configuration && checkingConfigurationId !== configuration.id && <Suspense fallback={<Spin />}>
          {route === "passkey" && <PasskeyPage key={configuration.id} canWrite onSaved={submitConfigurationSaved} />}
          {route === "smtp" && <SmtpPage key={configuration.id} canWrite onSaved={submitConfigurationSaved} />}
          {route === "sms" && <SmsPage key={configuration.id} canWrite onSaved={submitConfigurationSaved} />}
          {route === "wechat" && <WechatPage key={configuration.id} canWrite onSaved={submitConfigurationSaved} />}
        </Suspense>}
      </div>
    </Modal>
  </>;
}
