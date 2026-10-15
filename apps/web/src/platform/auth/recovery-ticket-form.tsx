"use client";

import { Alert, Button, Input, Space, Typography } from "antd";
import { useState } from "react";
import { ApiError, mutate } from "@/platform/api/client";
import type { LoginResponse } from "@/platform/auth/api-types";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";

type RecoveryDelivery = { expiresAt: string };

const errorKeys = [
  "mfaRecoveryDeliveryUnavailable",
  "mfaRecoveryNotPending",
  "mfaRecoveryLimited",
  "secondFactorExpired",
  "secondFactorUnavailable",
  "unavailable",
] as const;

export function RecoveryTicketForm({
  challengeId,
  onComplete,
  onCancel,
}: {
  challengeId: string;
  onComplete: (result: LoginResponse) => void;
  onCancel: () => void;
}) {
  const { t } = usePreferences();
  const [ticket, setTicket] = useState("");
  const [delivery, setDelivery] = useState<RecoveryDelivery | null>(null);
  const requestAction = useSubmitAction({ errorKeys });
  const verifyAction = useSubmitAction({ errorKeys });

  async function requestTicket() {
    await requestAction.run(async () => {
      const result = await mutate<RecoveryDelivery>("/auth/second-factor/recovery/request", "POST", { challengeId });
      if (!result?.expiresAt || !Number.isFinite(Date.parse(result.expiresAt))) {
        throw new ApiError(503, "unavailable");
      }
      setDelivery(result);
      setTicket("");
    });
  }

  async function verifyTicket() {
    const recoveryTicket = ticket.trim();
    if (!recoveryTicket) return;
    await verifyAction.run(async () => {
      const result = await mutate<LoginResponse>("/auth/second-factor/recovery/verify", "POST", {
        challengeId,
        recoveryTicket,
      });
      if (result?.state !== "ENROLLMENT_REQUIRED" && result?.state !== "PASSWORD_CHANGE_REQUIRED") {
        throw new ApiError(409, "secondFactorExpired");
      }
      setTicket("");
      onComplete(result);
    });
  }

  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert type="warning" showIcon title={t("mfaRecoveryRequiredHint")} />
      <Typography.Paragraph>{t("mfaRecoveryTicketHint")}</Typography.Paragraph>
      <FeedbackBubble id="mfa-recovery-request-error" content={requestAction.error ? t(requestAction.error) : undefined} />
      <AsyncButton
        block
        pending={requestAction.pending}
        onClick={() => void requestTicket()}
        label={t(delivery ? "mfaRecoveryResend" : "mfaRecoveryRequest")}
      />
      {delivery && <Typography.Text type="secondary">{t("mfaRecoveryTicketExpires", { expiresAt: new Date(delivery.expiresAt).toLocaleString() })}</Typography.Text>}
      <FeedbackBubble id="mfa-recovery-verify-error" content={verifyAction.error ? t(verifyAction.error) : undefined} />
      <Input.Password
        size="large"
        value={ticket}
        onChange={event => setTicket(event.target.value)}
        autoComplete="one-time-code"
        maxLength={256}
        aria-label={t("mfaRecoveryTicket")}
        placeholder={t("mfaRecoveryTicket")}
        disabled={!delivery || verifyAction.pending}
      />
      <AsyncButton
        type="primary"
        block
        pending={verifyAction.pending}
        disabled={!delivery || !ticket.trim()}
        onClick={() => void verifyTicket()}
        label={t("mfaRecoveryVerify")}
      />
      <Button block disabled={requestAction.pending || verifyAction.pending} onClick={onCancel}>{t("mfaBack")}</Button>
    </Space>
  );
}
