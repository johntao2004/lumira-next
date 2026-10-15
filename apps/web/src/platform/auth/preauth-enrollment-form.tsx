"use client";

import { useEffect, useState } from "react";
import { Alert, Button, Checkbox, Input, Modal, QRCode, Space, Typography } from "antd";
import { mutate } from "@/platform/api/client";
import type { EnrollmentConfirmationResponse, LoginResponse } from "@/platform/auth/api-types";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";

type EnrollmentSetup = {
  challengeId: string;
  secret: string;
  otpauthUri: string;
  expiresAt: string;
};

export function PreauthEnrollmentForm({ onComplete, onCancel }: { onComplete: (login: LoginResponse) => void; onCancel: () => void }) {
  const { t } = usePreferences();
  const [setup, setSetup] = useState<EnrollmentSetup | null>(null);
  const [code, setCode] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [loginResult, setLoginResult] = useState<LoginResponse | null>(null);
  const [saved, setSaved] = useState(false);
  const action = useSubmitAction({ errorKeys: ["secondFactorInvalid", "secondFactorExpired", "secondFactorUnavailable", "unavailable"] });

  useEffect(() => {
    if (!setup) return;
    const expiresAt = Date.parse(setup.expiresAt);
    if (!Number.isFinite(expiresAt)) return;
    const timer = window.setTimeout(() => {
      setSetup(null);
      setCode("");
      action.setError("secondFactorExpired");
    }, Math.max(0, expiresAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [setup, action.setError]);

  async function start() {
    await action.run(async () => {
      const result = await mutate<EnrollmentSetup>("/auth/second-factor/enrollment/start", "POST", {});
      setSetup(result);
      setCode("");
    });
  }

  async function confirm() {
    if (!setup || !code.trim()) return;
    await action.run(async () => {
      const result = await mutate<EnrollmentConfirmationResponse>("/auth/second-factor/enrollment/confirm", "POST", {
        challengeId: setup.challengeId,
        code: code.trim(),
      });
      if (!result.login || result.recoveryCodes.length === 0) {
        action.setError("unavailable");
        return false;
      }
      setLoginResult(result.login);
      setRecoveryCodes(result.recoveryCodes);
      setSaved(false);
      setSetup(null);
      setCode("");
    });
  }

  async function cancel() {
    await action.run(async () => {
      setSetup(null);
      onCancel();
    });
  }

  function downloadCodes() {
    if (!recoveryCodes?.length) return;
    const url = URL.createObjectURL(new Blob([`${recoveryCodes.join("\n")}\n`], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "lumira-recovery-codes.txt";
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
  }

  return <>
    <Typography.Paragraph>{t("preauthEnrollmentHint")}</Typography.Paragraph>
    <FeedbackBubble id="preauth-enrollment-error" content={action.error ? t(action.error) : undefined} />
    {setup ? <>
      <Typography.Paragraph>{t("mfaSetupHint")}</Typography.Paragraph>
      <QRCode value={setup.otpauthUri} />
      <Typography.Paragraph copyable>{setup.secret}</Typography.Paragraph>
      <Form layout="vertical" requiredMark={false} size="large" onFinish={() => void confirm()}>
        <Form.Item label={t("mfaCode")} required>
          <Input value={code} onChange={event => setCode(event.target.value)} autoComplete="one-time-code" maxLength={64} autoFocus />
        </Form.Item>
        <Space orientation="vertical" style={{ width: "100%" }}>
          <AsyncButton type="primary" htmlType="submit" block pending={action.pending} disabled={!code.trim()} label={t("mfaVerify")} />
          <Button block disabled={action.pending} onClick={() => void cancel()}>{t("mfaBack")}</Button>
        </Space>
      </Form>
    </> : <Space orientation="vertical" style={{ width: "100%" }}>
      <AsyncButton type="primary" block pending={action.pending} label={t("preauthEnrollmentStart")} onClick={() => void start()} />
      <Button block disabled={action.pending} onClick={() => void cancel()}>{t("mfaBack")}</Button>
    </Space>}
    {recoveryCodes && loginResult && <Modal
      open
      title={t("preauthRecoveryTitle")}
      closable={false}
      maskClosable={false}
      keyboard={false}
      footer={<Button type="primary" disabled={!saved} onClick={() => onComplete(loginResult)}>{t("preauthRecoveryContinue")}</Button>}
    >
      <Alert type="warning" showIcon title={t("preauthRecoveryHint")} />
      <Typography.Paragraph copyable={{ text: recoveryCodes.join("\n") }} className="mt-4">
        <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere", fontSize: 12 }}>{recoveryCodes.join("\n")}</pre>
      </Typography.Paragraph>
      <Space orientation="vertical">
        <Button onClick={downloadCodes}>{t("mfaDownload")}</Button>
        <Checkbox checked={saved} onChange={event => setSaved(event.target.checked)}>{t("mfaRecoverySaved")}</Checkbox>
      </Space>
    </Modal>}
  </>;
}
