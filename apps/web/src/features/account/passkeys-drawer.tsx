"use client";
import { errorCode } from "@/platform/api/errors";
import { PlatformForm as Form } from "@/platform/components/platform-form";

import { useState } from "react";
import {Alert, Button, Empty, Input, Listy, Modal, Space, Spin, Tag, Typography} from "antd";
import { DeleteOutlined, EditOutlined, KeyOutlined, PlusOutlined } from "@ant-design/icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, mutate } from "@/platform/api/client";
import type { PasskeyCredential, PasskeyOptions } from "@/platform/auth/api-types";
import { PlatformDrawer } from "@/platform/components/platform-drawer";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { QueryFailure } from "@/platform/components/query-failure";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { formatDateTime } from "@/platform/i18n/format";
import { MAX_PASSWORD_BYTES } from "@/platform/auth/password-contract";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import {
  createPasskeyCredential,
  isPasskeySupported,
  toPublicKeyCreationOptions,
  toRegistrationPayload,
} from "@/platform/auth/passkey";

type Action = "add" | "rename" | "delete";
type ActionValues = { label?: string; currentPassword?: string };

const errorKeys: Record<string, MessageKey> = {
  passkeyFeatureDisabled: "passkeyDisabled",
  passkeyRateLimited: "emailVerificationLimited",
  currentPasswordRequired: "passkeyPasswordRequired",
  wrongCurrentPassword: "wrongCurrentPassword",
  passkeyNotFound: "passkeyNotFound",
  passkeyAlreadyBound: "passkeyAlreadyBound",
  passkeyLabelRequired: "passkeyLabelRequired",
  invalidPasskeyLabel: "passkeyLabelInvalid",
  invalidPasskeyRegistration: "passkeyOperationFailed",
  passkeyChallengeExpired: "passkeyChallengeExpired",
  passkeyChallengeMismatch: "passkeyOperationFailed",
  passkeyOriginNotAllowed: "passkeyOperationFailed",
  passkeyRpIdMismatch: "passkeyOperationFailed",
  passkeyUserVerificationRequired: "passkeyOperationFailed",
};

function mapPasskeyError(error: unknown): MessageKey {
  if(errorCode(error)==="sensitiveContent")return "sensitiveContent";
  if (error instanceof Error && error.message === "passkeyUnsupported") return "passkeyUnsupported";
  if (error instanceof Error && error.message === "passkeyTimeout") return "passkeyTimeout";
  if (error instanceof DOMException && error.name === "NotAllowedError") return "passkeyCancelled";
  return errorKeys[error instanceof ApiError ? error.message : ""] ?? "passkeyOperationFailed";
}

function dateText(value: string | null, locale: string): string {
  return formatDateTime(value, locale, { dateStyle: "medium", timeStyle: "short" });
}

