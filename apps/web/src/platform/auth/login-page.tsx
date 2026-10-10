"use client";

import { KeyOutlined, LockOutlined, MoonOutlined, QrcodeOutlined, SunOutlined, UserOutlined } from "@ant-design/icons";
import { Alert, Button, Input, Skeleton, Space, type FormInstance } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { history, useLocation } from "@umijs/max";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError, api, login, mutate, sessionMe } from "@/platform/api/client";
import type { CurrentUser, LoginResponse, PasskeyAuthenticationAvailability, PasskeyOptions, WechatAttempt } from "@/platform/auth/api-types";
import type { LoginMethodAvailability } from "@/platform/auth/api-types";
import { BrandFooter } from "@/platform/branding/footer";
import { useBranding } from "@/platform/branding/provider";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { LanguageSwitcher } from "@/platform/i18n/language-switcher";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { announceSessionChangeAcrossTabs, subscribeSessionBoundary } from "@/platform/auth/session-lifecycle";
import { createSessionActionEpoch } from "./session-action-epoch.mjs";
import { AgreementLinks } from "./agreement-links";
import { MaintenanceNotice } from "./maintenance-notice";
import { createPasskeyCredential, isPasskeySupported, toAuthenticationPayload, toPublicKeyRequestOptions } from "./passkey";
import { PasswordRecoveryDrawer } from "./password-recovery-drawer";
import { safeReturnPath } from "./return-path";
import { SecondFactorForm } from "./second-factor-form";
import { PreauthEnrollmentForm } from "./preauth-enrollment-form";
import { RecoveryTicketForm } from "./recovery-ticket-form";
import { LoginCodeForm } from "./login-code-form";
import { CaptchaPanel } from "./captcha-panel";
import { useCaptchaProof } from "./use-captcha-proof";
import { isTrustedWechatAuthorizationUrl } from "./wechat-url";

type LoginValues = { username: string; password: string };

type PasswordLoginFormProps = {
  onFinish: (values: LoginValues) => Promise<void>;
  onFormReady: (form: FormInstance<LoginValues> | null) => void;
  pending: boolean;
  passkeyPending: boolean;
  wechatPending: boolean;
  passkeyLoginEnabled: boolean;
  wechatLoginAvailable: boolean;
  registrationAvailable: boolean;
  loginCodeAvailable: boolean;
  onPasskeyClick: () => void;
  onWechatClick: () => void;
  onRecoveryClick: () => void;
  onRegistrationClick: () => void;
  onLoginCodeClick: () => void;
  locale: string;
  registrationUsername: string | null;
  t: ReturnType<typeof usePreferences>["t"];
};

function PasswordLoginForm({
  onFinish,
  onFormReady,
  pending,
  passkeyPending,
  wechatPending,
  passkeyLoginEnabled,
  wechatLoginAvailable,
  registrationAvailable,
  loginCodeAvailable,
  onPasskeyClick,
  onWechatClick,
  onRecoveryClick,
  onRegistrationClick,
  onLoginCodeClick,
  locale,
  registrationUsername,
  t,
}: PasswordLoginFormProps) {
  const [form] = Form.useForm<LoginValues>();

  useEffect(() => {
    onFormReady(form);
    return () => onFormReady(null);
  }, [form, onFormReady]);

  useEffect(() => {
    const invalid = form.getFieldsError()
      .filter(field => field.errors.length)
      .map(field => field.name);
    if (invalid.length) void form.validateFields(invalid).catch(() => {});
  }, [locale, form]);

  useEffect(() => {
    if (registrationUsername) form.setFieldValue("username", registrationUsername);
  }, [form, registrationUsername]);

  return <Form form={form} onFinish={onFinish} layout="vertical" requiredMark={false} size="large">
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
      <AsyncButton type="primary" htmlType="submit" block pending={pending || passkeyPending || wechatPending} label={t("signIn")} />
      {passkeyLoginEnabled && <Button
        type="default"
        aria-label={t("passkeyLogin")}
        icon={<KeyOutlined />}
        block
        loading={passkeyPending}
        disabled={pending || passkeyPending || wechatPending}
        onClick={onPasskeyClick}
      >
        {t("passkeyLogin")}
      </Button>}
      {wechatLoginAvailable && <Button
        type="default"
        aria-label={t("wechatLogin")}
        icon={<QrcodeOutlined />}
        block
        loading={wechatPending}
        disabled={pending || passkeyPending}
        onClick={onWechatClick}
      >{t("wechatLogin")}</Button>}
      <Button type="link" disabled={pending || passkeyPending} onClick={onRecoveryClick}>
        {t("forgotPassword")}
      </Button>
    </div>
    {registrationAvailable && <Button type="link" block onClick={onRegistrationClick}>{t("publicRegistrationLink")}</Button>}
    {loginCodeAvailable && <Button type="link" block onClick={onLoginCodeClick}>{t("loginCodeSignIn")}</Button>}
  </Form>;
}

