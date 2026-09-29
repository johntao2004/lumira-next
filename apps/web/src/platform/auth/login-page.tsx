"use client";

import { KeyOutlined, LockOutlined, MoonOutlined, SunOutlined, UserOutlined } from "@ant-design/icons";
import { Button, Input, Skeleton, Space } from "antd";
import { useEffect, useState } from "react";
import { history, useLocation } from "@umijs/max";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError, api, login, mutate } from "@/platform/api/client";
import type { CurrentUser, PasskeyAuthenticationAvailability, PasskeyOptions } from "@/platform/auth/api-types";
import { BrandFooter } from "@/platform/branding/footer";
import { useBranding } from "@/platform/branding/provider";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { LanguageSwitcher } from "@/platform/i18n/language-switcher";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { AgreementLinks } from "./agreement-links";
import { MaintenanceNotice } from "./maintenance-notice";
import { createPasskeyCredential, isPasskeySupported, toAuthenticationPayload, toPublicKeyRequestOptions } from "./passkey";
import { PasswordRecoveryDrawer } from "./password-recovery-drawer";
import { safeReturnPath } from "./return-path";

type LoginValues = { username: string; password: string };

export function LoginPage() {
  const branding = useBranding();
  const client = useQueryClient();
  const { search } = useLocation();
  const customBackgroundUrl = branding.loginBackgroundUrl.trim();
  const hasCustomBackground = Boolean(customBackgroundUrl);
  const [recoveryOpen, setRecoveryOpen] = useState(false);
  const [recovered, setRecovered] = useState(false);
  const { dark, locale, toggleDark, t } = usePreferences();
  const [form] = Form.useForm<LoginValues>();
  const [pending, setPending] = useState(false);
  const [passkeyPending, setPasskeyPending] = useState(false);
  const [passkeyLoginEnabled, setPasskeyLoginEnabled] = useState(false);
  const [checkingSession, setCheckingSession] = useState(true);
  const [error, setError] = useState<MessageKey | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void api<CurrentUser>("/auth/me", { signal: controller.signal })
      .then(user => {
        if (controller.signal.aborted) return;
        client.setQueryData(["session"], user);
        history.replace(safeReturnPath(new URLSearchParams(search).get("returnTo")));
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        client.clear();
        setCheckingSession(false);
      });
    return () => controller.abort();
  }, [client, search]);

  useEffect(() => {
    const controller = new AbortController();
    void api<PasskeyAuthenticationAvailability>("/auth/passkeys/authentication/availability", { signal: controller.signal })
      .then(({ enabled }) => {
        if (!controller.signal.aborted) setPasskeyLoginEnabled(enabled);
      })
      .catch(() => {
        if (!controller.signal.aborted) setPasskeyLoginEnabled(false);
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const invalid = form.getFieldsError()
      .filter((field) => field.errors.length)
      .map((field) => field.name);
    if (invalid.length) void form.validateFields(invalid).catch(() => {});
  }, [locale, form]);

  if (checkingSession) {
    return <main className="workspace-loading"><Skeleton active paragraph={{ rows: 8 }} /></main>;
  }

  async function submit(values: LoginValues) {
    setPending(true);
    setError(null);
    try {
      await login(values.username, values.password);
      history.replace(safeReturnPath(new URLSearchParams(window.location.search).get("returnTo")));
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 503 && e.message === "maintenanceActive"
          ? "maintenanceActive"
          : e instanceof ApiError && e.status === 429
            ? "loginRateLimited"
            : e instanceof ApiError && e.status === 401
              ? "loginFailed"
              : e instanceof ApiError && e.status === 403
                ? "forbidden"
                : "unavailable",
      );
    } finally {
      setPending(false);
    }
  }

  async function signInWithPasskey() {
    if (pending || passkeyPending) return;
    setPasskeyPending(true);
    setError(null);
    try {
      if (!isPasskeySupported()) throw new Error("passkeyUnsupported");
      const options = await mutate<PasskeyOptions>("/auth/passkeys/authentication/options", "POST", {});
      const credential = await createPasskeyCredential(toPublicKeyRequestOptions(options));
      await mutate("/auth/passkeys/authentication/complete", "POST", toAuthenticationPayload(credential, options.challengeId));
      history.replace(safeReturnPath(new URLSearchParams(window.location.search).get("returnTo")));
    } catch (e) {
      if (e instanceof Error && e.message === "passkeyUnsupported") setError("passkeyUnsupported");
      else if (e instanceof Error && e.message === "passkeyTimeout") setError("passkeyTimeout");
      else if (e instanceof DOMException && e.name === "NotAllowedError") setError("passkeyCancelled");
      else if (e instanceof ApiError && e.message === "passkeyFeatureDisabled") setError("passkeyDisabled");
      else setError("passkeyOperationFailed");
    } finally {
      setPasskeyPending(false);
    }
  }

  return (
    <main className="login-page" data-has-background={hasCustomBackground}>
      <PasswordRecoveryDrawer
        open={recoveryOpen}
        onClose={() => setRecoveryOpen(false)}
        onRecovered={username => {
          setRecoveryOpen(false);
          setRecovered(true);
          setError(null);
          form.setFieldsValue({ username, password: undefined });
        }}
      />
      {hasCustomBackground && (
        <div
          className="login-hero"
          aria-hidden="true"
          style={{ backgroundImage: `url(${JSON.stringify(customBackgroundUrl)})` }}
        />
      )}
      <section className="login-shell">
        <Space className="login-utilities">
          <Button
            aria-label={t(dark ? "lightMode" : "darkMode")}
            icon={dark ? <SunOutlined /> : <MoonOutlined />}
            onClick={toggleDark}
          />
          <LanguageSwitcher />
        </Space>
        <div className="login-content">
          <h1>{t("welcome", { name: branding.websiteName })}</h1>
          <MaintenanceNotice />
          <div className="login-tab">{t("passwordLogin")}</div>
          <FeedbackBubble kind="success" id="login-recovered" content={recovered ? t("passwordRecovered") : undefined} />
          <FeedbackBubble id="login-error" content={error ? t(error) : undefined} />
          <Form form={form} onFinish={submit} layout="vertical" requiredMark={false} size="large">
            <Form.Item name="username" rules={[{ required: true, message: t("usernameRequired") }]}>
              <Input
                aria-label={t("username")}
                prefix={<UserOutlined />}
                autoComplete="username"
                placeholder={t("username")}
              />
            </Form.Item>
            <Form.Item name="password" rules={[{ required: true, message: t("passwordRequired") }]}>
              <Input.Password
                aria-label={t("password")}
                prefix={<LockOutlined />}
                autoComplete="current-password"
                placeholder={t("password")}
              />
            </Form.Item>
            <div className="login-actions">
              <AsyncButton type="primary" htmlType="submit" block pending={pending || passkeyPending} label={t("signIn")} />
              {passkeyLoginEnabled && (
                <Button
                  type="default"
                  aria-label={t("passkeyLogin")}
                  icon={<KeyOutlined />}
                  block
                  loading={passkeyPending}
                  disabled={pending || passkeyPending}
                  onClick={signInWithPasskey}
                >
                  {t("passkeyLogin")}
                </Button>
              )}
              <Button type="link" disabled={pending || passkeyPending} onClick={() => setRecoveryOpen(true)}>
                {t("forgotPassword")}
              </Button>
            </div>
          </Form>
          <AgreementLinks />
        </div>
        <footer><BrandFooter /></footer>
      </section>
    </main>
  );
}
