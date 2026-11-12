"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { Alert, Button, Form as AntForm, Input, Select, Spin, Switch, Tag, Typography } from "antd";
import { AnchorTabs as Tabs } from "@/platform/components/anchor-tabs";
import { useIntl } from "@ant-design/pro-components";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, mutate } from "@/platform/api/client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
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
import { ProfileFieldRenderer } from "@/platform/profile-fields/profile-field-renderer";
import type { DictionarySelection, ProfileFieldDefinition } from "@/platform/profile-fields/types";
import { formatQuotaBytes, formatQuotaLimitBytes, formatQuotaLimitCount } from "@/platform/i18n/format";
import { AssignmentSearchField, AssignmentSearchLayout } from "./assignment-search-layout";
import { RoleAssignmentSearch } from "./role-assignment-search";
import { buildUserEditorSavePlan } from "./user-editor-save-plan";

const userErrorKeys = ["invalidUser", "duplicateRecord", "staleRecord", "protectedAccount", "forbidden", "invalidRole", "invalidOrganization"] as const satisfies readonly MessageKey[];

type UserDetail = UserSummary;
type Assignment = { version: number; roleIds: string[] };
type Membership = { version: number; organizationIds: string[] };
type LinkedRole = { id: string; code: string; name: string; enabled: boolean; fileQuotaBytesLimit: number | null; fileQuotaFilesLimit: number | null; assignable: boolean };
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
type UserEditValues = { [key: string]: string | boolean | undefined; username?: string; displayName?: string; enabled?: boolean; password?: string; confirmPassword?: string };
export type ProfileValuesSnapshot = {
  version: number;
  schemaVersion: number;
  fields: ProfileFieldDefinition[];
  values: Record<string, string>;
  dictionarySelections: Record<string, DictionarySelection>;
};

