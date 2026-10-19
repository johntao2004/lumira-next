/** 编辑注册策略及授权范围内的默认角色。
 * Edit registration policy and permitted default-role assignments. */
"use client";

import { useEffect, useState } from "react";
import { useSettingsDraft } from "@/platform/forms/use-settings-draft";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Popconfirm, Alert, Checkbox, Input, Pagination, Select, Space, Spin, Switch, Tag, Typography } from "antd";
import { api, mutate } from "@/platform/api/client";
import { listRequestParams, type PageResult } from "@/platform/api/pagination";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { QueryFailure } from "@/platform/components/query-failure";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SettingsEnabledControls } from "@/platform/components/settings-enabled-controls";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import "./authentication-settings-panels.css";
import "./registration-page.css";

type RegistrationReadiness = {
  emailAvailable: boolean;
  smsAvailable: boolean;
  phoneRecoverySupported: boolean;
  agreementsAvailable: boolean;
  defaultRoleEligible: boolean;
  maintenance: boolean;
  captchaAvailable: boolean;
};

type RegistrationChannel = "EMAIL" | "SMS";

type RegistrationSettings = {
  version: number;
  enabled: boolean;
  channels: RegistrationChannel[];
  defaultRoleId: string | null;
  readiness: RegistrationReadiness;
};

type RegistrationRoleCandidate = {
  id: string;
  code: string;
  name: string;
  version: number;
  eligible: boolean;
  reason: string | null;
};

type RegistrationSettingsForm = {
  enabled: boolean;
  channels: RegistrationChannel[];
  defaultRoleId: string | null;
};

const settingsErrors = [
  "staleRecord", "invalidRegistrationPolicy", "registrationRoleIneligibleError",
  "phoneRecoveryUnavailable", "forbidden", "unavailable",
] as const satisfies readonly MessageKey[];

const roleReasonKeys: Record<string, MessageKey> = {
  registrationRoleDisabled: "registrationRoleDisabled",
  registrationRoleAdministrator: "registrationRoleAdministrator",
  registrationRoleScopeNotAllowed: "registrationRoleScopeNotAllowed",
  registrationRolePermissionsNotAllowed: "registrationRolePermissionsNotAllowed",
};

