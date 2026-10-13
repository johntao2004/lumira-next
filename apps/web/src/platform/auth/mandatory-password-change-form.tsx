"use client";

import { Alert, Button, Space } from "antd";
import { useEffect, useLayoutEffect, useRef } from "react";
import { mutate } from "@/platform/api/client";
import { currentSessionRevision, subscribeSessionBoundary } from "@/platform/auth/session-lifecycle";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { NewPasswordFields } from "@/platform/components/new-password-fields";
import { AsyncButton } from "@/platform/components/async-button";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";

type PasswordChangeValues = { newPassword: string; confirmation: string };

export function MandatoryPasswordChangeForm({ challengeId, expiresAt, onCancel, onChanged }: {
  challengeId: string;
  expiresAt: string | null;
  onCancel: () => void;
  onChanged: (challengeId: string) => void;
}) {
  const { t, locale } = usePreferences();
  const [form] = Form.useForm<PasswordChangeValues>();
  const challengeIdRef = useRef<string | null>(challengeId);
  useLayoutEffect(() => {
    challengeIdRef.current = challengeId;
    return () => {
      if (challengeIdRef.current === challengeId) challengeIdRef.current = null;
    };
  }, [challengeId]);
  const action = useSubmitAction({
    errorKeys: ["secondFactorExpired", "passwordPolicy", "passwordMismatch", "passwordMustDiffer", "passwordChangeLimited", "forbidden", "unavailable"],
    errorAliases: {
      passwordConfirmationMismatch: "passwordMismatch",
      newPasswordMustDiffer: "passwordMustDiffer",
    },
  });
  const parsedExpiry = expiresAt ? Date.parse(expiresAt) : Number.NaN;

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
    const revision = currentSessionRevision();
    await action.run(async () => {
      await mutate<void>("/auth/login/password-change", "POST", {
        challengeId,
        newPassword: values.newPassword,
        confirmPassword: values.confirmation,
      });
      if (revision !== currentSessionRevision() || challengeIdRef.current !== challengeId) return false;
      form.resetFields();
      onChanged(challengeId);
    });
  }

  function cancel() {
    challengeIdRef.current = null;
    form.resetFields();
    onCancel();
  }

  const expiresLabel = Number.isFinite(parsedExpiry)
    ? t("mandatoryPasswordChangeExpires", { expiresAt: new Date(parsedExpiry).toLocaleString(locale) })
    : undefined;

  return <Space orientation="vertical" style={{ width: "100%" }}>
    <Alert type="warning" showIcon title={t("mandatoryPasswordChangeHint")} description={expiresLabel} />
    <FeedbackBubble id="mandatory-password-change-error" content={action.errorMessage} />
    <Form form={form} layout="vertical" requiredMark={false} disabled={action.pending} onFinish={submit}>
      <NewPasswordFields passwordName="newPassword" confirmationName="confirmation" />
      <Space orientation="vertical" style={{ width: "100%" }}>
        <AsyncButton type="primary" htmlType="submit" block pending={action.pending} label={t("mandatoryPasswordChangeSubmit")} />
        <Button block disabled={action.pending} onClick={cancel}>{t("mfaBack")}</Button>
      </Space>
    </Form>
  </Space>;
}
