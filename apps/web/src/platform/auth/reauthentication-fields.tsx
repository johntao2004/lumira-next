"use client";

import { Alert, Form as AntForm, Input, Radio, Space, Typography } from "antd";
import { useEffect, useRef, useState } from "react";
import type { FormInstance } from "antd";
import { ApiError, mutate } from "@/platform/api/client";
import type { PasskeyOptions, ReauthenticationCodeChallenge, ReauthenticationMethod, ReauthenticationStart, ReauthenticationTicket } from "@/platform/auth/api-types";
import { createPasskeyCredential, isPasskeySupported, toAuthenticationPayload, toPublicKeyRequestOptions } from "@/platform/auth/passkey";
import type { ReauthenticationFormValues } from "@/platform/auth/reauthentication-contract";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";

const methodLabels: Record<ReauthenticationMethod, "passwordLogin" | "emailOtpLogin" | "smsOtpLogin" | "passkeyLogin"> = {
  PASSWORD: "passwordLogin",
  EMAIL_OTP: "emailOtpLogin",
  SMS_OTP: "smsOtpLogin",
  PASSKEY: "passkeyLogin",
};

const errorKeys = [
  "reauthenticationInvalid", "reauthenticationExpired", "reauthenticationLocked",
  "reauthenticationMethodUnavailable", "reauthenticationCodeLimited", "secondFactorInvalid",
  "secondFactorUnavailable", "passkeyUnsupported", "passkeyCancelled", "passkeyTimeout",
  "passkeyOperationFailed", "forbidden", "loginRateLimited", "unavailable",
] as const;

