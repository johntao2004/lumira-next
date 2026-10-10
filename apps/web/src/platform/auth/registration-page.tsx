"use client";

import { useEffect, useMemo, useState } from "react";
import { Alert, Button, Checkbox, Input, Modal, Radio, Skeleton, Space, Typography } from "antd";
import { MoonOutlined, SunOutlined } from "@ant-design/icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { history } from "@umijs/max";
import { ApiError, api, mutate } from "@/platform/api/client";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { MarkdownContent } from "@/platform/components/markdown-content";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { ProfileFieldRenderer } from "@/platform/profile-fields/profile-field-renderer";
import type { ProfileFieldDefinition } from "@/platform/profile-fields/types";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { LanguageSwitcher } from "@/platform/i18n/language-switcher";
import type { MessageKey } from "@/platform/i18n/messages";
import { messageKeyFor } from "@/platform/api/errors";
import { usePreferences } from "@/platform/providers";
import { useBranding } from "@/platform/branding/provider";
import { BrandFooter } from "@/platform/branding/footer";
import { NewPasswordFields } from "@/platform/components/new-password-fields";
import type { PasswordRules } from "./password-contract";
import type { LoginMethodAvailability, WechatAttempt, WechatResult } from "./api-types";
import { isTrustedWechatAuthorizationUrl } from "./wechat-url";
import { CaptchaPanel } from "./captcha-panel";
import { useCaptchaProof } from "./use-captcha-proof";

type RegistrationChannel = "EMAIL" | "SMS";
type RegistrationConfig = {
  available: boolean;
  reason: "REGISTRATION_CLOSED" | "REGISTRATION_UNAVAILABLE" | "MAINTENANCE" | null;
  channels: RegistrationChannel[];
  policyVersion: number;
  passwordRules: PasswordRules;
  codePolicy: { expiresInSeconds: number; cooldownSeconds: number };
  agreements: { version: number; userAgreementMarkdown: string; privacyAgreementMarkdown: string };
};
type RegistrationChallenge = { challengeId: string; expiresAt: string; resendAt: string };
type VerifiedRegistrationProof = { proofId: string; expiresAt: string };
type RegistrationState = {
  state: "NONE" | "PENDING" | "VERIFIED" | "CONSUMED" | "EXPIRED" | "LOCKED" | "SUPERSEDED";
  channel: RegistrationChannel | null;
  maskedContact: string | null;
  challengeId: string | null;
  proofId: string | null;
  expiresAt: string | null;
  resendAt: string | null;
  receipt: RegistrationReceipt | null;
};
type RegistrationReceipt = {
  receiptId: string;
  username: string;
  nextPath: "/user/login";
  wechatBinding: { status: "NOT_REQUESTED" | "BOUND" | "FAILED"; reason: string | null };
};
type PublicProfileSchema = { schemaVersion: number; fields: ProfileFieldDefinition[] };

const registrationErrorKeys = [
  "registrationUnavailable", "registrationRateLimited", "registrationChallengeInvalid", "registrationChallengeExpired",
  "registrationContactAlreadyUsed", "registrationUsernameTaken", "agreementChanged", "staleRecord", "invalidPassword",
  "registrationPolicyChanged", "registrationProofInvalid", "registrationRequestConflict",
  "registrationRequestPending", "registrationProfileSchemaChanged", "invalidRegistrationRequest",
  "registrationChannelUnavailable", "registrationProfileValuesInvalid",
  "passwordPolicy", "passwordMismatch", "invalidEmail", "invalidPhone", "sensitiveContent", "captchaUnavailable",
  "captchaRequired", "registrationProfileSchemaUnavailable", "registrationDictionaryTooLarge",
  "wechatUnavailable", "wechatAlreadyUsed", "wechatRequiredUsableMethod",
  "forbidden", "unavailable", "maintenanceActive", "invalidRegistrationUsername", "invalidRegistrationDisplayName",
] as const satisfies readonly MessageKey[];

const registrationErrorAliases = {
  registrationAgreementChanged: "agreementChanged",
  registrationLimited: "registrationRateLimited",
  invalidRegistrationChallenge: "registrationChallengeInvalid",
  registrationProofExpired: "registrationChallengeExpired",
  invalidRegistrationChannel: "registrationChannelUnavailable",
  invalidProfileValues: "registrationProfileValuesInvalid",
  registrationReceiptUnavailable: "registrationUnavailable",
  staleRecord: "registrationProfileSchemaChanged",
} as const satisfies Readonly<Record<string, MessageKey>>;

