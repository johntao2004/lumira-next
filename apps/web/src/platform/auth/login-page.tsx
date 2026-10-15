"use client";

import { KeyOutlined, LockOutlined, QrcodeOutlined, UserOutlined } from "@ant-design/icons";
import { Alert, Button, Input, Modal, Skeleton, Space, type FormInstance } from "antd";
import type { ReactNode } from "react";
import { useCallback, useEffect, useRef, useState } from "react";
import { history, useLocation } from "@umijs/max";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError, api, login, mutate, sessionMe } from "@/platform/api/client";
import type { CurrentUser, LoginResponse, PasskeyAuthenticationAvailability, PasskeyOptions, WechatAttempt } from "@/platform/auth/api-types";
import type { LoginMethodAvailability } from "@/platform/auth/api-types";
import { useBranding } from "@/platform/branding/provider";
import { AuthPageLayout } from "@/platform/auth/auth-page-layout";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { announceSessionChangeAcrossTabs, currentSessionRevision, subscribeSessionBoundary } from "@/platform/auth/session-lifecycle";
import { createSessionActionEpoch } from "./session-action-epoch.mjs";
import { restorePendingLogin } from "./login-state-restoration.mjs";
import { AgreementLinks } from "./agreement-links";
import { MaintenanceNotice } from "./maintenance-notice";
import { createPasskeyCredential, isPasskeySupported, toAuthenticationPayload, toPublicKeyRequestOptions } from "./passkey";
import { PasswordRecoveryForm } from "./password-recovery-form";
import { authRouteWithReturnTo, safeReturnPath } from "./return-path";
import { SecondFactorForm } from "./second-factor-form";
import { PreauthEnrollmentForm } from "./preauth-enrollment-form";
import { RecoveryTicketForm } from "./recovery-ticket-form";
import { LoginCodeForm } from "./login-code-form";
import { MandatoryPasswordChangeForm } from "./mandatory-password-change-form";
import { CaptchaPanel, type CaptchaPanelHandle } from "./captcha-panel";
import { useCaptchaProof } from "./use-captcha-proof";
import { isTrustedWechatAuthorizationUrl } from "./wechat-url";
import { isLoginResponse as isLoginResponseValue, loginResponseDisposition, passwordChangeReason } from "./login-response-contract.mjs";

type LoginValues = { username: string; password: string };