export function ReauthenticationFields<T extends ReauthenticationFormValues>({
  challenge,
  form,
  hint,
  currentPasswordLabel,
  passwordRequired,
  factorCodeLabel,
  secondFactorInvalid,
  onPasskeyTicket,
}: {
  challenge: ReauthenticationStart;
  form: FormInstance<T>;
  hint: string;
  currentPasswordLabel: string;
  passwordRequired: string;
  factorCodeLabel: string;
  secondFactorInvalid: string;
  onPasskeyTicket: (ticket: ReauthenticationTicket) => void;
}) {
  const fields = form as unknown as FormInstance<ReauthenticationFormValues>;
  const { t } = usePreferences();
  const [codeChallenge, setCodeChallenge] = useState<ReauthenticationCodeChallenge | null>(null);
  const [now, setNow] = useState(Date.now());
  const [passkeyTicketReady, setPasskeyTicketReady] = useState(false);
  const previousMethod = useRef<ReauthenticationMethod | null>(null);
  const method = Form.useWatch("reauthenticationMethod", fields) ?? challenge.methods[0];
  const currentMethod = useRef(method);
  currentMethod.current = method;
  const generation = useRef(0);
  const factorCode = Form.useWatch("factorCode", fields);
  const sendCode = useSubmitAction({ errorKeys, fallback: "unavailable" });
  const passkeyAction = useSubmitAction({
    errorKeys,
    fallback: "passkeyOperationFailed",
    mapError: cause => {
      if (cause instanceof Error && cause.name === "NotAllowedError") return "passkeyCancelled";
      if (cause instanceof Error && cause.message === "passkeyUnsupported") return "passkeyUnsupported";
      if (cause instanceof Error && cause.message === "passkeyTimeout") return "passkeyTimeout";
      return undefined as never;
    },
  });

  useEffect(() => {
    generation.current += 1;
    const first = challenge.methods[0];
    fields.setFieldValue("reauthenticationMethod", first);
    fields.setFieldsValue({ currentPassword: undefined, factorCode: undefined, reauthenticationCodeChallengeId: undefined, reauthenticationCode: undefined });
    previousMethod.current = first ?? null;
    setCodeChallenge(null);
    setPasskeyTicketReady(false);
  }, [challenge, fields]);

  useEffect(() => {
    if (!codeChallenge) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [codeChallenge]);

  useEffect(() => {
    const previous = previousMethod.current;
    if (!method || !previous || method === previous) return;
    generation.current += 1;
    previousMethod.current = method;
    setCodeChallenge(null);
    setPasskeyTicketReady(false);
    fields.setFieldsValue({ reauthenticationCodeChallengeId: undefined, reauthenticationCode: undefined, currentPassword: undefined, factorCode: undefined });
  }, [fields, method]);

  async function requestCode() {
    if (!method || !["EMAIL_OTP", "SMS_OTP"].includes(method)) return;
    const requestedMethod = method;
    const requestGeneration = generation.current;
    await sendCode.run(async () => {
      const result = await mutate<ReauthenticationCodeChallenge>("/account/reauthentication/code", "POST", {
        challengeId: challenge.challengeId,
        method: requestedMethod,
      });
      if (!result.codeChallengeId || !result.expiresAt || !result.resendAt) throw new ApiError(503, "unavailable");
      if (generation.current !== requestGeneration || currentMethod.current !== requestedMethod) return;
      setCodeChallenge(result);
      fields.setFieldsValue({ reauthenticationCodeChallengeId: result.codeChallengeId, reauthenticationCode: undefined });
    });
  }

  async function authenticateWithPasskey() {
    if (!challenge.methods.includes("PASSKEY")) return;
    if (!isPasskeySupported()) {
      passkeyAction.setError("passkeyUnsupported");
      return;
    }
    if (challenge.factorRequired) {
      try { await fields.validateFields(["factorCode"]); } catch { return; }
    }
    await passkeyAction.run(async () => {
      const options = await mutate<PasskeyOptions>("/account/reauthentication/passkey/options", "POST", {
        challengeId: challenge.challengeId,
      });
      const credential = await createPasskeyCredential(toPublicKeyRequestOptions(options));
      const assertion = toAuthenticationPayload(credential, options.challengeId);
      const ticket = await mutate<ReauthenticationTicket>("/account/reauthentication/passkey/complete", "POST", {
        challengeId: challenge.challengeId,
        credential: assertion,
        ...(challenge.factorRequired ? { factorCode } : {}),
      });
      onPasskeyTicket(ticket);
      fields.setFieldsValue({ factorCode: undefined, currentPassword: undefined, reauthenticationCode: undefined, reauthenticationCodeChallengeId: undefined });
      setPasskeyTicketReady(true);
    });
  }

  const resendSeconds = codeChallenge ? Math.max(0, Math.ceil((Date.parse(codeChallenge.resendAt) - now) / 1000)) : 0;
  if (!challenge.methods.length) return <Alert type="warning" showIcon message={t("reauthenticationMethodUnavailable")} />;

  return (
    <>
      <Typography.Paragraph>{hint}</Typography.Paragraph>
      <Form.Item name="reauthenticationMethod" label={t("reauthenticationMethod")} rules={[{ required: true }]}>
        <Radio.Group
          options={challenge.methods.map(value => ({ value, label: t(methodLabels[value]) }))}
          onChange={() => {
            generation.current += 1;
            setCodeChallenge(null);
            setPasskeyTicketReady(false);
            fields.setFieldsValue({ reauthenticationCodeChallengeId: undefined, reauthenticationCode: undefined, currentPassword: undefined, factorCode: undefined });
          }}
        />
      </Form.Item>

      {method === "PASSWORD" && <Form.Item name="currentPassword" label={currentPasswordLabel} rules={[{ required: true, message: passwordRequired }]}>
        <Input.Password autoComplete="current-password" />
      </Form.Item>}

      {(method === "EMAIL_OTP" || method === "SMS_OTP") && <>
        <Space wrap className="mb-3">
          <AsyncButton
            pending={sendCode.pending}
            disabled={resendSeconds > 0}
            onClick={() => void requestCode()}
            label={resendSeconds > 0 ? t("registrationResendCountdown", { seconds: resendSeconds }) : t(codeChallenge ? "registrationResendCode" : "reauthenticationSendCode")}
          />
          {codeChallenge && <Typography.Text type="secondary">{t("reauthenticationCodeSent")}</Typography.Text>}
        </Space>
        <AntForm.Item name="reauthenticationCodeChallengeId" hidden><Input /></AntForm.Item>
        <Form.Item name="reauthenticationCode" label={t("reauthenticationCode")} rules={[{ required: true, message: t("reauthenticationCodeRequired") }]}>
          <Input autoComplete="one-time-code" inputMode="numeric" maxLength={8} />
        </Form.Item>
        <FeedbackBubble id="reauth-code-error" content={sendCode.errorMessage} />
      </>}

      {method === "PASSKEY" && <>
        <Typography.Paragraph type="secondary">{t("reauthenticationPasskeyHint")}</Typography.Paragraph>
        <AsyncButton
          pending={passkeyAction.pending}
          disabled={passkeyTicketReady}
          onClick={() => void authenticateWithPasskey()}
          label={t("reauthenticationPasskey")}
        />
        {passkeyTicketReady && <Alert className="mt-3" type="success" showIcon message={t("reauthenticationTicketReady")} />}
        <FeedbackBubble id="reauth-passkey-error" content={passkeyAction.errorMessage} />
      </>}

      {challenge.factorRequired && <Form.Item name="factorCode" label={factorCodeLabel} rules={[{ required: true, message: secondFactorInvalid }]}>
        <Input autoComplete="one-time-code" maxLength={64} />
      </Form.Item>}
    </>
  );
}
