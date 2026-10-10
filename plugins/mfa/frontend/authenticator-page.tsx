"use client";

import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { App, Button, Card, Descriptions, Input, QRCode, Space, Spin, Tag, Typography } from "antd";
import { api, mutate } from "@/platform/api/client";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { FormDrawer } from "@/platform/components/form-drawer";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { formatDateTime } from "@/platform/i18n/format";
import { usePreferences } from "@/platform/providers";
import type { AuthenticationPolicy, ReauthenticationAction, ReauthenticationStart, ReauthenticationTicket } from "@/platform/auth/api-types";
import { RecoveryCodes } from "./recovery-codes";
import { ReauthenticationFields, reauthenticationCompletionBody } from "./reauthentication-fields";
import { mfaErrorKeys, type MfaActionValues, type MfaRecovery, type MfaSetup, type MfaStatus } from "./types";

type Action = "setup" | "replace" | "rename" | "recovery" | "disable";
const titles = {
  setup: "mfaBind",
  replace: "mfaReplace",
  rename: "mfaRename",
  recovery: "mfaRegenerate",
  disable: "mfaDisable",
} as const;
const reauthenticationActions: Record<Action, ReauthenticationAction> = {
  setup: "MFA_ENROLL",
  replace: "MFA_REPLACE",
  rename: "MFA_RENAME",
  recovery: "MFA_RECOVERY_REGENERATE",
  disable: "MFA_DISABLE",
};

