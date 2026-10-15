"use client";

import { Button, Input, Space, Typography } from "antd";
import { useState } from "react";
import { mutate } from "@/platform/api/client";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";
import type { LoginResponse } from "./api-types";

export function SecondFactorForm({
  challenge,
  onComplete,
  onCancel,
}: {
  challenge: LoginResponse;
  onComplete: (result: LoginResponse) => void;
  onCancel: () => void;
}) {
  const { t } = usePreferences();
  const [code, setCode] = useState("");
  const action = useSubmitAction({
    errorKeys: ["secondFactorInvalid", "secondFactorExpired", "secondFactorUnavailable", "loginRateLimited", "maintenanceActive"],
  });

  async function verify() {
    await action.run(async () => {
      if (!challenge.challengeId) {
        action.setError("secondFactorExpired");
        return false;
      }
      const result = await mutate<LoginResponse>("/auth/second-factor/confirm", "POST", {
        challengeId: challenge.challengeId,
        code: code.trim(),
      });
      if (result.state === "MFA_REQUIRED" || result.secondFactorRequired) {
        action.setError("secondFactorInvalid");
        return false;
      }
      onComplete(result);
    });
  }

  async function cancel() {
    await action.run(async () => {
      await mutate<void>("/auth/second-factor/cancel", "POST", {});
      onCancel();
    });
  }

  return (
    <>
      <Typography.Paragraph>{t("mfaLoginHint")}</Typography.Paragraph>
      <FeedbackBubble id="second-factor-error" content={action.error ? t(action.error) : undefined} />
      <Form layout="vertical" requiredMark={false} size="large" onFinish={verify}>
        <Form.Item label={t("mfaCode")} required>
          <Input
            aria-label={t("mfaCode")}
            value={code}
            onChange={event => setCode(event.target.value)}
            autoFocus
            autoComplete="one-time-code"
            maxLength={64}
          />
        </Form.Item>
        <Typography.Paragraph type="secondary">{t("mfaCodeHint")}</Typography.Paragraph>
        <Space orientation="vertical" style={{ width: "100%" }}>
          <AsyncButton
            type="primary"
            htmlType="submit"
            block
            pending={action.pending}
            disabled={!code.trim()}
            label={t("mfaVerify")}
          />
          <Button block disabled={action.pending} onClick={() => void cancel()}>
            {t("mfaBack")}
          </Button>
        </Space>
      </Form>
    </>
  );
}
