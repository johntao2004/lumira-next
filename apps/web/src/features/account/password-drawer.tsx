"use client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {NewPasswordFields} from "@/platform/components/new-password-fields";
import {App, Button, Input} from "antd";
import { history } from "@umijs/max";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError, mutate } from "@/platform/api/client";
import { FormDrawer } from "@/platform/components/form-drawer";
import { usePreferences } from "@/platform/providers";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import "./account-form-surface.css";

export function PasswordDrawer({ open, onClose, embedded = false }: { open: boolean; onClose: () => void; embedded?: boolean }) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const {pending,errorMessage,setError,run}=useSubmitAction({mapError: e => {
    const key=e instanceof ApiError?e.message:"unavailable";
    return key==="wrongCurrentPassword"||key==="passwordMustDiffer"||key==="passwordPolicy"||key==="passwordHashCapacity"?key:key==="passwordChangeLimited"?"emailVerificationLimited":"unavailable";
  }});
  const content = <>
    <FeedbackBubble id="account-password-error" content={errorMessage} />
    <Form id="account-password" layout="vertical" disabled={pending} onFinish={values => run(async () => {
        await mutate<void>("/account/password", "POST", { currentPassword: values.currentPassword, newPassword: values.newPassword });
        onClose(); client.clear(); void message.success(t("passwordChanged")); history.replace("/user/login");
    })}>
      <Form.Item name="currentPassword" label={t("currentPassword")} rules={[{ required: true, message: t("passwordRequired") }]}><Input.Password autoComplete="current-password" /></Form.Item>
      <NewPasswordFields/>
      {embedded && <div className="account-settings-form-footer"><Button type="primary" htmlType="submit" loading={pending}>{t("save")}</Button></div>}
    </Form>
  </>;
  return embedded ? <div className="account-settings-form-surface">{content}</div> : <FormDrawer title={t("changePassword")} open={open} pending={pending} formId="account-password" onClose={() => { setError(null); onClose(); }}>{content}</FormDrawer>;
}
