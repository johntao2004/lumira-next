"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Card, Descriptions, Space, Spin, Tag, Typography } from "antd";
import { history } from "@umijs/max";
import { ApiError, api, mutate } from "@/platform/api/client";
import type { LoginMethodAvailability, ReauthenticationAction, WechatAccount, WechatAttempt } from "@/platform/auth/api-types";
import { useReauthenticationTicket } from "@/platform/auth/use-reauthentication-ticket";
import { isTrustedWechatAuthorizationUrl } from "@/platform/auth/wechat-url";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { FormDrawer } from "@/platform/components/form-drawer";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { QueryFailure } from "@/platform/components/query-failure";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { ReauthenticationFields } from "../../../../../plugins/mfa/frontend/reauthentication-fields";
import type { MfaActionValues } from "../../../../../plugins/mfa/frontend/types";

type BindingAction = "BIND" | "REPLACE" | "UNBIND";
type BindingValues = MfaActionValues;
const bindingErrors = ["wechatUnavailable", "wechatAlreadyUsed", "wechatRequiredUsableMethod", "reauthenticationRequired", "reauthenticationInvalid", "reauthenticationExpired", "reauthenticationLocked", "reauthenticationMethodUnavailable", "reauthenticationCodeLimited", "reauthenticationPasskeyUseFirst", "secondFactorInvalid", "mfaRequiredByPolicy", "staleRecord", "forbidden", "passkeyUnsupported", "passkeyCancelled", "passkeyTimeout", "passkeyOperationFailed", "unavailable"] as const satisfies readonly MessageKey[];

export function AccountWechatPage() {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [action, setAction] = useState<BindingAction | null>(null);
  const [form] = Form.useForm<BindingValues>();
  const reauth = useReauthenticationTicket(form);
  const methods = useQuery({ queryKey: ["auth-methods"], queryFn: ({ signal }) => api<LoginMethodAvailability>("/auth/methods", { signal }) });
  const account = useQuery({ queryKey: ["account-wechat"], queryFn: ({ signal }) => api<WechatAccount>("/account/wechat", { signal }), enabled: methods.data?.capabilities.wechat === true });
  const submit = useSubmitAction({ errorKeys: bindingErrors });
  const supported = methods.data?.capabilities.wechat === true && methods.data.methods.includes("WECHAT");

  function close() {
    setAction(null);
    reauth.reset();
    form.resetFields();
    submit.setError(null);
  }

  function open(next: BindingAction) {
    close();
    setAction(next);
  }

  async function finish(values: BindingValues) {
    if (!action || !account.data) return;
    await submit.run(async () => {
      if (!reauth.challenge) {
        await reauth.begin((action === "UNBIND" ? "WECHAT_UNBIND" : "WECHAT_BIND") satisfies ReauthenticationAction);
        return false;
      }
      const ticketResult = await reauth.complete(values);
      if (ticketResult.status === "unavailable") {
        submit.setError("reauthenticationMethodUnavailable");
        return false;
      }
      if (ticketResult.status === "passkey") {
        submit.setError("reauthenticationPasskeyUseFirst");
        return false;
      }
      if (ticketResult.status !== "ready") return false;
      const activeTicket = ticketResult.ticket;

      if (action === "UNBIND") {
        await mutate<void>("/account/wechat", "DELETE", { version: account.data.version, reauthenticationTicketId: activeTicket.ticketId });
        close();
        await Promise.all([client.invalidateQueries({ queryKey: ["account-wechat"] }), client.invalidateQueries({ queryKey: ["authentication-policy"] })]);
        return true;
      }

      const attempt = await mutate<WechatAttempt>("/account/wechat/binding/start", "POST", {
        version: account.data.version,
        replace: action === "REPLACE",
        reauthenticationTicketId: activeTicket.ticketId,
      });
      if (attempt.purpose !== "BIND" || !isTrustedWechatAuthorizationUrl(attempt.authorizationUrl)) {
        reauth.reset();
        form.resetFields();
        submit.setError("wechatUnavailable");
        return false;
      }
      close();
      window.location.assign(attempt.authorizationUrl);
      return true;
    });
  }

  if (methods.isPending) return <Spin />;
  if (methods.error) return <QueryFailure title={t("unavailable")} pending={methods.isFetching} onRetry={() => void methods.refetch()} />;
  if (!supported) return <Alert type="info" showIcon message={t("wechatNotAvailableForAccount")} />;

  return <>
    <Card title={t("wechatBinding")}>
      {account.isPending ? <Spin /> : account.error ? <QueryFailure title={t("unavailable")} pending={account.isFetching} onRetry={() => void account.refetch()} /> : account.data ? <>
        <Descriptions column={{ xs: 1, md: 2 }} items={[
          { key: "status", label: t("status"), children: <Tag color={account.data.bound ? "success" : "default"}>{t(account.data.bound ? "wechatBound" : "wechatNotBound")}</Tag> },
          { key: "usable", label: t("authenticationUsableMethods"), children: <Tag color={account.data.usable ? "success" : "warning"}>{t(account.data.usable ? "wechatUsable" : "wechatNotUsable")}</Tag> },
          ...(account.data.identity?.nickname ? [{ key: "nickname", label: t("wechatNickname"), children: account.data.identity.nickname }] : []),
        ]} />
        <Typography.Paragraph type="secondary">{t("wechatBindingHint")}</Typography.Paragraph>
        <Space wrap>
          {!account.data.bound && <Button type="primary" onClick={() => open("BIND")}>{t("wechatStartBinding")}</Button>}
          {account.data.bound && <>
            <Button onClick={() => open("REPLACE")}>{t("wechatReplaceBinding")}</Button>
            <Button danger onClick={() => open("UNBIND")}>{t("wechatUnbind")}</Button>
          </>}
        </Space>
      </> : null}
    </Card>

    <FormDrawer open={action !== null} title={t(action === "REPLACE" ? "wechatReplaceBinding" : action === "UNBIND" ? "wechatUnbind" : "wechatStartBinding")} pending={submit.pending} submitDisabled={Boolean(reauth.challenge && !reauth.challenge.methods.length)} formId="wechat-binding" onClose={close}>
      {action === "REPLACE" && <Alert className="mb-4" type="warning" showIcon message={t("wechatReplaceOnlyAfterConfirm")} />}
      {action === "UNBIND" && <Alert className="mb-4" type="warning" showIcon message={t("wechatUnbindMayRequireAnotherMethod")} />}
      <FeedbackBubble id="wechat-binding-error" content={submit.errorMessage} />
      <Form id="wechat-binding" form={form} layout="vertical" disabled={submit.pending} onFinish={finish}>
        {reauth.challenge && <ReauthenticationFields
          challenge={reauth.challenge}
          form={form}
          hint={t("reauthenticationMfaHint")}
          currentPasswordLabel={t("currentPassword")}
          passwordRequired={t("passwordRequired")}
          factorCodeLabel={t("mfaCode")}
          secondFactorInvalid={t("secondFactorInvalid")}
          onPasskeyTicket={reauth.setTicket}
        />}
      </Form>
    </FormDrawer>
  </>;
}
