"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Skeleton, Space, Typography } from "antd";
import { history } from "@umijs/max";
import { api, mutate } from "@/platform/api/client";
import type { LoginResponse, WechatAccount, WechatResult } from "./api-types";
import { AuthPageLayout } from "./auth-page-layout";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";

const resultErrors = ["authorizationContextChanged", "staleRecord", "wechatUnavailable", "wechatAlreadyUsed", "wechatRequiredUsableMethod", "forbidden", "unavailable"] as const satisfies readonly MessageKey[];

export function WechatResultPage() {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const result = useQuery({
    queryKey: ["wechat-result"],
    queryFn: ({ signal }) => api<WechatResult>("/auth/wechat/result", { signal }),
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
  const binding = useQuery({
    queryKey: ["account-wechat"],
    queryFn: ({ signal }) => api<WechatAccount>("/account/wechat", { signal }),
    enabled: result.data?.purpose === "BIND" && result.data.status === "READY" && Boolean(result.data.attemptId),
    staleTime: 0,
  });
  const action = useSubmitAction({ errorKeys: resultErrors });

  async function confirmBinding() {
    const attemptId = result.data?.attemptId;
    const version = binding.data?.version;
    if (!attemptId || version === undefined || confirming) return;
    setConfirming(true);
    await action.run(async () => {
      await mutate<WechatAccount>("/account/wechat/binding/confirm", "POST", { attemptId, version, confirm: true });
      await Promise.all([
        client.invalidateQueries({ queryKey: ["account-wechat"] }),
        client.invalidateQueries({ queryKey: ["authentication-policy"] }),
        client.invalidateQueries({ queryKey: ["wechat-result"] }),
      ]);
      history.replace("/dashboard/account-wechat");
    });
    setConfirming(false);
  }

  if (result.isPending || result.error) return <AuthPageLayout>
    <div className="login-tab" role="heading" aria-level={2}>{t("wechatLogin")}</div>
    {result.isPending
      ? <Skeleton active paragraph={{ rows: 6 }} />
      : <Alert type="error" showIcon title={t("unavailable")} action={<Button type="link" onClick={() => void result.refetch()}>{t("retry")}</Button>} />}
  </AuthPageLayout>;
  const value = result.data;
  if (!value) return <AuthPageLayout>{null}</AuthPageLayout>;

  const statusKey = value.status === "READY" ? "wechatResultReady"
    : value.status === "UNBOUND" ? "wechatResultUnbound"
      : value.status === "PENDING" ? "wechatResultPending"
        : value.status === "EXPIRED" ? "wechatResultExpired"
          : value.status === "CANCELLED" ? "wechatResultCancelled"
            : value.status === "NONE" ? "wechatResultNone"
              : "wechatResultFailed";
  const actionContent = value.purpose === "LOGIN" && value.status === "READY" && value.login
    ? <Button type="primary" block onClick={() => continueWechatLogin(value.login!)}>{t("wechatContinueLogin")}</Button>
    : value.purpose === "REGISTRATION" && value.status === "READY" && value.wechatRegistrationProofId
      ? <Button type="primary" block onClick={() => history.replace("/user/register")}>{t("wechatReturnToRegistration")}</Button>
      : value.purpose === "BIND" && value.status === "READY" && value.attemptId
        ? <Space direction="vertical" className="w-full" style={{ alignItems: "stretch" }}>
          {binding.isPending ? <Skeleton active paragraph={{ rows: 2 }} /> : binding.error ? <Alert type="error" showIcon title={t("wechatBindingUnavailable")} action={<Button type="link" onClick={() => void binding.refetch()}>{t("retry")}</Button>} /> : binding.data ? <>
            <Alert type="info" showIcon title={t("wechatExplicitConfirmationRequired", { name: value.identity?.nickname ?? t("wechatUnknownIdentity") })} />
            <Button type="primary" block loading={confirming || action.pending} onClick={() => void confirmBinding()}>{t("wechatConfirmBinding")}</Button>
          </> : null}
        </Space>
        : value.purpose === "LOGIN" && value.status === "UNBOUND"
          ? <Space direction="vertical" className="w-full" style={{ alignItems: "stretch" }}><Button block onClick={() => history.replace("/user/login")}>{t("passwordLogin")}</Button><Button type="primary" block onClick={() => history.replace("/user/register")}>{t("publicRegistrationTitle")}</Button></Space>
            : value.purpose === "BIND"
            ? <Button block onClick={() => history.replace("/dashboard/account-wechat")}>{t("accountSecurity")}</Button>
            : value.purpose === "REGISTRATION"
              ? <Button block onClick={() => history.replace("/user/register")}>{t("wechatReturnToRegistration")}</Button>
              : <Button block onClick={() => history.replace("/user/login")}>{t("passwordLogin")}</Button>;

  return <AuthPageLayout>
    <div className="login-tab" role="heading" aria-level={2}>{t(value.purpose === "BIND" ? "wechatBinding" : "wechatLogin")}</div>
    <Space direction="vertical" size="middle" className="w-full" style={{ alignItems: "stretch" }}>
      <Alert type={value.status === "FAILED" || value.status === "EXPIRED" ? "warning" : value.status === "READY" ? "success" : "info"} showIcon title={t(statusKey)} />
      {value.identity?.nickname && <Typography.Paragraph>{t("wechatNickname")}: {value.identity.nickname}</Typography.Paragraph>}
      <FeedbackBubble id="wechat-result-error" content={action.error ? t(action.error) : undefined} />
      <div className="login-actions">
        {actionContent}
        <Button type="link" onClick={() => void result.refetch()}>{t("retry")}</Button>
      </div>
    </Space>
  </AuthPageLayout>;
}

function continueWechatLogin(result: LoginResponse) {
  history.replace("/user/login", { wechatLogin: result });
}