function mapPublicFields(value: PublicProfileSchema | undefined): ProfileFieldDefinition[] | null {
  if (!value || !Number.isSafeInteger(value.schemaVersion) || value.schemaVersion < 0 || !Array.isArray(value.fields) || value.fields.length > 20) return null;
  if (new TextEncoder().encode(JSON.stringify(value)).length > 1024 * 1024) return null;
  const allowedTypes = new Set(["TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT"]);
  const keys = new Set<string>();
  const fields: ProfileFieldDefinition[] = [];
  let optionCount = 0;
  for (const raw of value.fields) {
    const options = raw?.options ?? [];
    if (!raw || typeof raw.key !== "string" || !/^[A-Za-z][A-Za-z0-9_.-]{0,99}$/.test(raw.key)
      || keys.has(raw.key) || typeof raw.label !== "string" || !allowedTypes.has(raw.type)
      || !Array.isArray(options) || options.length > 200) return null;
    optionCount += options.length;
    if (optionCount > 1000) return null;
    keys.add(raw.key);
    fields.push({
      key: raw.key,
      label: raw.label,
      description: typeof raw.description === "string" ? raw.description : null,
      type: raw.type,
      required: false,
      placeholder: typeof raw.placeholder === "string" ? raw.placeholder : null,
      options: options.filter(option => typeof option === "string" || Boolean(option && typeof option.value === "string" && typeof option.label === "string")),
    });
  }
  return fields;
}