export function LoginPage() {
  const branding = useBranding();
  const client = useQueryClient();
  const { search, state } = useLocation();
  const customBackgroundUrl = branding.loginBackgroundUrl.trim();
  const hasCustomBackground = Boolean(customBackgroundUrl);
  const [challenge, setChallenge] = useState<LoginResponse | null>(null);
  const [recoveryOpen, setRecoveryOpen] = useState(false);
  const [recovered, setRecovered] = useState(false);
  const { dark, locale, toggleDark, t } = usePreferences();
  const [pending, setPending] = useState(false);
  const [passkeyPending, setPasskeyPending] = useState(false);
  const [wechatPending, setWechatPending] = useState(false);
  const [passkeyLoginEnabled, setPasskeyLoginEnabled] = useState(false);
  const [authMethods, setAuthMethods] = useState<LoginMethodAvailability | null>(null);
  const [authMethodsLoaded, setAuthMethodsLoaded] = useState(false);
  const [loginCodeOpen, setLoginCodeOpen] = useState(false);
  const [captchaPanelRevision, setCaptchaPanelRevision] = useState(0);
  const captchaProof = useCaptchaProof({
    alwaysActions: authMethods?.captcha.alwaysActions,
    supported: authMethods?.capabilities.captcha === true,
    unavailableMessage: "captchaUnavailable",
  });
  const captchaProofRef = useRef(captchaProof);
  captchaProofRef.current = captchaProof;
  const loginFormRef = useRef<FormInstance<LoginValues> | null>(null);
  const [loginActionEpoch] = useState(() => createSessionActionEpoch());
  const [loginFormRevision, setLoginFormRevision] = useState(0);
  const renderedLoginActionRevision = loginActionEpoch.capture();
  const isCurrentLoginAction = () => loginActionEpoch.isCurrent(renderedLoginActionRevision);
  const onLoginFormReady = useCallback((form: FormInstance<LoginValues> | null) => {
    loginFormRef.current = form;
  }, []);
  const [postPrimaryCaptchaOpen, setPostPrimaryCaptchaOpen] = useState(false);
  const [enrollmentRequired, setEnrollmentRequired] = useState(false);
  const [checkingSession, setCheckingSession] = useState(true);
  const [error, setError] = useState<MessageKey | null>(null);
  const [retryAfterSeconds, setRetryAfterSeconds] = useState<number | null>(null);
  const loginCodeChannels = authMethods?.capabilities.loginCode
    ? authMethods.methods.flatMap(method => method === "EMAIL_OTP" ? ["EMAIL" as const] : method === "SMS_OTP" ? ["SMS" as const] : [])
    : [];
  const wechatLoginAvailable = authMethods?.capabilities.wechat === true && authMethods.methods.includes("WECHAT");
  const registrationUsernameValue = (state as { registrationUsername?: unknown } | null)?.registrationUsername;
  const registrationUsername = typeof registrationUsernameValue === "string"
    && /^[A-Za-z0-9][A-Za-z0-9_.-]{2,99}$/.test(registrationUsernameValue)
    ? registrationUsernameValue
    : null;

  useEffect(() => {
    // Keep the epoch and keyed FormStore aligned when Strict Mode replays effects.
    setLoginFormRevision(loginActionEpoch.capture());
    const unsubscribe = subscribeSessionBoundary(() => {
      const nextRevision = loginActionEpoch.renew();
      setLoginFormRevision(nextRevision);
      captchaProofRef.current.cancel();
      loginFormRef.current?.resetFields();
      setPending(false);
      setPasskeyPending(false);
      setWechatPending(false);
      setError(null);
      setRetryAfterSeconds(null);
      setChallenge(null);
      setEnrollmentRequired(false);
      setLoginCodeOpen(false);
      setPostPrimaryCaptchaOpen(false);
      setRecoveryOpen(false);
      setRecovered(false);
    });
    return () => {
      loginActionEpoch.renew();
      captchaProofRef.current.cancel();
      unsubscribe();
    };
  }, [loginActionEpoch]);

  useEffect(() => {
    const controller = new AbortController();
    void sessionMe<CurrentUser>("login-page", controller.signal)
      .then(user => {
        if (controller.signal.aborted) return;
        client.setQueryData(["session"], user);
        history.replace(safeReturnPath(new URLSearchParams(search).get("returnTo")));
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        client.clear();
        void api<LoginResponse>("/auth/login/state", { signal: controller.signal })
          .then(value => {
            if (controller.signal.aborted) return;
            if (value.state === "ENROLLMENT_REQUIRED") {
              setChallenge(value);
              setEnrollmentRequired(true);
            } else if (value.state === "MFA_REQUIRED" || value.state === "CAPTCHA_REQUIRED" || value.state === "RECOVERY_REQUIRED" || value.secondFactorRequired) {
              continueLogin(value);
            }
          })
          .catch(() => api<LoginResponse>("/auth/second-factor/pending", { signal: controller.signal })
            .then(value => {
              if (!controller.signal.aborted && value.secondFactorRequired) setChallenge(value);
            })
            .catch(() => {}))
          .finally(() => {
            if (!controller.signal.aborted) setCheckingSession(false);
          });
      });
    return () => controller.abort();
  }, [client, search]);

  useEffect(() => {
    const controller = new AbortController();
    void api<LoginMethodAvailability>("/auth/methods", { signal: controller.signal })
      .then(value => {
        if (!controller.signal.aborted) { setAuthMethods(value); setAuthMethodsLoaded(true); }
      })
      .catch(() => {
        if (!controller.signal.aborted) { setAuthMethods(null); setAuthMethodsLoaded(true); }
      });
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
    if (!authMethodsLoaded || challenge?.state !== "CAPTCHA_REQUIRED" || !challenge.challengeId) return;
    if (authMethods?.capabilities.captcha) {
      setCaptchaPanelRevision(revision => revision + 1);
      setPostPrimaryCaptchaOpen(true);
    } else {
      setError("captchaUnavailable");
    }
  }, [authMethods, authMethodsLoaded, challenge]);

  useEffect(() => {
    const locationState = state as { wechatLogin?: unknown } | null;
    const loginResult = locationState?.wechatLogin;
    if (isLoginResponse(loginResult)) continueLogin(loginResult, loginResult.nextPath ?? undefined);
  }, [state]);

  if (checkingSession) {
    return <main className="workspace-loading"><Skeleton active paragraph={{ rows: 8 }} /></main>;
  }

  async function submit(values: LoginValues) {
    if (!isCurrentLoginAction()) return;
    setPending(true);
    setError(null);
    setRetryAfterSeconds(null);
    try {
      const outcome = await captchaProof.run("PASSWORD_LOGIN", captchaProofId => login(values.username, values.password, captchaProofId));
      if (!isCurrentLoginAction() || outcome.status !== "completed" || !captchaProof.isMounted()) return;
      loginFormRef.current?.setFieldValue("password", undefined);
      continueLogin(outcome.value);
    } catch (e) {
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      if (e instanceof ApiError && e.message === "passwordHashCapacity") {
        setRetryAfterSeconds(e.retryAfterSeconds ?? 1);
        setError("passwordHashCapacity");
      } else setError(
        e instanceof ApiError && e.message === "captchaUnavailable" ? "captchaUnavailable" :
        e instanceof ApiError && e.message === "captchaRequired" ? "captchaRequired" :
        e instanceof ApiError && e.message === "secondFactorUnavailable" ? "secondFactorUnavailable" :
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
      if (isCurrentLoginAction() && captchaProof.isMounted()) setPending(false);
    }
  }

  async function signInWithPasskey() {
    if (!isCurrentLoginAction() || pending || passkeyPending) return;
    setPasskeyPending(true);
    setError(null);
    try {
      if (!isPasskeySupported()) throw new Error("passkeyUnsupported");
      const outcome = await captchaProof.run("PASSKEY_LOGIN", captchaProofId => mutate<PasskeyOptions>("/auth/passkeys/authentication/options", "POST", captchaProofId ? { captchaProofId } : {}));
      if (!isCurrentLoginAction() || outcome.status !== "completed" || !captchaProof.isMounted()) return;
      const options = outcome.value;
      const credential = await createPasskeyCredential(toPublicKeyRequestOptions(options));
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      const result = await mutate<LoginResponse>("/auth/passkeys/authentication/complete", "POST", toAuthenticationPayload(credential, options.challengeId));
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      continueLogin(result);
    } catch (e) {
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      if (e instanceof Error && e.message === "passkeyUnsupported") setError("passkeyUnsupported");
      else if (e instanceof Error && e.message === "passkeyTimeout") setError("passkeyTimeout");
      else if (e instanceof DOMException && e.name === "NotAllowedError") setError("passkeyCancelled");
      else if (e instanceof ApiError && e.message === "secondFactorUnavailable") setError("secondFactorUnavailable");
      else if (e instanceof ApiError && e.message === "captchaUnavailable") setError("captchaUnavailable");
      else if (e instanceof ApiError && e.message === "captchaRequired") setError("captchaRequired");
      else if (e instanceof ApiError && e.message === "passkeyFeatureDisabled") setError("passkeyDisabled");
      else setError("passkeyOperationFailed");
    } finally {
      if (isCurrentLoginAction() && captchaProof.isMounted()) setPasskeyPending(false);
    }
  }

  async function signInWithWechat() {
    if (!isCurrentLoginAction() || pending || passkeyPending || wechatPending || !wechatLoginAvailable) return;
    setWechatPending(true);
    setError(null);
    try {
      const outcome = await captchaProof.run("WECHAT_LOGIN", captchaProofId => mutate<WechatAttempt>("/auth/wechat/attempts", "POST", {
        purpose: "LOGIN",
        ...(captchaProofId ? { captchaProofId } : {}),
      }));
      if (!isCurrentLoginAction() || outcome.status !== "completed" || !captchaProof.isMounted()) return;
      const attempt = outcome.value;
      if (attempt.purpose !== "LOGIN" || !isTrustedWechatAuthorizationUrl(attempt.authorizationUrl)) {
        throw new ApiError(502, "wechatUnavailable");
      }
      if (!isCurrentLoginAction()) return;
      window.location.assign(attempt.authorizationUrl);
    } catch (cause) {
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      setError(cause instanceof ApiError && cause.message === "captchaUnavailable" ? "captchaUnavailable"
        : cause instanceof ApiError && cause.message === "captchaRequired" ? "captchaRequired"
          : "wechatUnavailable");
    } finally {
      if (isCurrentLoginAction() && captchaProof.isMounted()) setWechatPending(false);
    }
  }

  function continueLogin(result: LoginResponse, nextPath?: string) {
    if (!isCurrentLoginAction()) return;
    setLoginCodeOpen(false);
    if (result.state === "ENROLLMENT_REQUIRED") {
      setChallenge(result);
      setEnrollmentRequired(true);
      return;
    }
    if (result.state === "MFA_REQUIRED" || result.secondFactorRequired) {
      setChallenge(result);
      setEnrollmentRequired(false);
      return;
    }
    if (result.state === "CAPTCHA_REQUIRED" && result.challengeId) {
      setChallenge(result);
      setEnrollmentRequired(false);
      return;
    }
    if (result.state === "RECOVERY_REQUIRED") {
      setChallenge(result);
      setEnrollmentRequired(false);
      setError(null);
      return;
    }
    if (result.state && result.state !== "COMPLETE") {
      setChallenge(result);
      setError("loginContinuationUnavailable");
      return;
    }
    setChallenge(null);
    setEnrollmentRequired(false);
    if (result.authenticated) announceSessionChangeAcrossTabs();
    history.replace(safeReturnPath(nextPath ?? new URLSearchParams(window.location.search).get("returnTo")));
  }

  async function confirmPostPrimaryCaptcha(proof: Parameters<typeof captchaProof.finish>[0] & { attemptId: string | null }) {
    if (!isCurrentLoginAction()) return;
    if (!challenge?.challengeId || proof.attemptId !== challenge.challengeId) {
      setError("captchaUnavailable");
      return;
    }
    setPending(true);
    setPostPrimaryCaptchaOpen(false);
    setError(null);
    try {
      const result = await mutate<LoginResponse>("/auth/login/captcha/confirm", "POST", {
        challengeId: challenge.challengeId,
        captchaProofId: proof.captchaProofId,
      });
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      continueLogin(result);
    } catch (e) {
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      if (e instanceof ApiError && e.message === "captchaRequired") {
        setError("captchaRequired");
        setCaptchaPanelRevision(revision => revision + 1);
        setPostPrimaryCaptchaOpen(true);
      } else if (e instanceof ApiError && e.message === "passwordHashCapacity") {
        setRetryAfterSeconds(e.retryAfterSeconds ?? 1);
        setError("passwordHashCapacity");
      } else setError(e instanceof ApiError && e.status === 429 ? "loginRateLimited"
        : e instanceof ApiError && e.status === 403 ? "forbidden"
          : e instanceof ApiError && e.message === "captchaRequired" ? "captchaRequired"
            : e instanceof ApiError && e.message === "secondFactorUnavailable" ? "secondFactorUnavailable"
              : "unavailable");
    } finally {
      if (isCurrentLoginAction() && captchaProof.isMounted()) setPending(false);
    }
  }

  async function cancelRestrictedLogin() {
    if (!isCurrentLoginAction()) return;
    if (!challenge?.challengeId) {
      captchaProof.cancel();
      setPostPrimaryCaptchaOpen(false);
      setChallenge(null);
      setEnrollmentRequired(false);
      setError(null);
      return;
    }
    setPending(true);
    setError(null);
    try {
      await mutate<void>("/auth/login/cancel", "POST", { challengeId: challenge.challengeId });
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      setPostPrimaryCaptchaOpen(false);
      setChallenge(null);
      setEnrollmentRequired(false);
    } catch (cause) {
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      setError(cause instanceof ApiError && cause.message === "secondFactorExpired" ? "secondFactorExpired" : "unavailable");
    } finally {
      if (isCurrentLoginAction() && captchaProof.isMounted()) setPending(false);
    }
  }

  return (
    <main className="login-page" data-has-background={hasCustomBackground}>
      <PasswordRecoveryDrawer
        key={loginFormRevision}
        open={recoveryOpen}
        onClose={() => setRecoveryOpen(false)}
        onRecovered={username => {
          if (!isCurrentLoginAction()) return;
          setRecoveryOpen(false);
          setRecovered(true);
          setError(null);
          loginFormRef.current?.setFieldsValue({ username, password: undefined });
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
          <div className="login-tab">{t(enrollmentRequired ? "preauthEnrollmentTitle" : challenge ? "mfaTitle" : loginCodeOpen ? "loginCodeSignIn" : "passwordLogin")}</div>
          <FeedbackBubble kind="success" id="login-recovered" content={recovered ? t("passwordRecovered") : undefined} />
          <FeedbackBubble id="login-error" content={error ? t(error, error === "passwordHashCapacity" ? { seconds: retryAfterSeconds ?? 1 } : undefined) : undefined} />
          {captchaProof.action && <CaptchaPanel
            key={loginFormRevision}
            action={captchaProof.action}
            onVerified={proof => { if (isCurrentLoginAction()) captchaProof.finish(proof); }}
            onCancel={() => {
              if (!isCurrentLoginAction()) return;
              captchaProof.finish(null);
              loginFormRef.current?.setFieldValue("password", undefined);
            }}
          />}
          {postPrimaryCaptchaOpen && challenge?.challengeId && <CaptchaPanel
            key={captchaPanelRevision}
            action="POST_PRIMARY_AUTH"
            attemptId={challenge.challengeId}
            onVerified={proof => { void confirmPostPrimaryCaptcha(proof); }}
            onCancel={() => { void cancelRestrictedLogin(); }}
          />}
          {enrollmentRequired ? (
            <PreauthEnrollmentForm
              onCancel={() => { void cancelRestrictedLogin(); }}
              onComplete={result => continueLogin(result)}
            />
          ) : challenge?.state === "RECOVERY_REQUIRED" && challenge.challengeId ? (
            <RecoveryTicketForm
              challengeId={challenge.challengeId}
              onComplete={result => continueLogin(result)}
              onCancel={() => { void cancelRestrictedLogin(); }}
            />
          ) : challenge && (challenge.state === "MFA_REQUIRED" || (!challenge.state && challenge.secondFactorRequired)) ? (
            <SecondFactorForm
              challenge={challenge}
              onCancel={() => { void cancelRestrictedLogin(); }}
              onComplete={() => continueLogin({ ...challenge, state: "COMPLETE", authenticated: true, secondFactorRequired: false })}
            />
          ) : challenge && challenge.state === "CAPTCHA_REQUIRED" ? <>
            {authMethodsLoaded && !authMethods?.capabilities.captcha && <Alert type="warning" showIcon title={t("captchaRequiredUnavailable")} />}
            <Button block disabled={pending} onClick={() => { void cancelRestrictedLogin(); }}>{t("loginCodeBack")}</Button>
          </> : challenge ? <>
            <Button block onClick={() => { setChallenge(null); setError(null); }}>{t("mfaBack")}</Button>
          </> : loginCodeOpen && loginCodeChannels.length > 0 ? <LoginCodeForm
            channels={loginCodeChannels}
            captchaAlwaysActions={authMethods?.captcha.alwaysActions ?? []}
            captchaSupported={authMethods?.capabilities.captcha === true}
            onComplete={result => continueLogin(result)}
            onCancel={() => {
              if (!isCurrentLoginAction()) return;
              setLoginCodeOpen(false);
              setError(null);
            }}
          /> : <PasswordLoginForm
            key={loginFormRevision}
            onFinish={submit}
            onFormReady={onLoginFormReady}
            pending={pending}
            passkeyPending={passkeyPending}
            wechatPending={wechatPending}
            passkeyLoginEnabled={authMethods ? authMethods.methods.includes("PASSKEY") : passkeyLoginEnabled}
            wechatLoginAvailable={wechatLoginAvailable}
            registrationAvailable={authMethods?.registration.supported === true
              && authMethods.registration.available
              && authMethods.registration.path === "/user/register"}
            loginCodeAvailable={loginCodeChannels.length > 0}
            onPasskeyClick={() => { void signInWithPasskey(); }}
            onWechatClick={() => { void signInWithWechat(); }}
            onRecoveryClick={() => { if (isCurrentLoginAction()) setRecoveryOpen(true); }}
            onRegistrationClick={() => { if (isCurrentLoginAction()) history.push("/user/register"); }}
            onLoginCodeClick={() => { if (isCurrentLoginAction()) setLoginCodeOpen(true); }}
            locale={locale}
            registrationUsername={registrationUsername}
            t={t}
          />}
          <AgreementLinks />
        </div>
        <footer><BrandFooter /></footer>
      </section>
    </main>
  );
}

function isLoginResponse(value: unknown): value is LoginResponse {
  if (!value || typeof value !== "object") return false;
  const result = value as Partial<LoginResponse>;
  return typeof result.authenticated === "boolean"
    && typeof result.secondFactorRequired === "boolean"
    && (result.challengeId === null || typeof result.challengeId === "string")
    && (result.state === undefined || ["PRIMARY_REQUIRED", "CAPTCHA_REQUIRED", "MFA_REQUIRED", "ENROLLMENT_REQUIRED", "RECOVERY_REQUIRED", "COMPLETE", "EXPIRED", "LOCKED", "CANCELLED", "NONE"].includes(result.state));
}
