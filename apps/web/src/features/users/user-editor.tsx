"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { Alert, Button, Form as AntForm, Input, Select, Spin, Switch, Tabs, Tag, Typography } from "antd";
import { useIntl } from "@ant-design/pro-components";
import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import type { UserSummary } from "@/platform/directory/api-types";
import { FormDrawer } from "@/platform/components/form-drawer";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { CapacityMeter } from "@/platform/components/capacity-meter";
import { QueryFailure } from "@/platform/components/query-failure";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams, type ListRequest } from "@/platform/api/pagination";
import { MAX_PASSWORD_BYTES, defaultPasswordLengthValid } from "@/platform/auth/password-contract";
import { useCenteredModalConfirm } from "@/platform/components/use-centered-modal-confirm";

const userErrorKeys = ["invalidUser", "duplicateRecord", "staleRecord", "protectedAccount", "forbidden", "invalidRole", "invalidOrganization"] as const satisfies readonly MessageKey[];

type UserDetail = UserSummary;
type Assignment = { version: number; roleIds: string[] };
type Membership = { version: number; organizationIds: string[] };
type LinkedRole = { id: string; code: string; name: string; enabled: boolean; fileQuotaBytesLimit: number | null; fileQuotaFilesLimit: number | null };
type LinkedOrganization = { id: string; code: string; name: string; enabled: boolean };
type QuotaPreview = {
  currentMaximumBytes: number;
  currentMaximumFiles: number;
  proposedMaximumBytes: number;
  proposedMaximumFiles: number;
  chargedBytes: number;
  fileCount: number;
  defaultMaximumBytes: number;
  defaultMaximumFiles: number;
  version: number;
};
type UserEditValues = { username?: string; displayName?: string; enabled?: boolean; password?: string };

