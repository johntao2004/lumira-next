"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Card, Skeleton, Space, Typography } from "antd";
import { history } from "@umijs/max";
import { api, mutate } from "@/platform/api/client";
import type { LoginResponse, WechatAccount, WechatResult } from "./api-types";
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

  if (result.isPending) return <main className="workspace-loading"><Skeleton active paragraph={{ rows: 6 }} /></main>;
  if (result.error) return <main className="login-page"><section className="login-shell"><Card title={t("wechatBinding")}><Alert type="error" showIcon title={t("unavailable")} action={<Button type="link" onClick={() => void result.refetch()}>{t("retry")}</Button>} /></Card></section></main>;
  const value = result.data;
  if (!value) return null;

  const statusKey = value.status === "READY" ? "wechatResultReady"
    : value.status === "UNBOUND" ? "wechatResultUnbound"
      : value.status === "PENDING" ? "wechatResultPending"
        : value.status === "EXPIRED" ? "wechatResultExpired"
          : value.status === "CANCELLED" ? "wechatResultCancelled"
            : value.status === "NONE" ? "wechatResultNone"
              : "wechatResultFailed";
  const actionContent = value.purpose === "LOGIN" && value.status === "READY" && value.login
    ? <Button type="primary" onClick={() => continueWechatLogin(value.login!)}>{t("wechatContinueLogin")}</Button>
    : value.purpose === "REGISTRATION" && value.status === "READY" && value.wechatRegistrationProofId
      ? <Button type="primary" onClick={() => history.replace("/user/register")}>{t("wechatReturnToRegistration")}</Button>
      : value.purpose === "BIND" && value.status === "READY" && value.attemptId
        ? <Space direction="vertical" className="w-full">
          {binding.isPending ? <Skeleton active paragraph={{ rows: 2 }} /> : binding.error ? <Alert type="error" showIcon title={t("wechatBindingUnavailable")} action={<Button type="link" onClick={() => void binding.refetch()}>{t("retry")}</Button>} /> : binding.data ? <>
            <Alert type="info" showIcon title={t("wechatExplicitConfirmationRequired", { name: value.identity?.nickname ?? t("wechatUnknownIdentity") })} />
            <Button type="primary" loading={confirming || action.pending} onClick={() => void confirmBinding()}>{t("wechatConfirmBinding")}</Button>
          </> : null}
        </Space>
        : value.purpose === "LOGIN" && value.status === "UNBOUND"
          ? <Space wrap><Button onClick={() => history.replace("/user/login")}>{t("passwordLogin")}</Button><Button type="primary" onClick={() => history.replace("/user/register")}>{t("publicRegistrationTitle")}</Button></Space>
            : value.purpose === "BIND"
            ? <Button onClick={() => history.replace("/dashboard/account-wechat")}>{t("accountSecurity")}</Button>
            : value.purpose === "REGISTRATION"
              ? <Button onClick={() => history.replace("/user/register")}>{t("wechatReturnToRegistration")}</Button>
              : <Button onClick={() => history.replace("/user/login")}>{t("passwordLogin")}</Button>;

  return <main className="login-page" style={{ minHeight: "100dvh", height: "auto" }}>
    <section className="login-shell">
      <div className="login-content">
        <Card title={t(value.purpose === "BIND" ? "wechatBinding" : "wechatLogin")}>
          <Space direction="vertical" size="middle" className="w-full">
            <Alert type={value.status === "FAILED" || value.status === "EXPIRED" ? "warning" : value.status === "READY" ? "success" : "info"} showIcon title={t(statusKey)} />
            {value.identity?.nickname && <Typography.Paragraph>{t("wechatNickname")}: {value.identity.nickname}</Typography.Paragraph>}
            <FeedbackBubble id="wechat-result-error" content={action.error ? t(action.error) : undefined} />
            {actionContent}
            <Button type="link" onClick={() => void result.refetch()}>{t("retry")}</Button>
          </Space>
        </Card>
      </div>
    </section>
  </main>;
}

function continueWechatLogin(result: LoginResponse) {
  history.replace("/user/login", { wechatLogin: result });
}
