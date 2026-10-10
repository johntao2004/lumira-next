"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import { PlatformDrawer } from "@/platform/components/platform-drawer";
import {Input, Typography} from "antd";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, mutate } from "@/platform/api/client";
import { AsyncButton } from "@/platform/components/async-button";
import { DrawerFormActions } from "@/platform/components/drawer-actions";
import { QueryFailure } from "@/platform/components/query-failure";
import { usePreferences } from "@/platform/providers";
import { formatDateTime } from "@/platform/i18n/format";
import type { MessageKey } from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { MAX_PASSWORD_BYTES } from "@/platform/auth/password-contract";
import { useContactVerificationAction } from "./use-contact-verification-action";

type PhoneState = { phone: string | null; verifiedAt: string | null; version: number; pendingPhone: string | null; expiresAt: string | null; requestedAt: string | null };
const queryKey = ["account-phone"];
const errorKeys: Record<string, MessageKey> = {
  invalidPhone: "invalidPhone", wrongCurrentPassword: "wrongCurrentPassword", staleRecord: "staleRecord", phoneAlreadyVerified: "phoneAlreadyVerified",
  phoneVerificationLimited: "phoneVerificationLimited", invalidVerificationToken: "invalidVerificationToken", phoneUnavailable: "phoneUnavailable",
  smsUnavailable: "smsUnavailable", smsCredentialRequired: "smsUnavailable", secretKeyUnavailable: "smsUnavailable", forbidden: "forbidden",
  smsProviderRejected: "smsProviderRejected",
};

export function PhoneDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const [requestForm] = Form.useForm(); const [confirmForm] = Form.useForm();
  const [notice, setNotice] = useState<MessageKey | null>(null);
  const contact = useContactVerificationAction({ queryKey, requestForm, errorKeys, onError: () => setNotice(null) });
  const { action, pendingStep } = contact;
  const pending = action.pending;
  const query = useQuery({ queryKey, queryFn: ({ signal }) => api<PhoneState>("/account/phone", { signal }), enabled: open, staleTime: 0, refetchOnWindowFocus: true });
  const ready = !!query.data && !query.error && !query.isFetching;
  async function submit(kind: "request" | "confirm", values: Record<string, string>) {
    const account = query.data;
    if (action.isPending() || !ready || !account) return;
    setNotice(null);
    await contact.run(kind, async () => {
      if (kind === "request") {
        const next = await mutate<PhoneState>("/account/phone/request", "POST", { ...values, version: account.version, locale });
        client.setQueryData(queryKey, next); confirmForm.resetFields(); setNotice("phoneQueued");
      } else {
        await mutate("/account/phone/confirm", "POST", values); confirmForm.resetFields(); setNotice("phoneVerified");
      }
    });
  }
  function close() { if (action.isPending()) return; requestForm.resetFields(); confirmForm.resetFields(); action.setError(null); setNotice(null); onClose(); }
  return <PlatformDrawer title={t("accountPhone")} open={open} closable={{ disabled: !!pending }} onClose={close} destroyOnHidden size={440} footer={<DrawerFormActions formId="account-phone-confirm" onClose={close} pending={!!pending} submitDisabled={!ready || !query.data?.pendingPhone} submitLabel={t("confirmPhone")} />}>
    <Typography.Paragraph>{t("phoneIndependentHint")}</Typography.Paragraph>
    {query.error && <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />}
    {query.data && !query.error && <>
      <Typography.Paragraph style={{ overflowWrap: "anywhere" }}>{t("verifiedPhone")}: {query.data.phone ?? t("phoneNotVerified")}</Typography.Paragraph>
      {query.data.pendingPhone && <Typography.Paragraph style={{ overflowWrap: "anywhere" }}>{t("pendingPhone")}: {query.data.pendingPhone}<br />{t("mailExpires")}: {formatDateTime(query.data.expiresAt, locale, { dateStyle: "medium", timeStyle: "short" })}</Typography.Paragraph>}
    </>}
    <FeedbackBubble kind="success" id="account-phone-success" content={notice ? t(notice) : undefined} />
    <FeedbackBubble id="account-phone-error" content={action.error ? t(action.error) : undefined} />
    <Form name="account-phone-request" form={requestForm} layout="vertical" disabled={pending || !ready} onFinish={values => submit("request", values)}>
      <Form.Item name="phone" label={t("accountPhone")} rules={[{ required: true, pattern: /^\+?[1-9][0-9 ()-]{6,20}$/, message: t("invalidPhone") }]}><Input type="tel" autoComplete="tel" maxLength={24} placeholder="+8613800138000" /></Form.Item>
      <Form.Item name="currentPassword" label={t("currentPassword")} rules={[{ required: true, message: t("passwordRequired") }]}><Input.Password autoComplete="current-password" maxLength={MAX_PASSWORD_BYTES} /></Form.Item>
      <AsyncButton htmlType="submit" label={t("requestPhoneCode")} pending={pendingStep === "request"} disabled={pending || !ready} />
    </Form>
    <Form id="account-phone-confirm" name="account-phone-confirm" className="mt-6" form={confirmForm} layout="vertical" disabled={pending || !ready || !query.data?.pendingPhone} onFinish={values => submit("confirm", values)}>
      <Form.Item name="token" label={t("phoneCode")} rules={[{ required: true, pattern: /^(?:[0-9]{6}|[A-Za-z0-9_-]{43})$/, message: t("invalidVerificationToken") }]}><Input autoComplete="one-time-code" maxLength={43} spellCheck={false} /></Form.Item>
    </Form>
  </PlatformDrawer>;
}