export function PasskeysDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm<ActionValues>();
  const [action, setAction] = useState<Action | null>(null);
  const [target, setTarget] = useState<PasskeyCredential | null>(null);
  const submitAction = useSubmitAction({ mapError: mapPasskeyError });
  const { pending, errorMessage, setError } = submitAction;
  const query = useQuery({
    queryKey: ["passkeys"],
    queryFn: ({ signal }) => api<PasskeyCredential[]>("/auth/passkeys", { signal }),
    enabled: open,
    staleTime: 0,
    refetchOnWindowFocus: true,
  });

  function openAction(next: Action, credential?: PasskeyCredential) {
    setError(null);
    setTarget(credential ?? null);
    form.resetFields();
    if (credential) form.setFieldValue("label", credential.label);
    setAction(next);
  }

  function closeAction() {
    if (submitAction.isPending()) return;
    setAction(null);
    setTarget(null);
    setError(null);
    form.resetFields();
  }

  async function submit(values: ActionValues) {
    if (!action || submitAction.isPending()) return;
    await submitAction.run(async () => {
      if (action === "add") {
        if (!isPasskeySupported()) throw new Error("passkeyUnsupported");
        const options = await mutate<PasskeyOptions>("/auth/passkeys/registration/options", "POST", { currentPassword: values.currentPassword });
        const credential = await createPasskeyCredential(toPublicKeyCreationOptions(options));
        await mutate("/auth/passkeys/registration/complete", "POST", toRegistrationPayload(credential, options.challengeId, values.label));
      } else if (action === "rename" && target) {
        await mutate(`/auth/passkeys/${encodeURIComponent(target.id)}`, "PATCH", { label: values.label, currentPassword: values.currentPassword });
      } else if (action === "delete" && target) {
        await mutate(`/auth/passkeys/${encodeURIComponent(target.id)}`, "DELETE", { currentPassword: values.currentPassword });
      }
      setAction(null);
      setTarget(null);
      form.resetFields();
      await client.invalidateQueries({ queryKey: ["passkeys"] });
    });
  }

  const actionTitle = action === "add" ? t("passkeyAdd") : action === "rename" ? t("passkeyRename") : t("passkeyDelete");
  return <PlatformDrawer title={t("passkeyCredentials")} open={open} closable={{ disabled: pending }} onClose={() => { if (!pending) { closeAction(); onClose(); } }} destroyOnHidden size={560}>
    {query.isPending && <div className="grid place-content-center p-8"><Spin /></div>}
    {query.error && <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />}
    {!query.isPending && !query.error && !query.data?.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("passkeyNoCredentials")} />}
    {!!query.data?.length && <Listy
      items={query.data}
      rowKey="id"
      virtual={false}
      itemRender={(credential) => <div className="flex items-center justify-between gap-3 py-2">
        <Space orientation="vertical" size={0} className="min-w-0">
          <Typography.Text strong ellipsis={{ tooltip: credential.label }}>{credential.label}</Typography.Text>
          <Typography.Text type="secondary">{t("passkeyCreated")}: {dateText(credential.createdAt, locale)}</Typography.Text>
          <Typography.Text type="secondary">{t("passkeyLastUsed")}: {dateText(credential.lastUsedAt, locale)}</Typography.Text>
        </Space>
        <Space>
          <Button aria-label={`${t("passkeyRename")} ${credential.label}`} icon={<EditOutlined />} onClick={() => openAction("rename", credential)} disabled={pending} />
          <Button danger aria-label={`${t("passkeyDelete")} ${credential.label}`} icon={<DeleteOutlined />} onClick={() => openAction("delete", credential)} disabled={pending} />
        </Space>
      </div>}
    />}
    <Button type="primary" icon={<PlusOutlined />} onClick={() => openAction("add")} disabled={pending || query.isPending} className="mt-4">{t("passkeyAdd")}</Button>
    <Modal
      open={action !== null}
      title={actionTitle}
      okText={action === "delete" ? t("passkeyDelete") : t("save")}
      cancelText={t("cancel")}
      confirmLoading={pending}
      okButtonProps={{ danger: action === "delete" }}
      destroyOnHidden
      onCancel={closeAction}
      onOk={() => form.submit()}
    >
      <FeedbackBubble id="passkey-operation-error" content={errorMessage} />
      {action === "delete" && <Alert type="warning" title={t("passkeyDeleteHint")} showIcon className="mb-4" />}
      <Form name="account-passkey-action" form={form} layout="vertical" onFinish={submit} disabled={pending}>
        {action !== "delete" && <Form.Item name="label" label={t("passkeyLabel")} rules={[{ required: true, whitespace: true, max: 128, message: t("passkeyLabelInvalid") }]}>
          <Input autoComplete="off" maxLength={128} />
        </Form.Item>}
        <Form.Item name="currentPassword" label={t("currentPassword")} rules={[{ required: true, message: t("passkeyPasswordRequired") }]}>
          <Input.Password autoComplete="current-password" maxLength={MAX_PASSWORD_BYTES} />
        </Form.Item>
      </Form>
    </Modal>
  </PlatformDrawer>;
}