export function RegistrationPage({ canWrite, canReadRoles, canAssignDefaultRole = false }: { canWrite: boolean; canReadRoles: boolean; canAssignDefaultRole?: boolean }) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm<RegistrationSettingsForm>();
  const [roleSearch, setRoleSearch] = useState("");
  const [roleSearchDraft, setRoleSearchDraft] = useState("");
  const [rolePage, setRolePage] = useState(1);
  const [rolePageSize, setRolePageSize] = useState(20);
  const [saved, setSaved] = useState(false);

  const query = useQuery({
    queryKey: ["settings", "registration"],
    queryFn: ({ signal }) => api<RegistrationSettings>("/platform/settings/registration", { signal }),
  });
  const roles = useQuery({
    queryKey: ["settings", "registration", "roles", rolePage, rolePageSize, roleSearch],
    queryFn: ({ signal }) => api<PageResult<RegistrationRoleCandidate>>(
      `/platform/settings/registration/roles?${listRequestParams({ page: rolePage, pageSize: rolePageSize, search: roleSearch })}`,
      { signal },
    ),
    enabled: canReadRoles,
  });
  const action = useSubmitAction({
    errorKeys: settingsErrors,
    onError: async key => { if (key === "staleRecord") await query.refetch(); },
  });
  const enabled = Form.useWatch("enabled", form);
  const channels = Form.useWatch("channels", form) ?? [];
  const selectedRoleId = Form.useWatch("defaultRoleId", form);

  const { snapshot, revision, commit: setSnapshot, reload, operationSignal } = useSettingsDraft(query.data,
    () => query.refetch({ throwOnError: true }), () => { form.resetFields(); setSaved(false); });
  // 草稿与首次读到的版本绑定，后台刷新只更新状态提示。
  // Bind drafts to their initial version; background refresh updates status only.
  useEffect(() => {
    if (!snapshot) return;
    form.setFieldsValue({
      enabled: snapshot.enabled,
      channels: snapshot.channels,
      defaultRoleId: snapshot.defaultRoleId,
    });
  }, [form, snapshot, revision]);

  async function save(values: RegistrationSettingsForm) {
    if (!canWrite || !snapshot) return;
    const signal = operationSignal();
    if (signal.aborted) return;
    await action.run(async () => {
      const result = await mutate<RegistrationSettings>("/platform/settings/registration", "PUT", {
        version: snapshot.version,
        enabled: values.enabled,
        channels: values.channels ?? [],
        defaultRoleId: canAssignDefaultRole ? (values.defaultRoleId || null) : snapshot.defaultRoleId,
      }, signal);
      if (signal.aborted) return;
      setSnapshot(result);
      client.setQueryData(["settings", "registration"], result);
      form.setFieldsValue({
        enabled: result.enabled,
        channels: result.channels,
        defaultRoleId: result.defaultRoleId,
      });
      if (signal.aborted) return;
      setSaved(true);
    });
  }

  if (query.isPending) return <Spin />;
  if (query.error) return <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />;
  if (!query.data) return null;

  const candidates = roles.data?.items ?? [];
  const selectedCandidate = candidates.find(candidate => candidate.id === selectedRoleId);
  const roleOptions = candidates.map(candidate => ({
    value: candidate.id,
    disabled: !candidate.eligible,
    label: <Space className="registration-role-option" size={6} wrap>
      <span className="registration-role-option-name">{candidate.name} ({candidate.code})</span>
      {candidate.eligible
        ? <Tag color="success">{t("registrationRoleEligible")}</Tag>
        : <Typography.Text type="danger">{t(roleReasonKeys[candidate.reason ?? ""] ?? "registrationRoleIneligible")}</Typography.Text>}
    </Space>,
  }));
  if (selectedRoleId && !candidates.some(candidate => candidate.id === selectedRoleId)) {
    roleOptions.unshift({
      value: selectedRoleId,
      disabled: true,
      label: <span className="registration-role-option-name">{t("registrationRoleCurrentId", { id: selectedRoleId })}</span>,
    });
  }
  const readiness: Array<{ key: keyof RegistrationReadiness; label: MessageKey; ready: boolean }> = [
    { key: "emailAvailable", label: "registrationReadinessEmail", ready: query.data.readiness.emailAvailable },
    { key: "smsAvailable", label: "registrationReadinessSms", ready: query.data.readiness.smsAvailable },
    { key: "phoneRecoverySupported", label: "registrationReadinessPhoneRecovery", ready: query.data.readiness.phoneRecoverySupported },
    { key: "agreementsAvailable", label: "registrationReadinessAgreements", ready: query.data.readiness.agreementsAvailable },
    { key: "defaultRoleEligible", label: "registrationReadinessDefaultRole", ready: query.data.readiness.defaultRoleEligible },
    { key: "captchaAvailable", label: "registrationReadinessCaptcha", ready: query.data.readiness.captchaAvailable },
    { key: "maintenance", label: "registrationReadinessMaintenance", ready: !query.data.readiness.maintenance },
  ];

  return <>
    <Form
      className="settings-form auth-config-form registration-settings-form"
      form={form}
      layout="vertical"
      disabled={!canWrite || action.pending}
      onValuesChange={() => setSaved(false)}
      onFinish={save}
    >
      <div className="auth-config-panels">
        <div className="auth-config-stack">
          <section className="auth-config-panel">
            <Typography.Title level={5}>{t("registrationSettingsTitle")}</Typography.Title>
            <Form.Item className="auth-config-toggle" name="enabled" label={t("registrationEnabled")} valuePropName="checked" extra={t("registrationEnabledHint")}>
              <Switch />
            </Form.Item>
            <SettingsEnabledControls name="enabled">
              <div className="auth-config-fields">
                <Form.Item name="channels" label={t("registrationChannels")}>
                  <Checkbox.Group>
                    <Space wrap>
                      <Checkbox value="EMAIL">{t("registrationChannelEmail")}</Checkbox>
                      <Checkbox value="SMS" disabled={!enabled || (!query.data.readiness.phoneRecoverySupported && !channels.includes("SMS"))}>{t("registrationChannelSms")}</Checkbox>
                    </Space>
                  </Checkbox.Group>
                </Form.Item>
              </div>
            </SettingsEnabledControls>
            {!query.data.readiness.phoneRecoverySupported && <Alert className="registration-phone-recovery-alert" type="warning" showIcon title={t("registrationPhoneRecoveryUnavailable")} />}
          </section>

          <section className="auth-config-panel">
            <Typography.Title level={5}>{t("registrationDefaultRole")}</Typography.Title>
            {!canReadRoles ? <Alert type="warning" showIcon title={t("registrationRolesReadUnavailable")} /> : <>
              <SettingsEnabledControls name="enabled">
                <div className="auth-config-fields">
                  <Form.Item label={t("registrationRoleSearch")} htmlFor="registration-role-search">
                    <Input.Search
                      id="registration-role-search"
                      allowClear
                      maxLength={100}
                      value={roleSearchDraft}
                      aria-label={t("registrationRoleSearch")}
                      placeholder={t("registrationRoleSearch")}
                      onChange={event => setRoleSearchDraft(event.target.value)}
                      onSearch={value => { setRoleSearch(value.trim()); setRolePage(1); }}
                    />
                  </Form.Item>
                  <Form.Item
                    name="defaultRoleId"
                    label={t("registrationDefaultRole")}
                    rules={enabled ? [{ required: true, message: t("registrationRoleIneligibleError") }] : []}
                  >
                    <Select
                      className="registration-role-select"
                      aria-label={t("registrationDefaultRole")}
                      allowClear
                      disabled={!canAssignDefaultRole}
                      showSearch={false}
                      loading={roles.isFetching}
                      options={roleOptions}
                      placeholder={t("registrationDefaultRole")}
                      notFoundContent={roles.isError ? t("unavailable") : t("registrationRoleNotFound")}
                    />
                  </Form.Item>
                  {roles.error && <QueryFailure title={t(roles.error instanceof Error && "status" in roles.error && roles.error.status === 403 ? "forbidden" : "unavailable")} pending={roles.isFetching} onRetry={() => void roles.refetch()} />}
                  {selectedCandidate && !selectedCandidate.eligible && <Alert className="registration-selected-candidate-alert" type="error" showIcon title={t(roleReasonKeys[selectedCandidate.reason ?? ""] ?? "registrationRoleIneligible")} />}
                  {roles.data && roles.data.total > 0 && <Pagination
                    className="registration-role-pagination"
                    current={rolePage}
                    pageSize={rolePageSize}
                    total={roles.data.total}
                    size="small"
                    responsive
                    showSizeChanger
                    pageSizeOptions={[10, 20, 50, 100]}
                    showTotal={total => t("totalRecords", { count: total })}
                    onChange={(page, pageSize) => { setRolePage(pageSize === rolePageSize ? page : 1); setRolePageSize(pageSize); }}
                  />}
                </div>
              </SettingsEnabledControls>
            </>}
          </section>
        </div>

        <section className="auth-config-panel registration-readiness-panel">
          <Typography.Title level={5}>{t("registrationReadiness")}</Typography.Title>
          <div className="registration-readiness-list">
            {readiness.map(item => {
              const ready = item.ready;
              const isMaintenance = item.key === "maintenance" && query.data!.readiness.maintenance;
              return <div key={item.key} className="registration-readiness-row">
                <span>{t(item.label)}</span>
                <Tag color={ready && !isMaintenance ? "success" : "warning"}>
                  {isMaintenance ? t("registrationMaintenanceActive") : t(ready ? "registrationReady" : "registrationNeedsAttention")}
                </Tag>
              </div>;
            })}
          </div>
        </section>
      </div>

      <Popconfirm title={t("reloadSettingsHint")} okText={t("reloadSettings")} cancelText={t("cancel")} onConfirm={() => action.run(reload)} disabled={action.pending}>
        <Button disabled={action.pending}>{t("reloadSettings")}</Button>
      </Popconfirm>
      <FeedbackBubble id="registration-settings-error" content={action.error ? t(action.error) : undefined} />
      <FeedbackBubble kind="success" id="registration-settings-saved" content={saved ? t("registrationSaved") : undefined} />
      <Space className="form-actions auth-config-actions" wrap>
        <AsyncButton htmlType="submit" type="primary" pending={action.pending} disabled={!canWrite} label={t("save")} />
      </Space>
    </Form>
  </>;
}
