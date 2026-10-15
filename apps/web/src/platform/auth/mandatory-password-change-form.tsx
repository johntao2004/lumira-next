"use client";

import { Button, Space } from "antd";
import { useEffect, useLayoutEffect, useRef } from "react";
import { mutate } from "@/platform/api/client";
import { currentSessionRevision, subscribeSessionBoundary } from "@/platform/auth/session-lifecycle";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { NewPasswordFields } from "@/platform/components/new-password-fields";
import { AsyncButton } from "@/platform/components/async-button";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";
import type { LoginResponse, PasswordChangeReason } from "./api-types";
import { resolvePasswordChangeContinuation } from "./password-change-continuation.mjs";

type PasswordChangeValues = { newPassword: string; confirmation: string };

export function MandatoryPasswordChangeForm({ challengeId, cancelPending, onCancel, onChanged }: {
  challengeId: string;
  expiresAt: string | null;
  reason: PasswordChangeReason;
  cancelPending: boolean;
  onCancel: () => Promise<void>;
  onChanged: (challengeId: string, result: LoginResponse, submittedSessionRevision: number) => void;
}) {
  const { t } = usePreferences();
  const [form] = Form.useForm<PasswordChangeValues>();
  const challengeIdRef = useRef<string | null>(challengeId);
  useLayoutEffect(() => {
    challengeIdRef.current = challengeId;
    return () => {
      if (challengeIdRef.current === challengeId) challengeIdRef.current = null;
    };
  }, [challengeId]);
  const action = useSubmitAction({
    errorKeys: ["secondFactorExpired", "passwordPolicy", "passwordMismatch", "passwordMustDiffer", "passwordChangeLimited", "forbidden", "unavailable", "loginContinuationUnavailable"],
    errorAliases: {
      passwordConfirmationMismatch: "passwordMismatch",
      newPasswordMustDiffer: "passwordMustDiffer",
    },
  });
  useEffect(() => {
    form.resetFields();
    const unsubscribe = subscribeSessionBoundary(() => {
      challengeIdRef.current = null;
      form.resetFields();
    });
    return () => {
      unsubscribe();
      form.resetFields();
    };
  }, [challengeId, form]);

  async function submit(values: PasswordChangeValues) {
    if (cancelPending) return;
    const revision = currentSessionRevision();
    await action.run(async () => {
      const response = await mutate<unknown>("/auth/login/password-change", "POST", {
        challengeId,
        newPassword: values.newPassword,
        confirmPassword: values.confirmation,
      });
      const continuation = resolvePasswordChangeContinuation({
        response,
        challengeId,
        activeChallengeId: challengeIdRef.current,
        submittedSessionRevision: revision,
        currentSessionRevision: currentSessionRevision(),
      });
      if (continuation.status === "stale") return false;
      form.resetFields();
      if (continuation.status === "invalid") {
        action.setError("loginContinuationUnavailable");
        return false;
      }
      onChanged(challengeId, continuation.response as LoginResponse, revision);
    });
  }

  async function cancel() {
    if (action.pending || cancelPending) return;
    form.resetFields();
    await onCancel();
  }

  return <Space orientation="vertical" style={{ width: "100%" }}>
    <FeedbackBubble id="mandatory-password-change-error" content={action.errorMessage} />
    <Form form={form} layout="vertical" requiredMark={false} size="large" disabled={action.pending || cancelPending} onFinish={submit}>
      <NewPasswordFields passwordName="newPassword" confirmationName="confirmation" />
      <div className="login-actions">
        <AsyncButton type="primary" htmlType="submit" block pending={action.pending} disabled={cancelPending} label={t("mandatoryPasswordChangeSubmit")} />
        <Button block loading={cancelPending} disabled={action.pending || cancelPending} onClick={() => { void cancel(); }}>{t("mandatoryPasswordChangeCancel")}</Button>
      </div>
    </Form>
  </Space>;
}