export function AuthenticatorPage() {
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [action, setAction] = useState<Action | null>(null);
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [reauthentication, setReauthentication] = useState<ReauthenticationStart | null>(null);
  const [ticket, setTicket] = useState<ReauthenticationTicket | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [form] = Form.useForm<MfaActionValues>();
  const query = useQuery({
    queryKey: ["mfa", "account"],
    queryFn: ({ signal }) => api<MfaStatus>("/mfa/account", { signal }),
  });
  const policy = useQuery({
    queryKey: ["authentication-policy"],
    queryFn: ({ signal }) => api<AuthenticationPolicy>("/account/authentication-policy", { signal }),
  });
  const submit = useSubmitAction({
    errorKeys: mfaErrorKeys,
    onError: async key => {
      if (key === "mfaSetupExpired" || key === "mfaReplacementExpired") {
        setSetup(null);
        form.setFieldValue("code", undefined);
      }
      if (key === "mfaAlreadyEnabled" || key === "mfaNotEnabled") {
        await client.invalidateQueries({ queryKey: ["mfa", "account"] });
      }
    },
  });
  const setSubmitError = submit.setError;

  useEffect(() => {
    if (!setup) return;
    const expiresAt = Date.parse(setup.expiresAt);
    if (!Number.isFinite(expiresAt)) return;
    const timer = window.setTimeout(() => {
      setSetup(null);
      form.setFieldValue("code", undefined);
      setSubmitError(action === "replace" ? "mfaReplacementExpired" : "mfaSetupExpired");
    }, Math.max(0, expiresAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [action, form, setup, setSubmitError]);

  function close(cancelReplacement = true) {
    if (cancelReplacement && action === "replace" && setup) {
      void mutate<void>("/mfa/account/replacement/cancel", "POST", { challengeId: setup.challengeId }).catch(() => {});
    }
    setAction(null);
    setSetup(null);
    setReauthentication(null);
    setTicket(null);
    form.resetFields();
    submit.setError(null);
  }

  function open(next: Action) {
    close();
    setAction(next);
    form.setFieldsValue({ label: query.data?.label ?? "" });
  }

  async function finish(values: MfaActionValues) {
    const selectedAction = action;
    if (!selectedAction) return;

    await submit.run(async () => {
      if ((selectedAction === "setup" || selectedAction === "replace") && setup) {
        const endpoint = selectedAction === "replace" ? "/mfa/account/replacement/confirm" : "/mfa/account/confirm";
        const result = await mutate<MfaRecovery>(endpoint, "POST", {
          challengeId: setup.challengeId,
          code: values.code,
        });
        close(false);
        if (result?.recoveryCodes?.length) setCodes(result.recoveryCodes);
        await client.invalidateQueries({ queryKey: ["mfa"] });
        void message.success(t("mfaOperationSaved"));
        return;
      }

      let reauthenticationTicket = ticket;
      if (!reauthentication) {
        const challenge = await mutate<ReauthenticationStart>("/account/reauthentication/start", "POST", {
          action: reauthenticationActions[selectedAction],
        });
        setReauthentication(challenge);
        return false;
      }
      if (!reauthenticationTicket) {
        if (!reauthentication.methods.length) {
          setSubmitError("reauthenticationMethodUnavailable");
          return false;
        }
        if ((values.reauthenticationMethod ?? reauthentication.methods[0]) === "PASSKEY") {
          setSubmitError("reauthenticationPasskeyUseFirst");
          return false;
        }
        const body = reauthenticationCompletionBody(reauthentication, values);
        if (!body) return false;
        reauthenticationTicket = await mutate<ReauthenticationTicket>("/account/reauthentication/complete", "POST", body);
        setTicket(reauthenticationTicket);
        form.setFieldsValue({ currentPassword: undefined, factorCode: undefined, reauthenticationCode: undefined, reauthenticationCodeChallengeId: undefined });
      }

      if (selectedAction === "setup" || selectedAction === "replace") {
        const endpoint = selectedAction === "replace" ? "/mfa/account/replacement/setup" : "/mfa/account/setup";
        const nextSetup = await mutate<MfaSetup>(endpoint, "POST", {
          label: values.label,
          reauthenticationTicketId: reauthenticationTicket.ticketId,
        });
        setSetup(nextSetup);
        setReauthentication(null);
        setTicket(null);
        form.setFieldsValue({ currentPassword: undefined, factorCode: undefined, code: undefined });
        return;
      }

      const path = selectedAction === "rename" ? "/label" : selectedAction === "recovery" ? "/recovery" : "";
      const method = selectedAction === "disable" ? "DELETE" : selectedAction === "rename" ? "PUT" : "POST";
      const result = await mutate<MfaRecovery | undefined>(`/mfa/account${path}`, method, {
        reauthenticationTicketId: reauthenticationTicket.ticketId,
        ...(selectedAction === "rename" ? { label: values.label } : {}),
      });

      close();
      if (result?.recoveryCodes?.length) setCodes(result.recoveryCodes);
      await client.invalidateQueries({ queryKey: ["mfa"] });
      void message.success(t("mfaOperationSaved"));
    });
  }

  return (
    <>
      <Card title={t("mfaTitle")}>
        <Typography.Paragraph>{t("mfaHint")}</Typography.Paragraph>
        <FeedbackBubble id="mfa-load" content={query.error ? t("unavailable") : undefined} />
        {query.isPending ? <Spin /> : query.data?.enabled ? (
          <>
            <Descriptions
              column={1}
              items={[
                { key: "name", label: t("mfaAppLabel"), children: query.data.label },
                {
                  key: "date",
                  label: t("mfaBoundAt"),
                  children: query.data.createdAt ? formatDateTime(query.data.createdAt, locale) : "—",
                },
                { key: "recovery", label: t("mfaRecoveryRemaining"), children: query.data.recoveryCodesRemaining },
              ]}
            />
            <Space wrap>
              <Button disabled={Boolean(query.error)} onClick={() => open("replace")}>{t("mfaReplace")}</Button>
              <Button disabled={Boolean(query.error)} onClick={() => open("rename")}>{t("mfaRename")}</Button>
              <Button disabled={Boolean(query.error)} onClick={() => open("recovery")}>{t("mfaRegenerate")}</Button>
              <Button danger disabled={Boolean(query.error)} onClick={() => open("disable")}>{t("mfaDisable")}</Button>
            </Space>
          </>
        ) : (
          <Space wrap>
            <Tag>{t("mfaUnbound")}</Tag>
            <Button type="primary" disabled={Boolean(query.error)} onClick={() => open("setup")}>{t("mfaBind")}</Button>
          </Space>
        )}
        {query.error && <Button onClick={() => void query.refetch()}>{t("retry")}</Button>}
      </Card>

      <FormDrawer
        open={action !== null}
        title={t(titles[action ?? "setup"])}
        pending={submit.pending}
        submitDisabled={Boolean(action && (!policy.data || (reauthentication && !reauthentication.methods.length)))}
        formId="mfa-action"
        onClose={close}
      >
        <FeedbackBubble id="mfa-action-error" content={submit.errorMessage} />
        {setup && (
          <>
            <Typography.Paragraph>{t("mfaSetupHint")}</Typography.Paragraph>
            <QRCode value={setup.uri} />
            <Typography.Paragraph copyable>{setup.secret}</Typography.Paragraph>
          </>
        )}
        {action === "disable" && <Typography.Paragraph>{t("mfaDisableHint")}</Typography.Paragraph>}
        {action === "replace" && <Typography.Paragraph>{t("mfaReplaceHint")}</Typography.Paragraph>}
        <Form id="mfa-action" form={form} layout="vertical" disabled={submit.pending} onFinish={finish}>
          {(action === "rename" || ((action === "setup" || action === "replace") && !setup)) && (
            <Form.Item
              name="label"
              label={t("mfaAppLabel")}
              rules={[{ required: true, whitespace: true, max: 128, message: t("mfaLabelRequired") }]}
            >
              <Input maxLength={128} />
            </Form.Item>
          )}
          {reauthentication && !setup && (
            <ReauthenticationFields
              challenge={reauthentication}
              form={form}
              hint={t("reauthenticationMfaHint")}
              currentPasswordLabel={t("currentPassword")}
              passwordRequired={t("passwordRequired")}
              factorCodeLabel={t("mfaCode")}
              secondFactorInvalid={t("secondFactorInvalid")}
              onPasskeyTicket={setTicket}
            />
          )}
          {setup && (
            <Form.Item
              name="code"
              label={t("mfaCode")}
              rules={[{ required: true, message: t("secondFactorInvalid") }]}
            >
              <Input autoComplete="one-time-code" maxLength={64} />
            </Form.Item>
          )}
        </Form>
      </FormDrawer>

      {codes && <RecoveryCodes codes={codes} onClose={() => setCodes(null)} />}
    </>
  );
}