type PasswordLoginFormProps = {
  onFinish: (values: LoginValues) => Promise<void>;
  onFormReady: (form: FormInstance<LoginValues> | null) => void;
  pending: boolean;
  passkeyPending: boolean;
  wechatPending: boolean;
  authMethodsReady: boolean;
  passwordCaptchaRequired: boolean;
  passwordCaptchaReady: boolean;
  passwordCaptcha: ReactNode;
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
  authMethodsReady,
  passwordCaptchaRequired,
  passwordCaptchaReady,
  passwordCaptcha,
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
    {passwordCaptcha && <div className="login-password-captcha">{passwordCaptcha}</div>}
    <div className="login-actions">
      <AsyncButton
        type="primary"
        htmlType="submit"
        block
        pending={pending || passkeyPending || wechatPending}
        disabled={!authMethodsReady || (passwordCaptchaRequired && !passwordCaptchaReady)}
        label={t("signIn")}
      />
      {passkeyLoginEnabled && <Button
        type="default"
        aria-label={t("passkeyLogin")}
        icon={<KeyOutlined />}
        block
        loading={passkeyPending}
        disabled={pending || passkeyPending || wechatPending || !authMethodsReady}
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
        disabled={pending || passkeyPending || !authMethodsReady}
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
  const [challenge, setChallenge] = useState<LoginResponse | null>(null);
  const [authView, setAuthView] = useState<"login" | "recovery">("login");
  const [recovered, setRecovered] = useState(false);
  const [recoveredUsername, setRecoveredUsername] = useState<string | null>(null);
  const { locale, t } = usePreferences();
  const [pending, setPending] = useState(false);
  const [passkeyPending, setPasskeyPending] = useState(false);
  const [wechatPending, setWechatPending] = useState(false);
  const [passkeyLoginEnabled, setPasskeyLoginEnabled] = useState(false);
  const [authMethods, setAuthMethods] = useState<LoginMethodAvailability | null>(null);
  const [authMethodsLoaded, setAuthMethodsLoaded] = useState(false);
  const [authMethodsReady, setAuthMethodsReady] = useState(false);
  const [authMethodsLoadFailed, setAuthMethodsLoadFailed] = useState(false);
  const authMethodsRequestRevision = useRef(0);
  const authMethodsResolved = useRef(false);
  const [captchaLoginUnavailable, setCaptchaLoginUnavailable] = useState(false);
  const [loginCodeOpen, setLoginCodeOpen] = useState(false);
  const [passwordCaptchaReady, setPasswordCaptchaReady] = useState(false);
  const [captchaPanelRevision, setCaptchaPanelRevision] = useState(0);
  const loadAuthMethods = useCallback(async (signal?: AbortSignal) => {
    const requestRevision = ++authMethodsRequestRevision.current;
    setAuthMethodsLoaded(false);
    setAuthMethodsReady(false);
    setAuthMethodsLoadFailed(false);
    setAuthMethods(null);
    try {
      const value = await api<LoginMethodAvailability>("/auth/methods", signal ? { signal } : {});
      if (signal?.aborted || requestRevision !== authMethodsRequestRevision.current) return null;
      if (!isLoginMethodAvailability(value)) throw new Error("invalidLoginMethodAvailability");
      authMethodsResolved.current = true;
      setAuthMethods(value);
      setAuthMethodsReady(true);
      setCaptchaLoginUnavailable(
        !value.capabilities.captcha && value.captcha.alwaysActions.includes("PASSWORD_LOGIN"),
      );
      return value;
    } catch (cause) {
      if (signal?.aborted || requestRevision !== authMethodsRequestRevision.current
        || (cause instanceof DOMException && cause.name === "AbortError")) return null;
      authMethodsResolved.current = true;
      setAuthMethods(null);
      setAuthMethodsReady(false);
      setAuthMethodsLoadFailed(true);
      return null;
    } finally {
      if (!signal?.aborted && requestRevision === authMethodsRequestRevision.current) setAuthMethodsLoaded(true);
    }
  }, []);
  const refreshCaptchaCapability = useCallback(async () => {
    const latest = await loadAuthMethods();
    const supported = latest?.capabilities.captcha === true;
    if (!supported) setCaptchaLoginUnavailable(true);
    return supported;
  }, [loadAuthMethods]);
  const captchaProof = useCaptchaProof({
    alwaysActions: authMethods?.captcha.alwaysActions,
    supported: authMethods?.capabilities.captcha === true,
    unavailableMessage: "captchaUnavailable",
    refreshSupported: refreshCaptchaCapability,
  });
  const captchaProofRef = useRef(captchaProof);
  captchaProofRef.current = captchaProof;
  const passwordCaptchaPanelRef = useRef<CaptchaPanelHandle | null>(null);
  const loginFormRef = useRef<FormInstance<LoginValues> | null>(null);
  const [loginActionEpoch] = useState(() => createSessionActionEpoch());
  const [loginFormRevision, setLoginFormRevision] = useState(0);
  const renderedLoginActionRevision = loginActionEpoch.capture();
  const isCurrentLoginAction = () => loginActionEpoch.isCurrent(renderedLoginActionRevision);
  const onLoginFormReady = useCallback((form: FormInstance<LoginValues> | null) => {
    loginFormRef.current = form;
  }, []);
  const [postPrimaryCaptchaOpen, setPostPrimaryCaptchaOpen] = useState(false);
  const restrictedLoginCancelInFlight = useRef(false);
  const [enrollmentRequired, setEnrollmentRequired] = useState(false);
  const [checkingSession, setCheckingSession] = useState(true);
  const [error, setError] = useState<MessageKey | null>(null);
  const [retryAfterSeconds, setRetryAfterSeconds] = useState<number | null>(null);
  const passwordChangeOpen = challenge?.state === "PASSWORD_CHANGE_REQUIRED";
  const passwordCaptchaAlways = authMethods?.captcha.alwaysActions.includes("PASSWORD_LOGIN") ?? false;
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
    const unsubscribe = subscribeSessionBoundary(boundary => {
      const nextRevision = loginActionEpoch.renew();
      setLoginFormRevision(nextRevision);
      captchaProofRef.current.cancel();
      loginFormRef.current?.resetFields();
      setPending(false);
      restrictedLoginCancelInFlight.current = false;
      setPasskeyPending(false);
      setWechatPending(false);
      setError(null);
      setRetryAfterSeconds(null);
      setChallenge(null);
      setEnrollmentRequired(false);
      setLoginCodeOpen(false);
      setPostPrimaryCaptchaOpen(false);
      setAuthView("login");
      setRecovered(false);
      setRecoveredUsername(null);
      if (boundary.kind === "invalidated" && authMethodsResolved.current) void loadAuthMethods();
    });
    return () => {
      loginActionEpoch.renew();
      captchaProofRef.current.cancel();
      unsubscribe();
    };
  }, [loginActionEpoch, loadAuthMethods]);

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
        void restorePendingLogin({
          epoch: loginActionEpoch,
          signal: controller.signal,
          loadLoginState: () => api<LoginResponse>("/auth/login/state", { signal: controller.signal }),
          loadSecondFactorPending: () => api<LoginResponse>("/auth/second-factor/pending", { signal: controller.signal }),
          onState: (value, revision) => {
            if (value.state === "ENROLLMENT_REQUIRED" || value.state === "PASSWORD_CHANGE_REQUIRED"
              || value.state === "MFA_REQUIRED" || value.state === "CAPTCHA_REQUIRED"
              || value.state === "RECOVERY_REQUIRED" || value.secondFactorRequired) {
              continueLogin(value, undefined, revision);
            }
          },
        })
          .finally(() => {
            if (!controller.signal.aborted) setCheckingSession(false);
          });
      });
    return () => controller.abort();
  }, [client, search]);

  useEffect(() => {
    const controller = new AbortController();
    void loadAuthMethods(controller.signal);
    void api<PasskeyAuthenticationAvailability>("/auth/passkeys/authentication/availability", { signal: controller.signal })
      .then(({ enabled }) => {
        if (!controller.signal.aborted) setPasskeyLoginEnabled(enabled);
      })
      .catch(() => {
        if (!controller.signal.aborted) setPasskeyLoginEnabled(false);
      });
    return () => controller.abort();
  }, [loadAuthMethods]);

  useEffect(() => {
    if (!authMethodsReady
      || authMethods?.capabilities.captcha !== true
      || !passwordCaptchaAlways
      || captchaLoginUnavailable
      || challenge
      || loginCodeOpen
      || enrollmentRequired) setPasswordCaptchaReady(false);
  }, [
    authMethods,
    authMethodsReady,
    captchaLoginUnavailable,
    challenge,
    enrollmentRequired,
    loginCodeOpen,
    passwordCaptchaAlways,
  ]);

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

  const recoveryView = authView === "recovery" && !enrollmentRequired && !challenge && !loginCodeOpen;

  async function submit(values: LoginValues) {
    if (!isCurrentLoginAction() || !authMethodsReady || captchaLoginUnavailable
      || pending || passkeyPending || wechatPending
      || (passwordCaptchaAlways && !passwordCaptchaReady)) return;
    setPending(true);
    setError(null);
    setRetryAfterSeconds(null);
    let passwordCaptchaConsumed = false;
    try {
      let result: LoginResponse;
      if (passwordCaptchaAlways) {
        const proof = await passwordCaptchaPanelRef.current?.verifyForSubmit();
        if (!isCurrentLoginAction() || !captchaProof.isMounted() || !proof) return;
        passwordCaptchaConsumed = true;
        result = await login(values.username, values.password, proof.captchaProofId);
      } else {
        const outcome = await captchaProof.run(
          "PASSWORD_LOGIN",
          captchaProofId => login(values.username, values.password, captchaProofId),
        );
        if (!isCurrentLoginAction() || outcome.status !== "completed" || !captchaProof.isMounted()) return;
        result = outcome.value;
      }
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      loginFormRef.current?.setFieldValue("password", undefined);
      continueLogin(result);
    } catch (e) {
      if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
      if (passwordCaptchaConsumed
        && !(passwordCaptchaAlways && e instanceof ApiError && e.message === "captchaRequired")) {
        passwordCaptchaPanelRef.current?.refresh();
      }
      if (passwordCaptchaAlways && e instanceof ApiError && e.message === "captchaRequired") {
        // Reloading methods remounts the always-on panel, which loads a fresh challenge.
        const latest = await loadAuthMethods();
        if (!isCurrentLoginAction() || !captchaProof.isMounted()) return;
        if (latest?.capabilities.captcha !== true) {
          setCaptchaLoginUnavailable(true);
          setError("captchaUnavailable");
        } else {
          setError("captchaRequired");
        }
      } else if (e instanceof ApiError && e.message === "passwordHashCapacity") {
        setRetryAfterSeconds(e.retryAfterSeconds ?? 1);
        setError("passwordHashCapacity");
      } else if (e instanceof ApiError && (e.message === "temporaryPasswordExpired" || e.message === "temporaryPasswordUsed")) {
        setError("temporaryPasswordUnavailable");
      } else {
        setError(
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
      }
    } finally {
      if (isCurrentLoginAction() && captchaProof.isMounted()) setPending(false);
    }
  }

  async function signInWithPasskey() {
    if (!isCurrentLoginAction() || !authMethodsReady || captchaLoginUnavailable || pending || passkeyPending) return;
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
    if (!isCurrentLoginAction() || !authMethodsReady || captchaLoginUnavailable || pending || passkeyPending || wechatPending || !wechatLoginAvailable) return;
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

  function continueLogin(result: LoginResponse, nextPath?: string, expectedRevision = renderedLoginActionRevision) {
    if (!loginActionEpoch.isCurrent(expectedRevision)) return;
    setLoginCodeOpen(false);
    setError(null);
    const disposition = loginResponseDisposition(result);
    if (disposition === "enrollment") {
      setChallenge(result);
      setEnrollmentRequired(true);
      return;
    }
    if (disposition === "password-change") {
      setChallenge(result);
      setEnrollmentRequired(false);
      setPostPrimaryCaptchaOpen(false);
      loginFormRef.current?.setFieldValue("password", undefined);
      return;
    }
    if (disposition === "mfa") {
      setChallenge(result);
      setEnrollmentRequired(false);
      return;
    }
    if (disposition === "captcha") {
      setChallenge(result);
      setEnrollmentRequired(false);
      return;
    }
    if (disposition === "recovery") {
      setChallenge(result);
      setEnrollmentRequired(false);
      return;
    }
    if (disposition !== "complete") {
      setChallenge(result);
      setError("loginContinuationUnavailable");
      return;
    }
    setChallenge(null);
    setEnrollmentRequired(false);
    if (result.authenticated) announceSessionChangeAcrossTabs();
    history.replace(safeReturnPath(nextPath ?? new URLSearchParams(window.location.search).get("returnTo")));
  }

  function requiredPasswordChanged(completedChallengeId: string, result: LoginResponse, submittedSessionRevision: number) {
    if (!isCurrentLoginAction()
      || submittedSessionRevision !== currentSessionRevision()
      || challenge?.state !== "PASSWORD_CHANGE_REQUIRED"
      || challenge.challengeId !== completedChallengeId) return;
    loginFormRef.current?.setFieldValue("password", undefined);
    continueLogin(result, result.nextPath ?? undefined, renderedLoginActionRevision);
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
    if (!isCurrentLoginAction() || pending || restrictedLoginCancelInFlight.current) return;
    if (!challenge?.challengeId) {
      if (challenge?.state === "PASSWORD_CHANGE_REQUIRED") {
        setError("loginContinuationUnavailable");
        return;
      }
      captchaProof.cancel();
      setPostPrimaryCaptchaOpen(false);
      setChallenge(null);
      setEnrollmentRequired(false);
      setError(null);
      return;
    }
    restrictedLoginCancelInFlight.current = true;
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
      restrictedLoginCancelInFlight.current = false;
      if (isCurrentLoginAction() && captchaProof.isMounted()) setPending(false);
    }
  }

  return (
    <>
      <AuthPageLayout inert={passwordChangeOpen}>
          <MaintenanceNotice />
          <div className="login-tab" role="heading" aria-level={2}>{t(recoveryView ? "forgotPassword" : enrollmentRequired ? "preauthEnrollmentTitle" : passwordChangeOpen ? "passwordLogin" : challenge ? "mfaTitle" : loginCodeOpen ? "loginCodeSignIn" : "passwordLogin")}</div>
          <FeedbackBubble kind="success" id="login-recovered" content={recovered ? t("passwordRecovered") : undefined} />
          <FeedbackBubble id="login-error" content={!passwordChangeOpen && error ? t(error, error === "passwordHashCapacity" ? { seconds: retryAfterSeconds ?? 1 } : undefined) : undefined} />
          {!recoveryView && authMethodsLoadFailed && <Space direction="vertical" style={{ width: "100%" }}>
            <Alert type="warning" showIcon title={t("unavailable")} />
            <Button block disabled={!authMethodsLoaded} onClick={() => { void loadAuthMethods(); }}>{t("retry")}</Button>
          </Space>}
          {!recoveryView && captchaLoginUnavailable && !authMethodsLoadFailed && <Space direction="vertical" style={{ width: "100%" }}>
            <Alert type="warning" showIcon title={t("captchaRequiredUnavailable")} />
            <Button block disabled={!authMethodsLoaded} onClick={() => { void loadAuthMethods(); }}>{t("retry")}</Button>
          </Space>}
          {captchaProof.action && <CaptchaPanel
            key={`captcha-proof:${loginFormRevision}`}
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
          {recoveryView ? <PasswordRecoveryForm
            key={loginFormRevision}
            onBack={() => {
              setAuthView("login");
              setError(null);
            }}
            onRecovered={username => {
              if (!isCurrentLoginAction()) return;
              setAuthView("login");
              setRecovered(true);
              setRecoveredUsername(username);
              setError(null);
            }}
          /> : enrollmentRequired ? (
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
              onComplete={result => continueLogin(result)}
            />
          ) : challenge && challenge.state === "CAPTCHA_REQUIRED" ? <>
            {authMethodsLoaded && !authMethods?.capabilities.captcha && <Alert type="warning" showIcon title={t("captchaRequiredUnavailable")} />}
            <Button block disabled={pending} onClick={() => { void cancelRestrictedLogin(); }}>{t("loginCodeBack")}</Button>
          </> : challenge && !passwordChangeOpen ? <>
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
            key={`password-login:${loginFormRevision}`}
            onFinish={submit}
            onFormReady={onLoginFormReady}
            pending={pending}
            passkeyPending={passkeyPending}
            wechatPending={wechatPending}
            authMethodsReady={authMethodsReady && !captchaLoginUnavailable}
            passwordCaptchaRequired={passwordCaptchaAlways}
            passwordCaptchaReady={passwordCaptchaReady}
            passwordCaptcha={passwordCaptchaAlways
              && !captchaProof.action
              && authMethods?.capabilities.captcha === true
              && !captchaLoginUnavailable
              ? <CaptchaPanel
                ref={passwordCaptchaPanelRef}
                action="PASSWORD_LOGIN"
                mode="password-login"
                disabled={pending}
                onSubmitReady={setPasswordCaptchaReady}
                onVerified={() => {
                  if (isCurrentLoginAction()) setPasswordCaptchaReady(false);
                }}
                onCancel={() => { setPasswordCaptchaReady(false); }}
              />
              : null}
            passkeyLoginEnabled={authMethods ? authMethods.methods.includes("PASSKEY") : passkeyLoginEnabled}
            wechatLoginAvailable={wechatLoginAvailable}
            registrationAvailable={authMethods?.registration.supported === true
              && authMethods.registration.available
              && authMethods.registration.path === "/user/register"}
            loginCodeAvailable={loginCodeChannels.length > 0}
            onPasskeyClick={() => { void signInWithPasskey(); }}
            onWechatClick={() => { void signInWithWechat(); }}
            onRecoveryClick={() => {
              if (!isCurrentLoginAction()) return;
              setAuthView("recovery");
              setRecovered(false);
              setError(null);
            }}
            onRegistrationClick={() => { if (isCurrentLoginAction()) history.push(authRouteWithReturnTo("/user/register", search)); }}
            onLoginCodeClick={() => { if (isCurrentLoginAction()) setLoginCodeOpen(true); }}
            locale={locale}
            registrationUsername={recoveredUsername ?? registrationUsername}
            t={t}
          />}
          <AgreementLinks />
      </AuthPageLayout>
      <Modal
        open={passwordChangeOpen}
        title={t("mandatoryPasswordChangeTitle")}
        width={560}
        centered
        footer={null}
        closable={false}
        keyboard={false}
        maskClosable={false}
        destroyOnHidden
      >
        {passwordChangeOpen && challenge?.challengeId ? <div className="auth-mandatory-password-dialog" style={{ width: "100%", maxWidth: 460, marginInline: "auto" }}>
          <FeedbackBubble id="mandatory-password-change-cancel-error" content={error ? t(error) : undefined} />
          <MandatoryPasswordChangeForm
            challengeId={challenge.challengeId}
            expiresAt={challenge.expiresAt}
            reason={passwordChangeReason(challenge)}
            cancelPending={pending}
            onCancel={cancelRestrictedLogin}
            onChanged={requiredPasswordChanged}
          />
        </div> : passwordChangeOpen ? <Alert type="error" showIcon title={t("loginContinuationUnavailable")} /> : null}
      </Modal>
    </>
  );
}

function isLoginResponse(value: unknown): value is LoginResponse {
  return isLoginResponseValue(value);
}

function isLoginMethodAvailability(value: unknown): value is LoginMethodAvailability {
  if (!value || typeof value !== "object") return false;
  const methods = value as Partial<LoginMethodAvailability>;
  return Array.isArray(methods.methods)
    && methods.methods.every(method => typeof method === "string")
    && typeof methods.registration?.supported === "boolean"
    && typeof methods.registration.available === "boolean"
    && Array.isArray(methods.registration.channels)
    && methods.registration.channels.every(channel => typeof channel === "string")
    && (methods.registration.path === null || typeof methods.registration.path === "string")
    && typeof methods.capabilities?.loginCode === "boolean"
    && typeof methods.capabilities.wechat === "boolean"
    && typeof methods.capabilities.captcha === "boolean"
    && typeof methods.capabilities.rolePreview === "boolean"
    && typeof methods.captcha?.required === "boolean"
    && (methods.captcha.type === null || typeof methods.captcha.type === "string")
    && Array.isArray(methods.captcha.actions)
    && methods.captcha.actions.every(action => typeof action === "string")
    && Array.isArray(methods.captcha.alwaysActions)
    && methods.captcha.alwaysActions.every(action => typeof action === "string");
}
