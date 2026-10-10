"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { App, Button, Input, Tooltip, Typography } from "antd";
import { api, mutate } from "@/platform/api/client";
import { listRequestParams, type PageResult } from "@/platform/api/pagination";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { FormDrawer } from "@/platform/components/form-drawer";
import { ResourceTable } from "@/platform/components/resource-table";
import { TableActions } from "@/platform/components/table-actions";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { usePreferences } from "@/platform/providers";
import type { AuthenticationPolicy } from "@/platform/auth/api-types";
import { useReauthenticationTicket } from "@/platform/auth/use-reauthentication-ticket";
import { ReauthenticationFields } from "./reauthentication-fields";
import { mfaErrorKeys, type MfaAccount, type MfaActionValues } from "./types";

export function ManagementPage() {
  const { t, locale } = usePreferences();
  const { message } = App.useApp();
  const client = useQueryClient();
  const currentUser = client.getQueryData<{ username: string }>(["session"]);
  const [request, setRequest] = useListRequest();
  const [target, setTarget] = useState<MfaAccount | null>(null);
  const [form] = Form.useForm<MfaActionValues>();
  const reauth = useReauthenticationTicket(form);
  const submit = useSubmitAction({ errorKeys: mfaErrorKeys });
  const query = useQuery({
    queryKey: ["mfa", "manage", request],
    queryFn: ({ signal }) => api<PageResult<MfaAccount>>(`/mfa/manage?${listRequestParams(request)}`, { signal }),
  });
  const policy = useQuery({
    queryKey: ["authentication-policy"],
    queryFn: ({ signal }) => api<AuthenticationPolicy>("/account/authentication-policy", { signal }),
  });

  function close() {
    setTarget(null);
    reauth.reset();
    form.resetFields();
    submit.setError(null);
  }

  return (
    <>
      <ResourceTable<MfaAccount>
        result={query.data}
        loading={query.isFetching}
        error={query.error}
        request={request}
        onChange={setRequest}
        onRetry={() => void query.refetch()}
        onSearch={({ filters }) => setRequest(current => ({
          ...current,
          page: 1,
          search: filters?.username ?? "",
          filters: {},
          filterModes: {},
        }))}
        searchInitialValues={{ username: request.search }}
        columns={[
          { title: t("username"), dataIndex: "username", search: true },
          { title: t("name"), dataIndex: "displayName" },
          { title: t("mfaAppLabel"), dataIndex: "label" },
          {
            title: t("mfaBoundAt"),
            dataIndex: "createdAt",
            render: (_, row) => row.createdAt ? formatDateTime(row.createdAt, locale) : "—",
          },
          { title: t("mfaRecoveryRemaining"), dataIndex: "recoveryCodesRemaining" },
          {
            title: t("actions"),
            key: "actions",
            render: (_, row) => row.username === currentUser?.username ? (
              <Tooltip title={t("mfaUseSelfService")}>
                <span>
                  <Button type="link" disabled>{t("mfaReset")}</Button>
                </span>
              </Tooltip>
            ) : (
              <TableActions>
                <Button type="link" danger onClick={() => {
                  close();
                  setTarget(row);
                }}>
                  {t("mfaReset")}
                </Button>
              </TableActions>
            ),
          },
        ]}
      />

      <FormDrawer
        open={target !== null}
        title={`${t("mfaReset")} · ${target?.username ?? ""}`}
        pending={submit.pending}
        submitDisabled={!policy.data || Boolean(reauth.challenge && !reauth.challenge.methods.length)}
        formId="mfa-reset"
        onClose={close}
      >
        <Typography.Paragraph>{t("mfaResetHint")}</Typography.Paragraph>
        <FeedbackBubble id="mfa-reset-error" content={submit.errorMessage} />
        <Form
          id="mfa-reset"
          form={form}
          layout="vertical"
          disabled={submit.pending}
          onFinish={values => submit.run(async () => {
            if (!target) return false;
            if (!reauth.challenge) {
              await reauth.begin("MFA_ADMIN_RESET");
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
            await mutate<void>(`/mfa/manage/${target.id}/reset`, "POST", {
              reauthenticationTicketId: activeTicket.ticketId,
              reason: values.reason,
            });
            close();
            await client.invalidateQueries({ queryKey: ["mfa"] });
            void message.success(t("mfaOperationSaved"));
          })}
        >
          {reauth.challenge && (
            <ReauthenticationFields
              challenge={reauth.challenge}
              form={form}
              hint={t("reauthenticationMfaHint")}
              currentPasswordLabel={t("currentPassword")}
              passwordRequired={t("passwordRequired")}
              factorCodeLabel={t("mfaCode")}
              secondFactorInvalid={t("secondFactorInvalid")}
              onPasskeyTicket={reauth.setTicket}
            />
          )}
          <Form.Item
            name="reason"
            label={t("mfaResetReason")}
            rules={[{ required: true, min: 5, max: 500, whitespace: true, message: t("mfaResetReasonRequired") }]}
          >
            <Input.TextArea maxLength={500} />
          </Form.Item>
        </Form>
      </FormDrawer>
    </>
  );
}