export function RegistrationPage() {
  const branding = useBranding();
  const { dark, locale, toggleDark, t } = usePreferences();
  const client = useQueryClient();
  const [contactForm] = Form.useForm<{ contact: string }>();
  const [accountForm] = Form.useForm<Record<string, unknown>>();
  const [channel, setChannel] = useState<RegistrationChannel>("EMAIL");
  const [contact, setContact] = useState("");
  const [code, setCode] = useState("");
  const [localChallenge, setLocalChallenge] = useState<RegistrationChallenge | null>(null);
  const [localProof, setLocalProof] = useState<VerifiedRegistrationProof | null>(null);
  const [localReceipt, setLocalReceipt] = useState<RegistrationReceipt | null>(null);
  const [agreementOpen, setAgreementOpen] = useState<"user" | "privacy" | null>(null);
  const [requestId, setRequestId] = useState<string | null>(null);
  const [secondsUntilResend, setSecondsUntilResend] = useState(0);
  const action = useSubmitAction({
    errorKeys: registrationErrorKeys,
    fallback: "registrationUnavailable",
    mapError: cause => {
      if (cause instanceof ApiError && cause.status === 429) return "registrationRateLimited";
      if (cause instanceof ApiError && cause.message === "invalidRegistrationContact")
        return channel === "SMS" ? "invalidPhone" : "invalidEmail";
      return messageKeyFor(cause, registrationErrorKeys, "registrationUnavailable", registrationErrorAliases);
    },
    onError: async (key, cause) => {
      if (cause instanceof ApiError && cause.status === 429 && cause.retryAfterSeconds) {
        setSecondsUntilResend(cause.retryAfterSeconds);
      }
      if (key === "registrationPolicyChanged") {
        setRequestId(null);
        await Promise.all([config.refetch(), registrationState.refetch()]);
      }
      if (key === "agreementChanged") {
        setRequestId(null);
        accountForm.setFieldValue("acceptAgreements", false);
        await config.refetch();
      }
      if (key === "registrationProofInvalid" || key === "registrationChallengeExpired") {
        setRequestId(null);
        setLocalProof(null);
        setLocalChallenge(null);
        setCode("");
        client.setQueryData<RegistrationState>(["public-registration-state"], current =>
          current ? { ...current, proofId: null } : current,
        );
        await Promise.all([
          registrationState.refetch(),
          client.invalidateQueries({ queryKey: ["wechat-result"] }),
        ]);
      }
      if (key === "registrationRequestConflict") {
        setRequestId(null);
        await registrationState.refetch();
      }
      if (key === "registrationRequestPending") {
        await registrationState.refetch();
      }
      if (key === "registrationProfileSchemaChanged") {
        setRequestId(null);
        await profileSchema.refetch();
      }
      if (key === "registrationChannelUnavailable") {
        setLocalChallenge(null);
        setCode("");
        await config.refetch();
      }
    },
  });
  const config = useQuery({
    queryKey: ["public-registration-config"],
    queryFn: ({ signal }) => api<RegistrationConfig>("/auth/registration/config", { signal }),
    retry: false,
  });
  const registrationState = useQuery({
    queryKey: ["public-registration-state"],
    queryFn: ({ signal }) => api<RegistrationState>("/auth/registration/state", { signal }),
    retry: false,
  });
  const profileSchema = useQuery({
    queryKey: ["public-registration-profile-schema"],
    queryFn: ({ signal }) => api<PublicProfileSchema>("/auth/registration/profile-schema", { signal }),
    enabled: Boolean(config.data?.available),
    retry: false,
  });
  const authMethods = useQuery({
    queryKey: ["auth-methods"],
    queryFn: ({ signal }) => api<LoginMethodAvailability>("/auth/methods", { signal }),
    retry: false,
  });
  const captchaProof = useCaptchaProof({
    alwaysActions: authMethods.data?.captcha.alwaysActions,
    supported: authMethods.data?.capabilities.captcha === true,
    unavailableMessage: "captchaUnavailable",
  });

  const restored = registrationState.data;
  const receipt = localReceipt ?? restored?.receipt ?? null;
  const challengeId = localChallenge?.challengeId ?? restored?.challengeId ?? null;
  const proofId = localProof?.proofId ?? restored?.proofId ?? null;
  const resendAt = localChallenge?.resendAt ?? restored?.resendAt ?? null;
  const fields = useMemo(() => mapPublicFields(profileSchema.data), [profileSchema.data]);
  const registrationSupported = authMethods.data?.registration.supported === true;
  const registrationOpen = Boolean(config.data?.available && registrationSupported && authMethods.data?.registration.available);
  const wechatResult = useQuery({
    queryKey: ["wechat-result", proofId ?? null],
    queryFn: ({ signal }) => api<WechatResult>("/auth/wechat/result", { signal }),
    enabled: Boolean(proofId && authMethods.data?.capabilities.wechat),
    staleTime: 0,
    retry: false,
  });
  const wechatRegistrationProofId = wechatResult.data?.purpose === "REGISTRATION" && wechatResult.data.status === "READY"
    ? wechatResult.data.wechatRegistrationProofId
    : null;
  const brandingBackground = branding.loginBackgroundUrl.trim();
  const registrationCaptchaRequired = Boolean(
    authMethods.data?.captcha.alwaysActions.some(actionName => ["REGISTRATION_SEND", "REGISTRATION_VERIFY", "REGISTRATION_COMPLETE"].includes(actionName)),
  );
  const captchaBlocksRegistration = registrationCaptchaRequired && !authMethods.data?.capabilities.captcha;
  const accountFormVisible = Boolean(proofId && registrationOpen && !config.error && !receipt && !captchaBlocksRegistration);

  useEffect(() => {
    if (restored?.channel) setChannel(restored.channel);
    if (restored?.state === "CONSUMED" && restored.receipt) setLocalReceipt(restored.receipt);
  }, [restored]);

  useEffect(() => {
    const availableChannels = config.data?.channels ?? [];
    if (availableChannels.length > 0 && !availableChannels.includes(channel)) {
      setChannel(availableChannels[0]);
    }
  }, [config.data?.channels, channel]);

  useEffect(() => {
    if (!resendAt) {
      setSecondsUntilResend(0);
      return;
    }
    const update = () => setSecondsUntilResend(Math.max(0, Math.ceil((Date.parse(resendAt) - Date.now()) / 1000)));
    update();
    const timer = window.setInterval(update, 1000);
    return () => window.clearInterval(timer);
  }, [resendAt]);

  useEffect(() => {
    if (!accountFormVisible) return;
    const invalid = accountForm.getFieldsError().filter(field => field.errors.length).map(field => field.name);
    if (invalid.length) void accountForm.validateFields(invalid).catch(() => {});
  }, [locale, accountForm, accountFormVisible]);

  if (config.isPending || registrationState.isPending || authMethods.isPending) {
    return <main className="workspace-loading"><Skeleton active paragraph={{ rows: 8 }} /></main>;
  }

  const startChallenge = async () => {
    const normalized = contact.trim();
    if (channel === "EMAIL" && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized)) {
      action.setError("invalidEmail");
      return;
    }
    if (channel === "SMS" && !/^\+[1-9]\d{6,14}$/.test(normalized)) {
      action.setError("invalidPhone");
      return;
    }
    await action.run(async () => {
      const outcome = await captchaProof.run("REGISTRATION_SEND", captchaProofId => mutate<RegistrationChallenge>("/auth/registration/challenges", "POST", {
        channel,
        contact: normalized,
        locale,
        ...(captchaProofId ? { captchaProofId } : {}),
      }));
      if (outcome.status !== "completed" || !captchaProof.isMounted()) return false;
      const next = outcome.value;
      setLocalChallenge(next);
      setLocalProof(null);
      setCode("");
      setSecondsUntilResend(Math.max(0, Math.ceil((Date.parse(next.resendAt) - Date.now()) / 1000)));
      await client.invalidateQueries({ queryKey: ["public-registration-state"] });
      return true;
    });
  };

  const verifyChallenge = async () => {
    if (!challengeId || !/^\d{6}$/.test(code)) {
      action.setError(challengeId ? "registrationChallengeInvalid" : "registrationChallengeExpired");
      return;
    }
    await action.run(async () => {
      const outcome = await captchaProof.run("REGISTRATION_VERIFY", captchaProofId => mutate<VerifiedRegistrationProof>("/auth/registration/challenges/verify", "POST", {
        challengeId,
        code,
        ...(captchaProofId ? { captchaProofId } : {}),
      }));
      if (outcome.status !== "completed" || !captchaProof.isMounted()) return false;
      const verified = outcome.value;
      setLocalProof(verified);
      setCode("");
      await client.invalidateQueries({ queryKey: ["public-registration-state"] });
      return true;
    });
  };

  const completeRegistration = async (values: Record<string, unknown>) => {
    if (!proofId || !config.data || !registrationOpen) return;
    const completionRequestId = requestId ?? crypto.randomUUID();
    await action.run(async () => {
      const outcome = await captchaProof.run("REGISTRATION_COMPLETE", async captchaProofId => {
        const profileValues: Record<string, string> = {};
        for (const field of fields ?? []) {
          const value = values[field.key];
          if (value !== undefined && value !== null && String(value).trim() !== "") profileValues[field.key] = String(value);
        }
        setRequestId(completionRequestId);
        const body: Record<string, unknown> = {
          proofId,
          username: values.username,
          displayName: values.displayName,
          password: values.password,
          policyVersion: config.data.policyVersion,
          agreementVersion: config.data.agreements.version,
          acceptAgreements: values.acceptAgreements === true,
          requestId: completionRequestId,
          ...(captchaProofId ? { captchaProofId } : {}),
          ...(wechatRegistrationProofId ? { wechatRegistrationProofId } : {}),
        };
        if (Object.keys(profileValues).length > 0 && profileSchema.data && fields) {
          body.profileSchemaVersion = profileSchema.data.schemaVersion;
          body.profileValues = profileValues;
        }
        return mutate<RegistrationReceipt>("/auth/registration/complete", "POST", body, undefined, 120_000);
      });
      if (outcome.status !== "completed" || !captchaProof.isMounted()) return false;
      const completed = outcome.value;
      setLocalReceipt(completed);
      setLocalChallenge(null);
      setLocalProof(null);
      setRequestId(null);
      accountForm.resetFields();
      await client.invalidateQueries({ queryKey: ["public-registration-state"] });
      return true;
    });
  };

  const startWechatRegistration = async () => {
    if (!proofId || authMethods.data?.capabilities.wechat !== true) return;
    await action.run(async () => {
      const outcome = await captchaProof.run("WECHAT_LOGIN", captchaProofId => mutate<WechatAttempt>("/auth/wechat/attempts", "POST", {
        purpose: "REGISTRATION",
        registrationProofId: proofId,
        ...(captchaProofId ? { captchaProofId } : {}),
      }));
      if (outcome.status !== "completed" || !captchaProof.isMounted()) return false;
      const attempt = outcome.value;
      if (attempt.purpose !== "REGISTRATION" || !isTrustedWechatAuthorizationUrl(attempt.authorizationUrl)) {
        throw new ApiError(503, "wechatUnavailable");
      }
      window.location.assign(attempt.authorizationUrl);
      return true;
    });
  };

  const channels = config.data?.channels ?? [];
  const completedReceipt = receipt;

  return <main className="login-page" data-has-background={Boolean(brandingBackground)} style={{ height: "auto", minHeight: "100dvh", overflowY: "auto" }}>
    {brandingBackground && <div className="login-hero" aria-hidden="true" style={{ backgroundImage: `url(${JSON.stringify(brandingBackground)})` }} />}
    <section className="login-shell" style={{ alignSelf: "start", paddingBlock: 72 }}>
      <Space className="login-utilities">
        <Button
          aria-label={t(dark ? "lightMode" : "darkMode")}
          icon={dark ? <SunOutlined /> : <MoonOutlined />}
          onClick={toggleDark}
        />
        <LanguageSwitcher />
      </Space>
      <div className="login-content" style={{ maxWidth: 620 }}>
        <Typography.Title level={1}>{branding.websiteName}</Typography.Title>
        <div className="login-tab">{t(completedReceipt ? "registrationComplete" : "publicRegistrationTitle")}</div>
        <FeedbackBubble id="registration-error" content={action.errorMessage} />
        {captchaProof.action && <CaptchaPanel action={captchaProof.action} onVerified={proof => { captchaProof.finish(proof); }} onCancel={() => { captchaProof.finish(null); }} />}
        {config.error ? <Alert type="error" showIcon title={t("registrationUnavailable")} action={<Button type="link" onClick={() => history.replace("/user/login")}>{t("mfaBack")}</Button>} /> : completedReceipt ? <>
          <Alert type="success" showIcon title={t("registrationCompleteHint", { username: completedReceipt.username })} />
          {completedReceipt.wechatBinding.status === "FAILED" && <Alert className="mt-4" type="warning" showIcon title={t("registrationWechatBindingFailed")} />}
          <Button className="mt-4" type="primary" block onClick={() => history.replace("/user/login", { registrationUsername: completedReceipt.username })}>{t("registrationGoToLogin")}</Button>
        </> : !registrationOpen ? <>
          <Alert type="info" showIcon title={registrationClosedMessage(config.data?.reason, t)} />
          <Button className="mt-4" block onClick={() => history.replace("/user/login")}>{t("mfaBack")}</Button>
        </> : captchaBlocksRegistration ? <>
          <Alert type="warning" showIcon title={t("captchaRequiredUnavailable")} />
          <Button className="mt-4" block onClick={() => history.replace("/user/login")}>{t("mfaBack")}</Button>
        </> : proofId ? <>
          <Typography.Paragraph>{t("registrationVerifiedContact", { contact: restored?.maskedContact ?? "" })}</Typography.Paragraph>
          <Typography.Paragraph type="secondary">{t("registrationAccountDetailsHint")}</Typography.Paragraph>
          {fields === null && <Alert type="warning" showIcon title={t(profileSchema.error ? "registrationProfileSchemaUnavailable" : "registrationOptionalFieldsUnavailable")} className="mb-4" />}
          {authMethods.data?.capabilities.wechat === true && <section className="mb-4" aria-label={t("wechatBinding")}>
            <Typography.Text strong>{t("wechatRegistrationOptional")}</Typography.Text>
            <Typography.Paragraph type="secondary">{t("wechatRegistrationOptionalHint")}</Typography.Paragraph>
            {wechatResult.isError && <Alert className="mb-3" type="warning" showIcon title={t("wechatRegistrationAttemptUnavailable")} />}
            {wechatRegistrationProofId
              ? <Alert className="mb-3" type="success" showIcon title={t("wechatRegistrationProofReady", { nickname: wechatResult.data?.identity?.nickname ?? t("wechatUnknownIdentity") })} />
              : <AsyncButton
                type="default"
                pending={action.pending}
                disabled={!proofId}
                onClick={() => void startWechatRegistration()}
                label={t("wechatRegistrationAttach")}
              />}
          </section>}
          <Form form={accountForm} layout="vertical" requiredMark={false} onValuesChange={() => setRequestId(null)} onFinish={completeRegistration}>
            <fieldset disabled={action.pending} style={{ border: 0, margin: 0, padding: 0 }}>
            <Form.Item name="username" label={t("username")} rules={[{ required: true, whitespace: true, min: 3, max: 100, pattern: /^[A-Za-z0-9][A-Za-z0-9_.-]{2,99}$/, message: t("invalidRegistrationUsername") }]}>
              <Input autoComplete="username" maxLength={100} />
            </Form.Item>
            <Form.Item name="displayName" label={t("displayName")} rules={[{ required: true, whitespace: true, max: 200, message: t("invalidRegistrationDisplayName") }]}>
              <Input autoComplete="name" maxLength={200} />
            </Form.Item>
            <NewPasswordFields rules={config.data.passwordRules} passwordName="password" passwordLabel="password" />
            {fields?.length ? <>
              <Typography.Title level={5}>{t("registrationAdditionalFields")}</Typography.Title>
              <Typography.Paragraph type="secondary">{t("profilePlacementRegistrationOptionalHint")}</Typography.Paragraph>
              <ProfileFieldRenderer fields={fields} disabled={action.pending} />
            </> : null}
            <Space wrap className="mb-3">
              <Button type="link" onClick={() => setAgreementOpen("user")}>{t("registrationUserAgreement")}</Button>
              <Button type="link" onClick={() => setAgreementOpen("privacy")}>{t("registrationPrivacyAgreement")}</Button>
            </Space>
            <Form.Item name="acceptAgreements" valuePropName="checked" rules={[{ validator: (_, value) => value === true ? Promise.resolve() : Promise.reject(new Error(t("registrationAgreementRequired"))) }]}>
              <Checkbox>{t("registrationAgreementAccept")}</Checkbox>
            </Form.Item>
            <AsyncButton htmlType="submit" type="primary" block pending={action.pending} disabled={!proofId || !registrationOpen} label={t("registrationCreateAccount")} />
            </fieldset>
          </Form>
          <Modal open={agreementOpen !== null} title={t(agreementOpen === "privacy" ? "registrationPrivacyAgreement" : "registrationUserAgreement")} onCancel={() => setAgreementOpen(null)} footer={<Button onClick={() => setAgreementOpen(null)}>{t("close")}</Button>}>
            <div style={{ maxHeight: "65dvh", overflowY: "auto" }}><MarkdownContent>{agreementOpen === "privacy" ? config.data.agreements.privacyAgreementMarkdown : config.data.agreements.userAgreementMarkdown}</MarkdownContent></div>
          </Modal>
          <Button type="link" className="mt-3" onClick={() => history.replace("/user/login")}>{t("mfaBack")}</Button>
        </> : <>
          <Typography.Paragraph>{t("registrationContactHint")}</Typography.Paragraph>
          <Form form={contactForm} layout="vertical" requiredMark={false}>
            <Form.Item label={t("registrationChannel")}>
              <Radio.Group value={channel} disabled={action.pending} onChange={event => { setChannel(event.target.value); setContact(""); setLocalChallenge(null); setCode(""); action.setError(null); }} options={channels.map(value => ({ value, label: t(value === "SMS" ? "registrationChannelSms" : "registrationChannelEmail") }))} />
            </Form.Item>
            <Form.Item label={t(channel === "SMS" ? "accountPhone" : "accountEmail")}>
              <Input disabled={action.pending} value={contact} onChange={event => setContact(event.target.value)} autoComplete={channel === "SMS" ? "tel" : "email"} inputMode={channel === "SMS" ? "tel" : "email"} maxLength={channel === "SMS" ? 16 : 254} placeholder={channel === "SMS" ? "+8613800138000" : undefined} />
            </Form.Item>
            <Space wrap>
              <AsyncButton type="default" pending={action.pending} disabled={Boolean(secondsUntilResend)} label={secondsUntilResend > 0 ? t("registrationResendCountdown", { seconds: secondsUntilResend }) : t(challengeId ? "registrationResendCode" : "registrationSendCode")} onClick={() => void startChallenge()} />
              {challengeId && <Typography.Text type="secondary">{t("registrationCodeExpiry", { seconds: config.data.codePolicy.expiresInSeconds })}</Typography.Text>}
            </Space>
            {challengeId && <>
              <Form.Item className="mt-4" label={t("emailCode")}>
                <Input disabled={action.pending} value={code} onChange={event => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))} inputMode="numeric" autoComplete="one-time-code" maxLength={6} />
              </Form.Item>
              <AsyncButton type="primary" block pending={action.pending} disabled={!/^\d{6}$/.test(code)} label={t("registrationVerifyCode")} onClick={() => void verifyChallenge()} />
            </>}
          </Form>
          <Button type="link" className="mt-3" onClick={() => history.replace("/user/login")}>{t("mfaBack")}</Button>
        </>}
        {restored?.maskedContact && !proofId && !completedReceipt && <Typography.Paragraph type="secondary" className="mt-3">{t("registrationChallengeRestored", { contact: restored.maskedContact })}</Typography.Paragraph>}
      </div>
      <footer><BrandFooter /></footer>
    </section>
  </main>;
}

function registrationClosedMessage(reason: RegistrationConfig["reason"] | undefined, t: (key: MessageKey) => string) {
  if (reason === "MAINTENANCE") return t("maintenanceActive");
  if (reason === "REGISTRATION_CLOSED") return t("registrationClosed");
  return t("registrationUnavailable");
}
