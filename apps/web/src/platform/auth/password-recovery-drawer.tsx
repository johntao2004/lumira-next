"use client";

import { useRef, useState } from "react";
import { Input } from "antd";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api, mutate } from "../api/client";
import { AsyncButton } from "../components/async-button";
import { DrawerFormActions } from "../components/drawer-actions";
import { FeedbackBubble } from "../components/feedback-bubble";
import { PlatformDrawer } from "../components/platform-drawer";
import { PlatformForm as Form } from "../components/platform-form";
import { NewPasswordFields } from "../components/new-password-fields";
import { CaptchaPanel } from "./captcha-panel";
import type { LoginMethodAvailability } from "./api-types";
import { usePreferences } from "../providers";
import { useCaptchaProof } from "./use-captcha-proof";
import type { MessageKey } from "../i18n/messages";

const errors: Record<string, MessageKey> = {
  invalidRecoveryRequest: "usernameRequired",
  invalidRecoveryToken: "invalidRecoveryToken",
  passwordRecoveryLimited: "passwordRecoveryLimited",
  passwordHashCapacity: "passwordHashCapacity",
  passwordPolicy: "passwordPolicy",
  passwordMustDiffer: "passwordMustDiffer",
  smtpUnavailable: "emailSmtpUnavailable",
  smtpPasswordRequired: "emailSmtpUnavailable",
  secretKeyUnavailable: "emailSmtpUnavailable",
  captchaUnavailable: "captchaUnavailable",
  captchaRequiredUnavailable: "captchaRequiredUnavailable",
  captchaRequired: "captchaRequired",
};

export function PasswordRecoveryDrawer({ open, onClose, onRecovered }: {
  open: boolean;
  onClose: () => void;
  onRecovered: (username: string) => void;
}) {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm();
  const authMethods = useQuery({
    queryKey: ["auth-methods"],
    queryFn: ({ signal }) => api<LoginMethodAvailability>("/auth/methods", { signal }),
    retry: false,
  });
  const busy = useRef(false);
  const [pending, setPending] = useState<"request" | "confirm" | null>(null);
  const [error, setError] = useState<MessageKey | null>(null);
  const [retryAfterSeconds, setRetryAfterSeconds] = useState<number | null>(null);
  const [requested, setRequested] = useState(false);
  const captchaProof = useCaptchaProof({
    alwaysActions: authMethods.data?.captcha.alwaysActions,
    supported: authMethods.data?.capabilities.captcha === true,
    unavailableMessage: "captchaRequiredUnavailable",
  });

  function close() {
    if (busy.current) return;
    captchaProof.cancel();
    form.resetFields();
    setError(null);
    setRetryAfterSeconds(null);
    setRequested(false);
    onClose();
  }

  async function submit(kind: "request" | "confirm", values: Record<string, string>) {
    if (busy.current) return;
    busy.current = true;
    setPending(kind);
    setError(null);
    setRetryAfterSeconds(null);
    try {
      const action = kind === "request" ? "PASSWORD_RECOVERY_REQUEST" as const : "PASSWORD_RECOVERY_CONFIRM" as const;
      const outcome = await captchaProof.run(action, async captchaProofId => {
        if (kind === "request") {
          await mutate("/auth/password-recovery/request", "POST", { username: values.username, locale, ...(captchaProofId ? { captchaProofId } : {}) });
          return;
        }
        await mutate("/auth/password-recovery/confirm", "POST", {
          username: values.username,
          token: values.token,
          newPassword: values.newPassword,
          ...(captchaProofId ? { captchaProofId } : {}),
        });
      });
      if (outcome.status === "completed" && captchaProof.isMounted()) {
        if (kind === "request") {
          setRequested(true);
          form.setFieldValue("token", undefined);
        } else {
          client.clear();
          form.resetFields();
          setRequested(false);
          onRecovered(values.username);
        }
      }
    } catch (cause) {
      if (!captchaProof.isMounted()) return;
      const apiError = cause instanceof ApiError ? cause : null;
      setError(apiError?.status === 403
        ? "forbidden"
        : errors[apiError?.message ?? ""] ?? "unavailable");
      setRetryAfterSeconds(apiError?.message === "passwordHashCapacity" ? apiError.retryAfterSeconds ?? 1 : null);
    } finally {
      if (captchaProof.isMounted()) {
        if (kind === "confirm") form.setFieldsValue({ newPassword: undefined, confirmation: undefined });
        busy.current = false;
        setPending(null);
      }
    }
  }

  return <PlatformDrawer
    title={t("forgotPassword")}
    open={open}
    size={440}
    destroyOnHidden
    closable={{ disabled: !!pending }}
    onClose={close}
    footer={<DrawerFormActions formId="password-recovery" onClose={close} pending={!!pending} submitLabel={t("resetPassword")} />}
  >
    {captchaProof.action && <CaptchaPanel action={captchaProof.action} onVerified={proof => { captchaProof.finish(proof); }} onCancel={() => { captchaProof.finish(null); }} />}
    <FeedbackBubble kind="success" id="password-recovery-requested" content={requested ? t("recoveryRequested") : undefined} />
    <FeedbackBubble id="password-recovery-error" content={error ? t(error, error === "passwordHashCapacity" ? { seconds: retryAfterSeconds ?? 1 } : undefined) : undefined} />
    <Form
      id="password-recovery"
      name="password-recovery"
      form={form}
      layout="vertical"
      disabled={!!pending}
      onValuesChange={values => {
        if (Object.hasOwn(values, "username")) {
          setRequested(false);
          form.setFieldValue("token", undefined);
          setError(null);
          setRetryAfterSeconds(null);
        }
      }}
      onFinish={values => submit("confirm", values)}
    >
      <Form.Item name="username" label={t("username")} extra={t("recoveryHint")} rules={[{ required: true, whitespace: true, message: t("usernameRequired") }, { max: 100, message: t("nameTooLong") }]}>
        <Input autoComplete="username" maxLength={100} />
      </Form.Item>
      <AsyncButton
        className="mb-4"
        label={t("requestRecoveryCode")}
        pending={pending === "request"}
        disabled={!!pending}
        onClick={() => { void form.validateFields(["username"]).then(values => submit("request", values)).catch(() => {}); }}
      />
      <Form.Item name="token" label={t("emailCode")} rules={[{ required: true, pattern: /^[A-Za-z0-9_-]{43}$/, message: t("invalidRecoveryToken") }]}>
        <Input autoComplete="one-time-code" maxLength={43} spellCheck={false} />
      </Form.Item>
      <NewPasswordFields />
    </Form>
  </PlatformDrawer>;
}
