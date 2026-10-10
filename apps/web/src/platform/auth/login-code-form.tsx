"use client";

import { Alert, Button, Input, Radio, Space, Typography } from "antd";
import { useEffect, useState } from "react";
import { ApiError, mutate } from "@/platform/api/client";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";
import { CaptchaPanel } from "./captcha-panel";
import { useCaptchaProof } from "./use-captcha-proof";
import type { LoginResponse } from "./api-types";

type LoginCodeChannel = "EMAIL" | "SMS";
type LoginCodeChallenge = { challengeId: string; expiresAt: string; resendAt: string };

export function LoginCodeForm({
  channels,
  captchaAlwaysActions,
  captchaSupported,
  onComplete,
  onCancel,
}: {
  channels: LoginCodeChannel[];
  captchaAlwaysActions: string[];
  captchaSupported: boolean;
  onComplete: (result: LoginResponse) => void;
  onCancel: () => void;
}) {
  const { locale, t } = usePreferences();
  const [channel, setChannel] = useState<LoginCodeChannel>(channels[0] ?? "EMAIL");
  const [contact, setContact] = useState("");
  const [code, setCode] = useState("");
  const [challenge, setChallenge] = useState<LoginCodeChallenge | null>(null);
  const [secondsUntilResend, setSecondsUntilResend] = useState(0);
  const [retryAt, setRetryAt] = useState<number | null>(null);
  const captchaProof = useCaptchaProof({
    alwaysActions: captchaAlwaysActions,
    supported: captchaSupported,
    unavailableMessage: "captchaUnavailable",
  });
  const action = useSubmitAction({
    errorKeys: ["loginFailed", "loginRateLimited", "maintenanceActive", "captchaRequired", "captchaUnavailable", "forbidden", "unavailable"],
    fallback: "unavailable",
    mapError: cause => cause instanceof ApiError && cause.status === 429
      ? "loginRateLimited"
      : cause instanceof ApiError && cause.status === 403
        ? "forbidden"
        : cause instanceof ApiError && cause.status === 401
          ? "loginFailed"
          : typeof cause === "object" && cause instanceof Error && cause.message === "captchaRequired"
            ? "captchaRequired"
            : typeof cause === "object" && cause instanceof Error && cause.message === "captchaUnavailable"
              ? "captchaUnavailable"
              : "unavailable",
    onError: (_key, cause) => {
      if (cause instanceof ApiError && cause.status === 429 && cause.retryAfterSeconds !== null) {
        setRetryAt(Date.now() + cause.retryAfterSeconds * 1000);
      }
    },
  });

  useEffect(() => {
    const update = () => {
      const deadline = challenge?.resendAt ? Date.parse(challenge.resendAt) : retryAt;
      setSecondsUntilResend(deadline ? Math.max(0, Math.ceil((deadline - Date.now()) / 1000)) : 0);
    };
    update();
    const timer = window.setInterval(update, 1000);
    return () => window.clearInterval(timer);
  }, [challenge, retryAt]);

  async function sendCode() {
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
      const outcome = await captchaProof.run("LOGIN_CODE_SEND", async captchaProofId => {
        return mutate<LoginCodeChallenge>("/auth/login-code/challenges", "POST", {
          channel,
          contact: normalized,
          locale,
          ...(captchaProofId ? { captchaProofId } : {}),
        });
      });
      if (outcome.status !== "completed" || !captchaProof.isMounted()) return false;
      const next = outcome.value;
      setChallenge(next);
      setRetryAt(null);
      setCode("");
      return true;
    });
  }

  async function complete() {
    if (!challenge || !/^\d{6}$/.test(code)) return;
    await action.run(async () => {
      const outcome = await captchaProof.run("LOGIN_CODE_VERIFY", captchaProofId => mutate<LoginResponse>("/auth/login-code/complete", "POST", {
        challengeId: challenge.challengeId,
        code,
        ...(captchaProofId ? { captchaProofId } : {}),
      }));
      if (outcome.status !== "completed" || !captchaProof.isMounted()) return false;
      setCode("");
      onComplete(outcome.value);
      return true;
    });
  }

  return <>
    <Typography.Paragraph>{t("loginCodeContactHint")}</Typography.Paragraph>
    {captchaProof.action && <CaptchaPanel action={captchaProof.action} onVerified={proof => { captchaProof.finish(proof); }} onCancel={() => { captchaProof.finish(null); }} />}
    <FeedbackBubble id="login-code-error" content={action.error ? t(action.error) : undefined} />
    {captchaAlwaysActions.some(name => ["LOGIN_CODE_SEND", "LOGIN_CODE_VERIFY"].includes(name)) && !captchaSupported && <Alert className="mb-3" type="warning" showIcon title={t("captchaRequiredUnavailable")} />}
    <Form layout="vertical" requiredMark={false}>
      {channels.length > 1 && <Form.Item label={t("registrationChannel")}>
        <Radio.Group
          value={channel}
          disabled={action.pending || Boolean(challenge)}
          onChange={event => { setChannel(event.target.value); setContact(""); setChallenge(null); setCode(""); action.setError(null); }}
          options={channels.map(value => ({ value, label: t(value === "SMS" ? "registrationChannelSms" : "registrationChannelEmail") }))}
        />
      </Form.Item>}
      <Form.Item label={t(channel === "SMS" ? "accountPhone" : "accountEmail")}>
        <Input
          value={contact}
          disabled={action.pending || Boolean(challenge)}
          onChange={event => setContact(event.target.value)}
          autoComplete={channel === "SMS" ? "tel" : "email"}
          inputMode={channel === "SMS" ? "tel" : "email"}
          maxLength={channel === "SMS" ? 16 : 254}
          placeholder={channel === "SMS" ? "+8613800138000" : undefined}
        />
      </Form.Item>
      <Space wrap>
        <AsyncButton
          pending={action.pending}
          disabled={secondsUntilResend > 0 || (captchaAlwaysActions.includes("LOGIN_CODE_SEND") && !captchaSupported)}
          label={secondsUntilResend > 0 ? t("loginCodeResendCountdown", { seconds: secondsUntilResend }) : t(challenge ? "loginCodeResend" : "loginCodeSend")}
          onClick={() => void sendCode()}
        />
        {challenge && <Typography.Text type="secondary">{t("registrationCodeExpiry", { seconds: Math.max(0, Math.ceil((Date.parse(challenge.expiresAt) - Date.now()) / 1000)) })}</Typography.Text>}
      </Space>
      {challenge && <>
        <Alert className="mt-3" type="info" showIcon title={t("loginCodeSent")} />
        <Form.Item className="mt-4" label={t(channel === "SMS" ? "phoneCode" : "emailCode")}>
          <Input value={code} disabled={action.pending} onChange={event => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))} inputMode="numeric" autoComplete="one-time-code" maxLength={6} />
        </Form.Item>
        <AsyncButton
          type="primary"
          block
          pending={action.pending}
          disabled={!/^\d{6}$/.test(code) || (captchaAlwaysActions.includes("LOGIN_CODE_VERIFY") && !captchaSupported)}
          label={t("loginCodeVerify")}
          onClick={() => void complete()}
        />
      </>}
    </Form>
    <Space orientation="vertical" style={{ width: "100%" }} className="mt-3">
      <Button block disabled={action.pending} onClick={onCancel}>{t("loginCodeBack")}</Button>
    </Space>
  </>;
}