export function UserEditor({ user, canWrite, canAssignRoles, canAssignOrganizations, onClose, onSaved }: {
  user: UserSummary | null | undefined;
  canWrite: boolean;
  canAssignRoles: boolean;
  canAssignOrganizations: boolean;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t, locale } = usePreferences();
  const confirm = useCenteredModalConfirm();
  const client = useQueryClient();
  const [form] = Form.useForm<UserEditValues>();
  const [rolesRequest, setRolesRequest] = useListRequest();
  const [organizationsRequest, setOrganizationsRequest] = useListRequest();
  const [roleSelection, setRoleSelection] = useState<string[] | null>(null);
  const [organizationSelection, setOrganizationSelection] = useState<string[] | null>(null);
  const [pending, setPending] = useState(false);
  const [committed, setCommitted] = useState(false);
  const [saveError, setSaveError] = useState<MessageKey | null>(null);
  const [refreshFailed, setRefreshFailed] = useState(false);
  const [createChanged, setCreateChanged] = useState(false);
  const [refreshingSnapshots, setRefreshingSnapshots] = useState(false);
  const [, setFormRevision] = useState(0);
  const discardPromptOpen = useRef(false);

  const isExisting = !!user;
  const editorOpen = user !== undefined;
  const sessionId = user?.id ?? (user === null ? "create" : "closed");
  useEffect(() => {
    setRoleSelection(null);
    setOrganizationSelection(null);
    setPending(false);
    setCommitted(false);
    setSaveError(null);
    setRefreshFailed(false);
    setCreateChanged(false);
    if (editorOpen) form.resetFields();
  }, [editorOpen, form, sessionId]);

  const detail = useQuery({
    queryKey: ["user", user?.id],
    enabled: isExisting,
    queryFn: ({ signal }) => api<UserDetail>(`/platform/users/${user!.id}`, { signal }),
    staleTime: 0,
  });
  useEffect(() => {
    if (!detail.data || !editorOpen) return;
    form.setFieldsValue({
      username: detail.data.username,
      displayName: detail.data.displayName,
      enabled: detail.data.enabled,
    });
    setFormRevision(value => value + 1);
  }, [detail.data?.id, editorOpen, form]);
  const roles = useQuery({
    queryKey: ["user-assignments", "roles", user?.id],
    enabled: isExisting && canAssignRoles,
    queryFn: ({ signal }) => api<Assignment>(`/platform/users/${user!.id}/roles`, { signal }),
    staleTime: 0,
  });
  const organizations = useQuery({
    queryKey: ["user-assignments", "organizations", user?.id],
    enabled: isExisting && canAssignOrganizations,
    queryFn: ({ signal }) => api<Membership>(`/platform/users/${user!.id}/organizations`, { signal }),
    staleTime: 0,
  });
  const rolesList = useQuery({
    queryKey: ["roles", rolesRequest],
    enabled: isExisting && canAssignRoles,
    queryFn: ({ signal }) => api<PageResult<LinkedRole>>(`/platform/roles?${listRequestParams(rolesRequest)}`, { signal }),
  });
  const organizationsList = useQuery({
    queryKey: ["organizations", organizationsRequest],
    enabled: isExisting && canAssignOrganizations,
    queryFn: ({ signal }) => api<PageResult<LinkedOrganization>>(`/platform/organizations?${listRequestParams(organizationsRequest)}`, { signal }),
  });

  const selectedRoleIds = roleSelection ?? roles.data?.roleIds ?? [];
  const selectedOrganizationIds = organizationSelection ?? organizations.data?.organizationIds ?? [];
  const sortedRoleIds = useMemo(() => [...selectedRoleIds].sort(), [selectedRoleIds]);
  const quotaPreview = useQuery({
    queryKey: ["user-file-quota-preview", user?.id, sortedRoleIds],
    enabled: isExisting && canAssignRoles && !!roles.data,
    queryFn: ({ signal }) => {
      const params = new URLSearchParams();
      sortedRoleIds.forEach(id => params.append("roleIds", id));
      return api<QuotaPreview>(`/platform/users/${user!.id}/file-quota/preview?${params}`, { signal });
    },
    staleTime: 0,
  });

  const values = form.getFieldsValue(true);
  const profileChanged = !!(isExisting && canWrite && detail.data
    && (values.displayName !== detail.data.displayName || values.enabled !== detail.data.enabled));
  const rolesChanged = !!(canAssignRoles && roles.data && !sameIds(selectedRoleIds, roles.data.roleIds));
  const organizationsChanged = !!(canAssignOrganizations && organizations.data
    && !sameIds(selectedOrganizationIds, organizations.data.organizationIds));
  const hasChanges = isExisting ? profileChanged || rolesChanged || organizationsChanged : false;
  const isDirty = !committed && (isExisting ? hasChanges : createChanged);

  const versions = [
    detail.data?.version,
    canAssignRoles ? roles.data?.version : undefined,
    canAssignOrganizations ? organizations.data?.version : undefined,
    canAssignRoles ? quotaPreview.data?.version : undefined,
  ].filter((version): version is number => version !== undefined);
  const versionMismatch = !!detail.data && versions.some(version => version !== detail.data!.version);
  const roleSaveReady = !rolesChanged || (!!rolesList.data && !rolesList.error && !!quotaPreview.data && !quotaPreview.error);
  const organizationSaveReady = !organizationsChanged || (!!organizationsList.data && !organizationsList.error);
  const submitDisabled = isExisting
    ? !detail.data || !!detail.error || !hasChanges || versionMismatch || !roleSaveReady || !organizationSaveReady || committed
    : !canWrite || committed;

  const retrySnapshots = async () => {
    if (refreshingSnapshots || pending) return;
    setRefreshingSnapshots(true);
    setRoleSelection(null);
    setOrganizationSelection(null);
    setSaveError(null);
    setRefreshFailed(false);
    try {
      const freshDetail = await detail.refetch({ throwOnError: true });
      if (freshDetail.data) {
        form.setFieldsValue({
          username: freshDetail.data.username,
          displayName: freshDetail.data.displayName,
          enabled: freshDetail.data.enabled,
        });
        setFormRevision(value => value + 1);
      }
      await Promise.all([
        canAssignRoles ? roles.refetch({ throwOnError: true }) : Promise.resolve(undefined),
        canAssignOrganizations ? organizations.refetch({ throwOnError: true }) : Promise.resolve(undefined),
      ]);
      if (canAssignRoles && user) {
        await client.invalidateQueries({ queryKey: ["user-file-quota-preview", user.id] });
      }
    } catch {
      // Query errors remain visible in their own section and can be retried there.
    } finally {
      setRefreshingSnapshots(false);
    }
  };

  const refreshAfterSave = async () => {
    try {
      await onSaved();
      onClose();
    } catch {
      setRefreshFailed(true);
    }
  };

  const closeNow = () => {
    discardPromptOpen.current = false;
    setSaveError(null);
    setRefreshFailed(false);
    onClose();
  };
  const requestClose = () => {
    if (pending) return;
    if (!isDirty) {
      closeNow();
      return;
    }
    if (discardPromptOpen.current) return;
    discardPromptOpen.current = true;
    confirm({
      title: t("userEditorDiscardTitle"),
      content: t("userEditorDiscardContent"),
      okText: t("confirm"),
      cancelText: t("cancel"),
      okButtonProps: { danger: true },
      onOk: closeNow,
      onCancel: () => { discardPromptOpen.current = false; },
    });
  };

  const confirmQuotaPolicyChange = async (preview: QuotaPreview) => {
    const storageChanged = preview.currentMaximumBytes !== preview.proposedMaximumBytes;
    const countChanged = preview.currentMaximumFiles !== preview.proposedMaximumFiles;
    if (!storageChanged && !countChanged) return true;
    const decrease = preview.proposedMaximumBytes < preview.currentMaximumBytes
      || preview.proposedMaximumFiles < preview.currentMaximumFiles;
    const increase = preview.proposedMaximumBytes > preview.currentMaximumBytes
      || preview.proposedMaximumFiles > preview.currentMaximumFiles;
    const rows = [
      storageChanged && `${t("roleFileStorageLimit")}: ${formatBytes(preview.currentMaximumBytes, locale)} → ${formatBytes(preview.proposedMaximumBytes, locale)}`,
      countChanged && `${t("roleFileCountLimit")}: ${formatCount(preview.currentMaximumFiles, locale)} → ${formatCount(preview.proposedMaximumFiles, locale)}`,
      decrease && t("userQuotaDecreaseConfirm"),
      increase && t("userQuotaIncreaseConfirm"),
    ].filter(Boolean);
    return new Promise<boolean>(resolve => confirm({
      title: t("userEffectiveQuotaPreview", { storage: formatBytes(preview.proposedMaximumBytes, locale), files: formatCount(preview.proposedMaximumFiles, locale) }),
      content: <div className="flex flex-col gap-2">{rows.map((row, index) => <Typography.Paragraph key={index} className="mb-0">{row}</Typography.Paragraph>)}</div>,
      okText: t("confirm"),
      cancelText: t("cancel"),
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    }));
  };

  const onFinish = async (submitted: UserEditValues) => {
    if (pending || committed) return false;
    setPending(true);
    setSaveError(null);
    setRefreshFailed(false);
    try {
      if (!isExisting) {
        await mutate("/platform/users", "POST", {
          username: submitted.username,
          displayName: submitted.displayName,
          password: submitted.password,
        });
      } else {
        if (!detail.data || versionMismatch) return false;
        const profileChangedNow = canWrite && (submitted.displayName !== detail.data.displayName || submitted.enabled !== detail.data.enabled);
        const rolesChangedNow = canAssignRoles && !!roles.data && !sameIds(selectedRoleIds, roles.data.roleIds);
        const organizationsChangedNow = canAssignOrganizations && !!organizations.data
          && !sameIds(selectedOrganizationIds, organizations.data.organizationIds);
        if (!profileChangedNow && !rolesChangedNow && !organizationsChangedNow) return false;
        if (selectedRoleIds.length > 100 || selectedOrganizationIds.length > 100) {
          setSaveError(selectedRoleIds.length > 100 ? "invalidRole" : "invalidOrganization");
          return false;
        }
        if (rolesChangedNow) {
          if (!quotaPreview.data || quotaPreview.error) return false;
          if (!await confirmQuotaPolicyChange(quotaPreview.data)) return false;
        }
        const payload = {
          version: detail.data.version,
          ...(profileChangedNow ? { profile: { displayName: submitted.displayName?.trim(), enabled: submitted.enabled } } : {}),
          ...(rolesChangedNow ? { roleIds: selectedRoleIds } : {}),
          ...(organizationsChangedNow ? { organizationIds: selectedOrganizationIds } : {}),
        };
        await mutate(`/platform/users/${user!.id}/editor`, "PUT", payload);
      }
      setCommitted(true);
      await refreshAfterSave();
      return true;
    } catch (cause) {
      setSaveError(messageKeyFor(cause, userErrorKeys));
      return false;
    } finally {
      setPending(false);
    }
  };

  const title = user ? `${t("edit")} · ${user.displayName}` : t("createUser");
  return <FormDrawer open={editorOpen} title={title} pending={pending} submitDisabled={submitDisabled} size={760} formId="user-editor" onClose={requestClose}>
    {editorOpen && <>
    <FeedbackBubble id="user-editor-error" content={saveError ? t(saveError) : undefined} />
    {saveError && <Alert className="mb-4" type="error" showIcon message={isExisting ? t("userEditorRolledBack") : t(saveError)} description={isExisting ? t(saveError) : undefined} />}
    {refreshFailed && <Alert className="mb-4" type="warning" showIcon message={t("userEditorRefreshFailed")} action={<Button size="small" onClick={() => { setRefreshFailed(false); void refreshAfterSave(); }}>{t("retry")}</Button>} />}
    {!isExisting ? <Form key="create" id="user-editor" form={form} layout="vertical" initialValues={{ enabled: true }} onValuesChange={(_, allValues) => setCreateChanged(!!(allValues.username || allValues.displayName || allValues.password))} onFinish={onFinish}>
      <Form.Item name="username" label={t("username")} rules={[{ required: true, message: t("usernameRequired") }, { pattern: /^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}$/, message: t("invalidUser") }]}><Input disabled={pending} autoComplete="off" /></Form.Item>
      <Form.Item name="displayName" label={t("name")} rules={[{ required: true, whitespace: true, message: t("nameRequired") }, { max: 200, message: t("nameTooLong") }]}><Input maxLength={200} disabled={pending} /></Form.Item>
      <Form.Item name="password" label={t("password")} rules={[{ required: true, message: t("passwordRequired") }, { validator: (_, value) => !value || defaultPasswordLengthValid(value) ? Promise.resolve() : Promise.reject(new Error(t("invalidUser"))) }]}><Input.Password autoComplete="new-password" maxLength={MAX_PASSWORD_BYTES} disabled={pending} /></Form.Item>
    </Form> : detail.error ? <QueryFailure title={t("unavailable")} pending={detail.isFetching} onRetry={() => void detail.refetch()} /> : !detail.data || detail.isFetching ? <Spin /> : <>
      {versionMismatch && <Alert className="mb-4" type="warning" showIcon message={t("staleRecord")} action={<Button size="small" loading={refreshingSnapshots} disabled={pending} onClick={() => void retrySnapshots()}>{t("refreshPage")}</Button>} />}
      {(detail.data.fileQuotaBytesOverride !== null || detail.data.fileQuotaFilesOverride !== null)
        && <Alert className="mb-4" type="warning" showIcon message={t("userLegacyQuotaWarning")} description={`${t("legacyQuotaRoleLabel")}: ${detail.data.fileQuotaBytesOverride === null ? "—" : formatBytes(detail.data.fileQuotaBytesOverride, locale)} · ${detail.data.fileQuotaFilesOverride === null ? "—" : formatCount(detail.data.fileQuotaFilesOverride, locale)}`} />}
      <Form key={detail.data.id} id="user-editor" form={form} layout="vertical" initialValues={{ username: detail.data.username, displayName: detail.data.displayName, enabled: detail.data.enabled }} onValuesChange={() => setFormRevision(value => value + 1)} onFinish={onFinish}>
        <Tabs items={[
          ...(canWrite ? [{ key: "profile", label: t("userBasicInformation"), children: <>
            <Form.Item name="username" label={t("username")}><Input disabled /></Form.Item>
            <Form.Item name="displayName" label={t("name")} rules={[{ required: true, whitespace: true, message: t("nameRequired") }, { max: 200, message: t("nameTooLong") }]}><Input maxLength={200} disabled={pending || committed} /></Form.Item>
            <Form.Item name="enabled" label={t("enabled")} valuePropName="checked"><Switch disabled={pending || committed} /></Form.Item>
          </> }] : []),
          ...(canAssignRoles ? [{ key: "roles", label: t("assignRoles"), children: <>
            {roles.error ? <QueryFailure title={t("unavailable")} pending={roles.isFetching} onRetry={() => void roles.refetch()} /> : !roles.data || roles.isFetching ? <Spin /> : <>
              <Typography.Paragraph>{t("selectedRoles", { count: selectedRoleIds.length })}</Typography.Paragraph>
              <Typography.Paragraph type="secondary">{t("roleAssignmentHint")}</Typography.Paragraph>
              <AssignmentSearch request={rolesRequest} onChange={setRolesRequest} codeLabel={t("roleCode")} nameLabel={t("name")} statusLabel={t("status")} />
              <ResourceTable<LinkedRole> result={rolesList.data} loading={rolesList.isFetching} error={rolesList.error} request={rolesRequest} onChange={setRolesRequest} onRetry={() => void rolesList.refetch()} searchable={false}
                rowSelection={{ selectedRowKeys: selectedRoleIds, preserveSelectedRowKeys: true, onChange: keys => setRoleSelection(keys.map(String)), getCheckboxProps: () => ({ disabled: pending || committed }) }}
                columns={[{ title: t("roleCode"), dataIndex: "code", search: true }, { title: t("name"), dataIndex: "name", search: true },
                  { title: t("roleFileStorageLimit"), dataIndex: "fileQuotaBytesLimit", search: false, render: (_, role) => role.fileQuotaBytesLimit === null ? <Typography.Text type="secondary">—</Typography.Text> : formatBytes(role.fileQuotaBytesLimit, locale) },
                  { title: t("roleFileCountLimit"), dataIndex: "fileQuotaFilesLimit", search: false, render: (_, role) => role.fileQuotaFilesLimit === null ? <Typography.Text type="secondary">—</Typography.Text> : formatCount(role.fileQuotaFilesLimit, locale) },
                  { title: t("status"), dataIndex: "enabled", search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, role) => <Tag color={role.enabled ? "success" : "default"}>{t(role.enabled ? "enabled" : "disabled")}</Tag> }]}
              />
              {quotaPreview.error ? <QueryFailure title={t("unavailable")} pending={quotaPreview.isFetching} onRetry={() => void quotaPreview.refetch()} /> : !quotaPreview.data || quotaPreview.isFetching ? <Spin /> : <div className="mt-4 flex flex-col gap-4">
                <Typography.Text strong>{t("userEffectiveQuotaPreview", { storage: formatBytes(quotaPreview.data.proposedMaximumBytes, locale), files: formatCount(quotaPreview.data.proposedMaximumFiles, locale) })}</Typography.Text>
                <Typography.Text type="secondary">{t("roleFileStorageLimit")}: {formatBytes(quotaPreview.data.currentMaximumBytes, locale)} → {formatBytes(quotaPreview.data.proposedMaximumBytes, locale)} · {t("roleFileCountLimit")}: {formatCount(quotaPreview.data.currentMaximumFiles, locale)} → {formatCount(quotaPreview.data.proposedMaximumFiles, locale)}</Typography.Text>
                <div className="grid gap-4 md:grid-cols-2">
                  <CapacityMeter label={t("fileStorageUsage")} used={quotaPreview.data.chargedBytes} limit={quotaPreview.data.proposedMaximumBytes} format={value => formatBytes(value, locale)} />
                  <CapacityMeter label={t("fileCountUsage")} used={quotaPreview.data.fileCount} limit={quotaPreview.data.proposedMaximumFiles} format={value => formatCount(value, locale)} />
                </div>
                <Typography.Paragraph type="secondary" className="mb-0">{t("roleQuotaImpactHint")}</Typography.Paragraph>
              </div>}
            </>}
          </> }] : []),
          ...(canAssignOrganizations ? [{ key: "organizations", label: t("assignOrganizations"), children: organizations.error
            ? <QueryFailure title={t("unavailable")} pending={organizations.isFetching} onRetry={() => void organizations.refetch()} />
            : !organizations.data || organizations.isFetching ? <Spin /> : <>
              <Typography.Paragraph>{t("selectedOrganizations", { count: selectedOrganizationIds.length })}</Typography.Paragraph>
              <Typography.Paragraph type="secondary">{t("organizationAssignmentHint")}</Typography.Paragraph>
              <AssignmentSearch request={organizationsRequest} onChange={setOrganizationsRequest} codeLabel={t("organizationCode")} nameLabel={t("name")} statusLabel={t("status")} />
              <ResourceTable<LinkedOrganization> result={organizationsList.data} loading={organizationsList.isFetching} error={organizationsList.error} request={organizationsRequest} onChange={setOrganizationsRequest} onRetry={() => void organizationsList.refetch()} searchable={false}
                rowSelection={{ selectedRowKeys: selectedOrganizationIds, preserveSelectedRowKeys: true, onChange: keys => setOrganizationSelection(keys.map(String)), getCheckboxProps: () => ({ disabled: pending || committed }) }}
                columns={[{ title: t("organizationCode"), dataIndex: "code", search: true }, { title: t("name"), dataIndex: "name", search: true }, { title: t("status"), dataIndex: "enabled", search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, organization) => <Tag color={organization.enabled ? "success" : "default"}>{t(organization.enabled ? "enabled" : "disabled")}</Tag> }]}
              />
            </> }] : []),
        ]} />
      </Form>
    </>}
    </>}
  </FormDrawer>;
}