export function UserEditor({ user, canCreate, canUpdate, canAssignRoles, canReadRoles, canAssignOrganizations, canViewRolesOnly, onClose, onSaved }: {
  user: UserSummary | null | undefined;
  canCreate: boolean;
  canUpdate: boolean;
  canAssignRoles: boolean;
  canReadRoles: boolean;
  canAssignOrganizations: boolean;
  canViewRolesOnly: boolean;
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
  const {pending,error:saveError,errorMessage:saveErrorMessage,setError:setSaveError,run}=useSubmitAction({errorKeys:userErrorKeys});
  const [committed, setCommitted] = useState(false);
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
  const profileValues = useQuery({
    queryKey: ["user-profile-values", user?.id],
    enabled: isExisting && canUpdate,
    queryFn: ({ signal }) => api<ProfileValuesSnapshot>(`/platform/users/${user!.id}/profile-values`, { signal }),
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
  useEffect(() => {
    if (!profileValues.data || !editorOpen) return;
    form.setFieldsValue(profileValues.data.values);
    setFormRevision(value => value + 1);
  }, [profileValues.data?.version, profileValues.data?.schemaVersion, editorOpen, form]);
  const roles = useQuery({
    queryKey: ["user-assignments", "roles", user?.id],
    enabled: isExisting && canReadRoles,
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
    queryKey: ["user-assignment-role-options", user?.id, rolesRequest],
    enabled: isExisting && canReadRoles,
    queryFn: ({ signal }) => api<PageResult<LinkedRole>>(`/platform/users/${user!.id}/role-options?${listRequestParams(rolesRequest)}`, { signal }),
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
  const profilePlan = isExisting && detail.data
    ? buildUserEditorSavePlan({
      version: detail.data.version,
      baseline: detail.data,
      formValues: values,
      profileValues: profileValues.data,
      canSaveProfile: canUpdate,
    })
    : undefined;
  const profileChanged = !!profilePlan?.profileChanged;
  const profileValuesChanged = !!profilePlan?.profileValuesChanged;
  const rolesChanged = !!(canAssignRoles && roles.data && !sameIds(selectedRoleIds, roles.data.roleIds));
  const organizationsChanged = !!(canAssignOrganizations && organizations.data
    && !sameIds(selectedOrganizationIds, organizations.data.organizationIds));
  const hasChanges = isExisting ? profileChanged || profileValuesChanged || rolesChanged || organizationsChanged : false;
  const isDirty = !committed && (isExisting ? hasChanges : createChanged);

  const versions = [
    detail.data?.version,
    canUpdate ? profileValues.data?.version : undefined,
      canAssignRoles ? roles.data?.version : undefined,
    canAssignOrganizations ? organizations.data?.version : undefined,
    canAssignRoles ? quotaPreview.data?.version : undefined,
  ].filter((version): version is number => version !== undefined);
  const versionMismatch = !!detail.data && versions.some(version => version !== detail.data!.version);
  const roleSaveReady = !rolesChanged || (!!rolesList.data && !rolesList.error && !!quotaPreview.data && !quotaPreview.error);
  const organizationSaveReady = !organizationsChanged || (!!organizationsList.data && !organizationsList.error);
  const profileSaveReady = !canUpdate || (!!profileValues.data && !profileValues.error);
  const submitDisabled = isExisting
    ? !detail.data || !!detail.error || !profileSaveReady || !hasChanges || versionMismatch || !roleSaveReady || !organizationSaveReady || committed
    : !canCreate || committed;

  const retrySnapshots = async () => {
    if (refreshingSnapshots || pending) return;
    setRefreshingSnapshots(true);
    setRoleSelection(null);
    setOrganizationSelection(null);
    setRefreshFailed(false);
    setSaveError(null);
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
      if (canUpdate) await profileValues.refetch({ throwOnError: true });
      await Promise.all([
        canReadRoles ? roles.refetch({ throwOnError: true }) : Promise.resolve(undefined),
        canReadRoles ? rolesList.refetch({ throwOnError: true }) : Promise.resolve(undefined),
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
    const currentStorageRank = preview.currentMaximumBytes === 0 ? Number.POSITIVE_INFINITY : preview.currentMaximumBytes;
    const proposedStorageRank = preview.proposedMaximumBytes === 0 ? Number.POSITIVE_INFINITY : preview.proposedMaximumBytes;
    const currentFileCountRank = preview.currentMaximumFiles === 0 ? Number.POSITIVE_INFINITY : preview.currentMaximumFiles;
    const proposedFileCountRank = preview.proposedMaximumFiles === 0 ? Number.POSITIVE_INFINITY : preview.proposedMaximumFiles;
    const decrease = proposedStorageRank < currentStorageRank
      || proposedFileCountRank < currentFileCountRank;
    const increase = proposedStorageRank > currentStorageRank
      || proposedFileCountRank > currentFileCountRank;
    const rows = [
      storageChanged && `${t("roleFileStorageLimit")}: ${formatQuotaLimitBytes(preview.currentMaximumBytes,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})} → ${formatQuotaLimitBytes(preview.proposedMaximumBytes,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})}`,
      countChanged && `${t("roleFileCountLimit")}: ${formatQuotaLimitCount(preview.currentMaximumFiles,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})} → ${formatQuotaLimitCount(preview.proposedMaximumFiles,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})}`,
      decrease && t("userQuotaDecreaseConfirm"),
      increase && t("userQuotaIncreaseConfirm"),
    ].filter(Boolean);
    return new Promise<boolean>(resolve => confirm({
      title: t("userEffectiveQuotaPreview", { storage: formatQuotaLimitBytes(preview.proposedMaximumBytes,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")}), files: formatQuotaLimitCount(preview.proposedMaximumFiles,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")}) }),
      content: <div className="flex flex-col gap-2">{rows.map((row, index) => <Typography.Paragraph key={index} className="mb-0">{row}</Typography.Paragraph>)}</div>,
      okText: t("confirm"),
      cancelText: t("cancel"),
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    }));
  };

  const onFinish = async (submitted: UserEditValues) => {
    if (pending || committed) return false;
    setRefreshFailed(false);
    return run(async () => {
      if (!isExisting) {
        if (!canCreate) return false;
        await mutate("/platform/users", "POST", {
          username: submitted.username,
          displayName: submitted.displayName,
          password: submitted.password,
        });
      } else {
        if (!detail.data || versionMismatch) return false;
        const rolesChangedNow = canAssignRoles && !!roles.data && !sameIds(selectedRoleIds, roles.data.roleIds);
        const organizationsChangedNow = canAssignOrganizations && !!organizations.data
          && !sameIds(selectedOrganizationIds, organizations.data.organizationIds);
        const savePlan = buildUserEditorSavePlan({
          version: detail.data.version,
          baseline: detail.data,
          // Tabs may not have mounted every field. Read the full Form store and
          // let the opening snapshots supply untouched values for unmounted fields.
          formValues: form.getFieldsValue(true),
          profileValues: profileValues.data,
          canSaveProfile: canUpdate,
          roleIds: rolesChangedNow ? selectedRoleIds : undefined,
          organizationIds: organizationsChangedNow ? selectedOrganizationIds : undefined,
        });
        if (!savePlan.hasChanges) return false;
        if (selectedRoleIds.length > 100 || selectedOrganizationIds.length > 100) {
          setSaveError(selectedRoleIds.length > 100 ? "invalidRole" : "invalidOrganization");
          return false;
        }
        if (rolesChangedNow) {
          if (!quotaPreview.data || quotaPreview.error) return false;
          if (!await confirmQuotaPolicyChange(quotaPreview.data)) return false;
        }
        await mutate(`/platform/users/${user!.id}/editor`, "PUT", savePlan.payload);
      }
      setCommitted(true);
      await refreshAfterSave();
      return true;
    });
  };

  const title =
    user ? `${t(canViewRolesOnly ? "permissionReadRoles" : "edit")} · ${user.displayName}` : t("createUser");
  return <FormDrawer open={editorOpen} title={title} pending={pending} submitDisabled={submitDisabled} size={760} formId="user-editor" onClose={requestClose}>
    {editorOpen && <>
    <FeedbackBubble id="user-editor-error" content={saveErrorMessage} />
    {saveError && <Alert className="mb-4" type="error" showIcon message={isExisting ? t("userEditorRolledBack") : saveErrorMessage} description={isExisting ? saveErrorMessage : undefined} />}
    {refreshFailed && <Alert className="mb-4" type="warning" showIcon message={t("userEditorRefreshFailed")} action={<Button size="small" onClick={() => { setRefreshFailed(false); void refreshAfterSave(); }}>{t("retry")}</Button>} />}
    {!isExisting ? <Form key="create" name="user-editor" id="user-editor" form={form} layout="vertical" initialValues={{ enabled: true }} onValuesChange={(_, allValues) => setCreateChanged(!!(allValues.username || allValues.displayName || allValues.password || allValues.confirmPassword))} onFinish={onFinish}>
      <Form.Item name="username" label={t("username")} rules={[{ required: true, message: t("usernameRequired") }, { pattern: /^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}$/, message: t("invalidUser") }]}><Input disabled={pending || !canCreate} autoComplete="off" /></Form.Item>
      <Form.Item name="displayName" label={t("name")} rules={[{ required: true, whitespace: true, message: t("nameRequired") }, { max: 200, message: t("nameTooLong") }]}><Input maxLength={200} disabled={pending || !canCreate} /></Form.Item>
      <Form.Item name="password" label={t("password")} rules={[{ required: true, message: t("passwordRequired") }, { validator: (_, value) => !value || defaultPasswordLengthValid(value) ? Promise.resolve() : Promise.reject(new Error(t("invalidUser"))) }]}><Input.Password autoComplete="new-password" maxLength={MAX_PASSWORD_BYTES} disabled={pending || !canCreate} /></Form.Item>
      <Form.Item name="confirmPassword" label={t("confirmPassword")} dependencies={["password"]} rules={[{ required: true, message: t("passwordRequired") }, { validator: (_, value) => !value || value === form.getFieldValue("password") ? Promise.resolve() : Promise.reject(new Error(t("passwordMismatch"))) }]}><Input.Password autoComplete="new-password" maxLength={MAX_PASSWORD_BYTES} disabled={pending || !canCreate} /></Form.Item>
    </Form> : detail.error ? <QueryFailure title={t("unavailable")} pending={detail.isFetching} onRetry={() => void detail.refetch()} /> : !detail.data || detail.isFetching ? <Spin /> : <>
      {versionMismatch && <Alert className="mb-4" type="warning" showIcon message={t("staleRecord")} action={<Button size="small" loading={refreshingSnapshots} disabled={pending} onClick={() => void retrySnapshots()}>{t("refreshPage")}</Button>} />}
      {(detail.data.fileQuotaBytesOverride !== null || detail.data.fileQuotaFilesOverride !== null)
        && <Alert className="mb-4" type="warning" showIcon message={t("userLegacyQuotaWarning")} description={`${t("legacyQuotaRoleLabel")}: ${formatQuotaLimitBytes(detail.data.fileQuotaBytesOverride,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})} · ${formatQuotaLimitCount(detail.data.fileQuotaFilesOverride,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})}`} />}
      <Form key={detail.data.id} name="user-editor" id="user-editor" form={form} layout="vertical" initialValues={{ username: detail.data.username, displayName: detail.data.displayName, enabled: detail.data.enabled }} onValuesChange={() => setFormRevision(value => value + 1)} onFinish={onFinish}>
        <Tabs anchor="user-editor" items={[
          ...(canUpdate ? [{ key: "profile", label: t("userBasicInformation"), children: <>
            <Form.Item name="username" label={t("username")}><Input disabled /></Form.Item>
            <Form.Item name="displayName" label={t("name")} rules={[{ required: true, whitespace: true, message: t("nameRequired") }, { max: 200, message: t("nameTooLong") }]}><Input maxLength={200} disabled={pending || committed} /></Form.Item>
            <Form.Item name="enabled" label={t("enabled")} valuePropName="checked"><Switch disabled={pending || committed} /></Form.Item>
            {profileValues.error
              ? <QueryFailure title={t("unavailable")} pending={profileValues.isFetching} onRetry={() => void profileValues.refetch()} />
              : !profileValues.data || profileValues.isFetching
                ? <Spin />
                : <ProfileFieldRenderer
                    dictionaryEndpointPrefix={`/platform/users/${detail.data.id}/profile-dictionaries`}
                    fields={profileValues.data.fields}
                    disabled={pending || committed}
                    dictionarySelections={profileValues.data.dictionarySelections}
                  />}
          </> }] : []),
      ...(canReadRoles ? [{ key: "roles", label: t(canAssignRoles ? "assignRoles" : "permissionReadRoles"), children: <>
            {roles.error ? <QueryFailure title={t("unavailable")} pending={roles.isFetching} onRetry={() => void roles.refetch()} /> : !roles.data || roles.isFetching ? <Spin /> : <>
              <Typography.Paragraph>{t("selectedRoles", { count: selectedRoleIds.length })}</Typography.Paragraph>
              {canAssignRoles && <Typography.Paragraph type="secondary">{t("roleAssignmentHint")}</Typography.Paragraph>}
              <RoleAssignmentSearch userId={user.id} request={rolesRequest} onChange={setRolesRequest} codeLabel={t("roleCode")} nameLabel={t("name")} statusLabel={t("status")} />
              <ResourceTable<LinkedRole> result={rolesList.data} loading={rolesList.isFetching} error={rolesList.error} request={rolesRequest} onChange={setRolesRequest} onRetry={() => void rolesList.refetch()} searchable={false} scroll={{ x: 640 }}
                rowSelection={{ columnWidth: 40, selectedRowKeys: selectedRoleIds, preserveSelectedRowKeys: true, onChange: keys => setRoleSelection(keys.map(String)), getCheckboxProps: role => ({ disabled: pending || committed || !canAssignRoles || !role.assignable }) }}
                columns={[{ title: t("roleCode"), dataIndex: "code", width: 104, ellipsis: { showTitle: true }, search: true }, { title: t("name"), dataIndex: "name", width: 120, ellipsis: { showTitle: true }, search: true },
                  { title: t("roleFileStorageLimit"), dataIndex: "fileQuotaBytesLimit", width: 152, search: false, render: (_, role) => formatQuotaLimitBytes(role.fileQuotaBytesLimit,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")}) },
                  { title: t("roleFileCountLimit"), dataIndex: "fileQuotaFilesLimit", width: 152, search: false, render: (_, role) => formatQuotaLimitCount(role.fileQuotaFilesLimit,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")}) },
                  { title: t("status"), dataIndex: "enabled", width: 72, search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, role) => <Tag color={role.enabled ? "success" : "default"}>{t(role.enabled ? "enabled" : "disabled")}</Tag> }]}
              />
              {quotaPreview.error ? <QueryFailure title={t("unavailable")} pending={quotaPreview.isFetching} onRetry={() => void quotaPreview.refetch()} /> : !quotaPreview.data || quotaPreview.isFetching ? <Spin /> : <div className="mt-4 flex flex-col gap-4">
                <div className="grid gap-4 md:grid-cols-2">
                  <CapacityMeter label={t("fileStorageUsage")} used={quotaPreview.data.chargedBytes} limit={quotaPreview.data.proposedMaximumBytes} format={value => formatQuotaBytes(value, locale)} unlimitedWhenZero unlimitedLabel={t("roleQuotaUnlimited")} />
                  <CapacityMeter label={t("fileCountUsage")} used={quotaPreview.data.fileCount} limit={quotaPreview.data.proposedMaximumFiles} format={value => formatCount(value, locale)} unlimitedWhenZero unlimitedLabel={t("roleQuotaUnlimited")} />
                </div>
              </div>}
            </>}
          </> }] : []),
          ...(canAssignOrganizations ? [{ key: "organizations", label: t("assignOrganizations"), children: organizations.error
            ? <QueryFailure title={t("unavailable")} pending={organizations.isFetching} onRetry={() => void organizations.refetch()} />
            : !organizations.data || organizations.isFetching ? <Spin /> : <>
              <Typography.Paragraph>{t("selectedOrganizations", { count: selectedOrganizationIds.length })}</Typography.Paragraph>
              <Typography.Paragraph type="secondary">{t("organizationAssignmentHint")}</Typography.Paragraph>
              <AssignmentSearch request={organizationsRequest} onChange={setOrganizationsRequest} codeLabel={t("organizationCode")} nameLabel={t("name")} statusLabel={t("status")} />
              <ResourceTable<LinkedOrganization> result={organizationsList.data} loading={organizationsList.isFetching} error={organizationsList.error} request={organizationsRequest} onChange={setOrganizationsRequest} onRetry={() => void organizationsList.refetch()} searchable={false} scroll={{ x: 480 }}
                rowSelection={{ columnWidth: 40, selectedRowKeys: selectedOrganizationIds, preserveSelectedRowKeys: true, onChange: keys => setOrganizationSelection(keys.map(String)), getCheckboxProps: () => ({ disabled: pending || committed }) }}
                columns={[{ title: t("organizationCode"), dataIndex: "code", width: 160, ellipsis: { showTitle: true }, search: true }, { title: t("name"), dataIndex: "name", ellipsis: { showTitle: true }, search: true }, { title: t("status"), dataIndex: "enabled", width: 88, search: true, valueEnum: { true: { text: t("enabled") }, false: { text: t("disabled") } }, render: (_, organization) => <Tag color={organization.enabled ? "success" : "default"}>{t(organization.enabled ? "enabled" : "disabled")}</Tag> }]}
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
  const codeInputId = useId();
  const nameInputId = useId();
  const statusInputId = useId();

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

  return <AntForm form={form} component={false} layout="vertical">
    <AssignmentSearchLayout actions={<>
      <Button htmlType="button" onClick={reset}>{intl.getMessage("tableForm.reset", "重置")}</Button>
      <Button type="primary" htmlType="button" onClick={search}>{intl.getMessage("tableForm.search", "搜索")}</Button>
    </>}>
      <AssignmentSearchField label={codeLabel} inputId={codeInputId}>
        <AntForm.Item name="code" noStyle><Input id={codeInputId} allowClear onPressEnter={searchOnEnter} /></AntForm.Item>
      </AssignmentSearchField>
      <AssignmentSearchField label={nameLabel} inputId={nameInputId}>
        <AntForm.Item name="name" noStyle><Input id={nameInputId} allowClear onPressEnter={searchOnEnter} /></AntForm.Item>
      </AssignmentSearchField>
      <AssignmentSearchField label={statusLabel} inputId={statusInputId}>
        <AntForm.Item name="enabled" noStyle><Select id={statusInputId} aria-labelledby={`${statusInputId}-label`} allowClear options={[
          { value: "true", label: t("enabled") },
          { value: "false", label: t("disabled") },
        ]} /></AntForm.Item>
      </AssignmentSearchField>
    </AssignmentSearchLayout>
  </AntForm>;
}

function sameIds(left: string[], right: string[]) {
  return left.length === right.length && [...left].sort().every((id, index) => id === [...right].sort()[index]);
}

function formatCount(value: number, locale: string) {
  return new Intl.NumberFormat(locale).format(value);
}
