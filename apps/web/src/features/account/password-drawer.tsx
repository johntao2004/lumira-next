"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {NewPasswordFields} from "@/platform/components/new-password-fields";
import {App, Input} from "antd";
import { useState } from "react";
import { history } from "@umijs/max";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError, mutate } from "@/platform/api/client";
import { FormDrawer } from "@/platform/components/form-drawer";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";

export function PasswordDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<MessageKey | null>(null);
  return <FormDrawer title={t("changePassword")} open={open} pending={pending} formId="account-password" onClose={() => { setError(null); onClose(); }}>
    <FeedbackBubble id="account-password-error" content={error ? t(error) : undefined} />
    <Form id="account-password" layout="vertical" onFinish={async (values) => {
      setPending(true); setError(null);
      try {
        await mutate<void>("/account/password", "POST", { currentPassword: values.currentPassword, newPassword: values.newPassword });
        onClose(); client.clear(); void message.success(t("passwordChanged")); history.replace("/user/login");
      } catch (e) {
        const key = e instanceof ApiError ? e.message : "unavailable";
        setError(key === "wrongCurrentPassword" || key === "passwordMustDiffer" || key === "passwordPolicy" ? key : "unavailable");
      } finally { setPending(false); }
    }}>
      <Form.Item name="currentPassword" label={t("currentPassword")} rules={[{ required: true, message: t("passwordRequired") }]}><Input.Password autoComplete="current-password" /></Form.Item>
      <NewPasswordFields/>
    </Form>
  </FormDrawer>;
}