type AssignmentSearchValues = { code?: string; name?: string; enabled?: string };

function AssignmentSearch({ request, onChange, codeLabel, nameLabel, statusLabel }: {
  request: ListRequest;
  onChange: (request: ListRequest) => void;
  codeLabel: string;
  nameLabel: string;
  statusLabel: string;
}) {
  const { t } = usePreferences();
  const [form] = AntForm.useForm<AssignmentSearchValues>();
  const intl = useIntl();

  useEffect(() => {
    form.setFieldsValue({
      code: request.filters?.code,
      name: request.filters?.name,
      enabled: request.filters?.enabled,
    });
  }, [form, request.filters]);

  const search = () => {
    const values = form.getFieldsValue();
    const filters: Record<string, string> = {};
    const filterModes: NonNullable<ListRequest["filterModes"]> = {};
    if (values.code) {
      filters.code = values.code;
      filterModes.code = "contains";
    }
    if (values.name) {
      filters.name = values.name;
      filterModes.name = "contains";
    }
    if (values.enabled) {
      filters.enabled = values.enabled;
      filterModes.enabled = "exact";
    }
    onChange({ ...request, page: 1, search: "", filters, filterModes });
  };

  const reset = () => {
    form.resetFields();
    onChange({ ...request, page: 1, search: "", filters: {}, filterModes: {} });
  };

  const searchOnEnter = (event: React.KeyboardEvent<HTMLInputElement>) => {
    event.preventDefault();
    event.stopPropagation();
    search();
  };

  return <div className="mb-4 grid gap-2" role="search">
    <AntForm form={form} component={false} layout="vertical">
      <div className="grid grid-cols-1 gap-x-4 md:grid-cols-3">
        <AntForm.Item name="code" label={codeLabel}>
          <Input allowClear onPressEnter={searchOnEnter} />
        </AntForm.Item>
        <AntForm.Item name="name" label={nameLabel}>
          <Input allowClear onPressEnter={searchOnEnter} />
        </AntForm.Item>
        <AntForm.Item name="enabled" label={statusLabel}>
          <Select allowClear options={[
            { value: "true", label: t("enabled") },
            { value: "false", label: t("disabled") },
          ]} />
        </AntForm.Item>
      </div>
    </AntForm>
    <div className="flex flex-wrap justify-end gap-2">
      <Button type="primary" htmlType="button" onClick={search}>{intl.getMessage("tableForm.search", "搜索")}</Button>
      <Button htmlType="button" onClick={reset}>{intl.getMessage("tableForm.reset", "重置")}</Button>
    </div>
  </div>;
}

function sameIds(left: string[], right: string[]) {
  return left.length === right.length && [...left].sort().every((id, index) => id === [...right].sort()[index]);
}

function formatBytes(bytes: number, locale: string) {
  const unit = bytes >= 1024 ** 3 ? 3 : bytes >= 1024 ** 2 ? 2 : bytes >= 1024 ? 1 : 0;
  return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(bytes / 1024 ** unit)} ${["B", "KiB", "MiB", "GiB"][unit]}`;
}

function formatCount(value: number, locale: string) {
  return new Intl.NumberFormat(locale).format(value);
}
