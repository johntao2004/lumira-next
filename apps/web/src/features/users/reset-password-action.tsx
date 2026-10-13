"use client";

import { CopyOutlined } from "@ant-design/icons";
import { Alert, Button, Modal, Space, Typography, theme } from "antd";
import { useEffect, useRef, useState } from "react";
import "./reset-password-action.css";
import { ApiError, mutate } from "@/platform/api/client";
import { currentSessionRevision, subscribeSessionBoundary } from "@/platform/auth/session-lifecycle";
import type { UserSummary } from "@/platform/directory/api-types";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { usePreferences } from "@/platform/providers";

type PasswordResetDisclosure = { temporaryPassword: string; expiresAt: string };
type PasswordResetResponse = PasswordResetDisclosure;

export function ResetPasswordAction({ user, onReset, onStale }: {
  user: Pick<UserSummary, "id" | "username" | "version">;
  onReset?: () => void | Promise<void>;
  onStale?: () => void | Promise<void>;
}) {
  const { t, locale } = usePreferences();
  const { token } = theme.useToken();
  const [open, setOpen] = useState(false);
  const [disclosure, setDisclosure] = useState<PasswordResetDisclosure | null>(null);
  const disclosureRef = useRef<PasswordResetDisclosure | null>(null);
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const action = useSubmitAction({
    errorKeys: ["forbidden", "staleRecord", "unavailable", "passwordResetProtected"],
    errorAliases: {
      protectedAccount: "passwordResetProtected",
      invalidPasswordReset: "unavailable",
      userNotFound: "unavailable",
    },
    onError: key => {
      if (key === "staleRecord") void Promise.resolve(onStale?.()).catch(() => {});
    },
  });

  useEffect(() => subscribeSessionBoundary(() => {
    disclosureRef.current = null;
    setOpen(false);
    setDisclosure(null);
    setCopied(false);
    setCopyFailed(false);
  }), []);

  function clearDisclosure() {
    disclosureRef.current = null;
    setOpen(false);
    setDisclosure(null);
    setCopied(false);
    setCopyFailed(false);
    action.setError(null);
  }

  async function generate() {
    const revision = currentSessionRevision();
    await action.run(async () => {
      const result = await mutate<PasswordResetResponse>(`/platform/users/${user.id}/password-reset`, "POST", { version: user.version });
      if (revision !== currentSessionRevision()) return false;
      if (typeof result?.temporaryPassword !== "string" || result.temporaryPassword.length === 0
        || typeof result.expiresAt !== "string" || !Number.isFinite(Date.parse(result.expiresAt))) {
        throw new ApiError(503, "unavailable");
      }
      const nextDisclosure = { temporaryPassword: result.temporaryPassword, expiresAt: result.expiresAt };
      disclosureRef.current = nextDisclosure;
      setDisclosure(nextDisclosure);
      setCopied(false);
      setCopyFailed(false);
      void Promise.resolve(onReset?.()).catch(() => {});
    });
  }

  async function copyTemporaryPassword() {
    const currentDisclosure = disclosure;
    if (!currentDisclosure) return;
    const value = currentDisclosure.temporaryPassword;
    const revision = currentSessionRevision();
    setCopyFailed(false);
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
      await navigator.clipboard.writeText(value);
      if (revision !== currentSessionRevision() || disclosureRef.current !== currentDisclosure) return;
      setCopied(true);
    } catch {
      if (revision === currentSessionRevision() && disclosureRef.current === currentDisclosure) setCopyFailed(true);
    }
  }

  const expiresAt = disclosure ? new Date(disclosure.expiresAt).toLocaleString(locale) : "";

  return <>
    <Button type="link" disabled={action.pending} onClick={() => { action.setError(null); setOpen(true); }}>{t("resetPassword")}</Button>
    <Modal
      open={open}
      destroyOnHidden
      title={t(disclosure ? "temporaryPasswordTitle" : "resetPassword")}
      onCancel={() => { if (!action.pending) clearDisclosure(); }}
      closable={!action.pending}
      maskClosable={!action.pending}
      keyboard={!action.pending}
      footer={disclosure
        ? <div className="temporary-password-disclosure__actions" style={{ gap: token.marginSM }}>
          <Button
            className="temporary-password-disclosure__copy"
            type="primary"
            icon={<CopyOutlined />}
            onClick={() => void copyTemporaryPassword()}
          >{t(copied ? "temporaryPasswordCopied" : "temporaryPasswordCopy")}</Button>
          <Button className="temporary-password-disclosure__close" onClick={clearDisclosure}>{t("close")}</Button>
        </div>
        : <Space>
          <Button disabled={action.pending} onClick={clearDisclosure}>{t("cancel")}</Button>
          <AsyncButton type="primary" pending={action.pending} label={t("generateTemporaryPassword")} onClick={() => void generate()} />
        </Space>}
    >
      {disclosure ? <section className="temporary-password-disclosure" style={{ gap: token.marginLG }} aria-label={t("temporaryPasswordTitle")}>
        <div className="temporary-password-disclosure__notice" style={{ gap: token.marginSM }}>
          <Alert type="warning" showIcon title={t("temporaryPasswordShownOnce")} />
        </div>
        <div
          className="temporary-password-disclosure__value"
          style={{
            backgroundColor: token.colorFillQuaternary,
            borderColor: token.colorBorderSecondary,
            borderRadius: token.borderRadiusLG,
            color: token.colorText,
            fontFamily: token.fontFamilyCode,
            padding: token.paddingLG,
          }}
          aria-label={t("temporaryPasswordTitle")}
          tabIndex={0}
        >
          {disclosure.temporaryPassword}
        </div>
        <Typography.Text className="temporary-password-disclosure__expiry" type="secondary">
          {t("temporaryPasswordExpiry", { expiresAt })}
        </Typography.Text>
        <FeedbackBubble kind="success" id={`reset-password-copied-${user.id}`} content={copied ? t("temporaryPasswordCopied") : undefined} />
        <FeedbackBubble id={`reset-password-copy-error-${user.id}`} content={copyFailed ? t("temporaryPasswordCopyFailed") : undefined} />
      </section> : <>
        <Alert type="warning" showIcon title={t("resetPasswordConfirm", { username: user.username })} />
        <FeedbackBubble id={`reset-password-error-${user.id}`} content={action.errorMessage} />
      </>}
    </Modal>
  </>;
}
