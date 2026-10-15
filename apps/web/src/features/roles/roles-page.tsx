"use client";
import { useEffect, useRef } from "react";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {Button, Card, Input, InputNumber, Spin, Switch, Tag, Select, Space, Typography} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api, isRolePreviewReadOnly, mutate } from "@/platform/api/client";
import { useCrudDrawer } from "@/platform/forms/use-crud-drawer";
import { saveRecord, pageAfterRemoval } from "@/platform/api/records";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { DeleteAction } from "@/platform/components/delete-action";
import { FormDrawer } from "@/platform/components/form-drawer";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { useListRequest } from "@/platform/routing/use-list-request";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";

const roleErrorKeys = ["staleRecord", "protectedAccount", "forbidden", "invalidRole", "invalidFileQuota"] as const satisfies readonly MessageKey[];
const roleErrorAliases = { duplicateRecord: "duplicateRole" } as const satisfies Readonly<Record<string, MessageKey>>;
import { QueryFailure } from "@/platform/components/query-failure";
import { listRequestParams } from "@/platform/api/pagination";
import { useCenteredModalConfirm } from "@/platform/components/use-centered-modal-confirm";
import { RoleAuthenticationEditor } from "./role-authentication-editor";
import { RolePermissionPicker } from "./role-permission-picker";
import type { PermissionCatalogGroup } from "./role-permission-model";
import { formatQuotaLimitBytes, formatQuotaLimitCount } from "@/platform/i18n/format";

import type { Role } from "./types";

const GIB = 1024 ** 3;
const MAX_SAFE_QUOTA = Number.MAX_SAFE_INTEGER;

export function RolesPage({canCreate,canUpdate,canDelete,canManagePluginPermissions=false,authorities,canManageAuthenticationPolicy=false,canReadAuthenticationPolicy=false,canReadRegistrationSettings=false,canReadFileQuota=false,readOnlyPreview=false}:{canCreate:boolean;canUpdate:boolean;canDelete:boolean;canManagePluginPermissions?:boolean;authorities:string[];canManageAuthenticationPolicy?:boolean;canReadAuthenticationPolicy?:boolean;canReadRegistrationSettings?:boolean;canReadFileQuota?:boolean;readOnlyPreview?:boolean}) {
  const previewReadOnly = readOnlyPreview || isRolePreviewReadOnly();
  const effectiveCanCreate = canCreate && !previewReadOnly;
  const effectiveCanUpdate = canUpdate && !previewReadOnly;
  const effectiveCanDelete = canDelete && !previewReadOnly;
  const effectiveCanManageAuthenticationPolicy = canManageAuthenticationPolicy && !previewReadOnly;
  const effectiveCanReadAuthenticationPolicy = (canReadAuthenticationPolicy || canManageAuthenticationPolicy) && !previewReadOnly;
  const effectiveCanReadRegistrationSettings = canReadRegistrationSettings && !previewReadOnly;
  const {t,locale}=usePreferences();
  const confirm=useCenteredModalConfirm();
  const client=useQueryClient();
  const [request,setRequest]=useListRequest();
  const {selected,setSelected,pending,error,close,saved,run}=useCrudDrawer<string>({errorKeys:roleErrorKeys,errorAliases:roleErrorAliases});
  const previousPreviewReadOnly = useRef(previewReadOnly);
  useEffect(() => {
    const enteringPreview = previewReadOnly && !previousPreviewReadOnly.current;
    previousPreviewReadOnly.current = previewReadOnly;
    if (enteringPreview && selected !== undefined) close();
  }, [previewReadOnly, selected, close]);
  const query=useQuery({queryKey:["roles",request],queryFn:({signal})=>api<PageResult<Role>>(`/platform/roles?${listRequestParams(request)}`,{signal})});
  const detail=useQuery({queryKey:["role",selected],enabled:typeof selected==="string",queryFn:({signal})=>api<Role>(`/platform/roles/${selected}`,{signal}),staleTime:0});
  const permissionCatalog=useQuery({queryKey:["permission-catalog"],enabled:selected!==undefined,queryFn:({signal})=>api<PermissionCatalogGroup[]>("/platform/permission-catalog",{signal}),staleTime:0,refetchOnWindowFocus:true,refetchInterval:30_000});
  const editableRole = !!selected ? effectiveCanUpdate : effectiveCanCreate;

  return <>
    <ResourceTable<Role> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()}
      actions={(effectiveCanReadRegistrationSettings || effectiveCanCreate) && <Space>
        {effectiveCanReadRegistrationSettings && <Button href="/dashboard/registration">{t("registrationSettingsTitle")}</Button>}
        {effectiveCanCreate && <Button type="primary" onClick={()=>setSelected(null)}>{t("createRole")}</Button>}
      </Space>}
      columns={[{title:t("roleCode"),dataIndex:"code",search:true},{title:t("name"),dataIndex:"name", search: true,render:(_,role)=><Space wrap><Typography.Text>{role.name}</Typography.Text>{role.defaultRegistrationRole && <Tag color="processing">{t("registrationDefaultRole")}</Tag>}</Space>},{title:t("roleMemberStorageLimit"),dataIndex:"fileQuotaBytesLimit",search:false,render:(_,r)=>formatQuotaLimitBytes(r.fileQuotaBytesLimit,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})},{title:t("roleMemberFileCountLimit"),dataIndex:"fileQuotaFilesLimit",search:false,render:(_,r)=>formatQuotaLimitCount(r.fileQuotaFilesLimit,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})},{title:t("status"),dataIndex:"enabled", search: true,valueEnum:{true:{text:t("enabled")},false:{text:t("disabled")}},render:(_,r)=><Tag color={r.enabled?"success":"default"}>{t(r.enabled?"enabled":"disabled")}</Tag>},
        {title:t("actions"), key: "actions",search:false,render:(_,r)=><TableActions><Button type="link" onClick={()=>setSelected(r.id)}>{t(effectiveCanUpdate?"edit":"roleDetails")}</Button>{effectiveCanDelete && <DeleteAction name={r.name} onDelete={async()=>{
          await mutate(`/platform/roles/${r.id}?version=${r.version}`,"DELETE",{});
          setRequest(pageAfterRemoval(request,query.data?.items.length));
          await client.invalidateQueries({queryKey:["roles"]});client.removeQueries({queryKey:["role",r.id]});
        }}/>}</TableActions>}]} />
    <FormDrawer open={selected!==undefined} title={t(selected?(effectiveCanUpdate?"edit":"roleDetails"):"createRole")} pending={pending} size={760} submitDisabled={!editableRole || !permissionCatalog.data || (!!selected && (!detail.data || detail.isFetching))} formId="role-editor" onClose={close}>
      <FeedbackBubble id="role-editor-error" content={error ? t(error) : undefined} />
      {permissionCatalog.error || (selected && detail.error) ? <QueryFailure title={t("unavailable")} pending={permissionCatalog.isFetching || !!(selected && detail.isFetching)} onRetry={()=>{void permissionCatalog.refetch();void detail.refetch();}} /> : permissionCatalog.isPending || (selected && (detail.isPending || detail.isFetching)) ? <Spin/> :
      <Form key={selected ?? "create"} clearOnDestroy name="role-editor" id="role-editor" layout="vertical" disabled={!editableRole || pending} initialValues={selected?roleFormValues(detail.data):{enabled:true,authorities:[],dataScope:"SELF",fileQuotaStorageGiB:null,fileQuotaFilesLimit:null}} onFinish={values=>run(async()=>{

          const {fileQuotaStorageGiB,fileQuotaFilesLimit,...roleValues}=values;
          const fileQuotaBytesLimit=fileQuotaStorageGiB==null?null:Math.round(fileQuotaStorageGiB*GIB);
          const currentRole=detail.data;
          const quotaPolicyChanged=!!selected&&!!currentRole&&(
            currentRole.fileQuotaBytesLimit!==fileQuotaBytesLimit
            ||currentRole.fileQuotaFilesLimit!==(fileQuotaFilesLimit??null)
            ||currentRole.enabled!==roleValues.enabled
          );
          if(quotaPolicyChanged&&currentRole){
            const changes=[
              currentRole.fileQuotaBytesLimit!==fileQuotaBytesLimit&&`${t("roleMemberStorageLimit")}: ${formatQuotaLimitBytes(currentRole.fileQuotaBytesLimit,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})} → ${formatQuotaLimitBytes(fileQuotaBytesLimit,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})}`,
              currentRole.fileQuotaFilesLimit!==(fileQuotaFilesLimit??null)&&`${t("roleMemberFileCountLimit")}: ${formatQuotaLimitCount(currentRole.fileQuotaFilesLimit,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})} → ${formatQuotaLimitCount(fileQuotaFilesLimit??null,locale,{unlimited:t("roleQuotaUnlimited"),inherited:t("roleQuotaInherit")})}`,
              currentRole.enabled!==roleValues.enabled&&`${t("enabled")}: ${t(currentRole.enabled?"enabled":"disabled")} → ${t(roleValues.enabled?"enabled":"disabled")}`,
            ].filter(Boolean);
            const confirmed=await new Promise<boolean>(resolve=>confirm({
              title:t("fileQuotaAllocation"),
              content:<div className="flex flex-col gap-2"><Typography.Paragraph>{t("roleQuotaImpactHint")}</Typography.Paragraph>{changes.map((change,index)=><Typography.Paragraph key={index} className="mb-0">{change}</Typography.Paragraph>)}</div>,
              okText:t("confirm"),cancelText:t("cancel"),
              onOk:()=>resolve(true),onCancel:()=>resolve(false),
            }));
            if(!confirmed)return false;
          }
          await saveRecord("/platform/roles",selected,{...roleValues,authorities:[...new Set(roleValues.authorities ?? [])],fileQuotaBytesLimit,fileQuotaFilesLimit:fileQuotaFilesLimit??null,version:detail.data?.version});
          saved();await Promise.all([
            client.invalidateQueries({queryKey:["roles"]}),
            client.invalidateQueries({queryKey:["session"]}),
            client.invalidateQueries({queryKey:["user-file-quota-preview"]}),
            client.invalidateQueries({queryKey:["files","usage"]}),
          ]);

      })}>
        <Form.Item name="code" label={t("roleCode")} rules={[{required:true,pattern:/^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}$/,message:t("invalidRole")}]}><Input disabled={!!selected || !editableRole} maxLength={100}/></Form.Item>
        <Form.Item name="name" label={t("name")} rules={[{required:true,whitespace:true,message:t("nameRequired")},{max:200,message:t("nameTooLong")}]}><Input maxLength={200}/></Form.Item>
        <Form.Item name="enabled" label={t("enabled")} valuePropName="checked"><Switch/></Form.Item>
        <Form.Item name="dataScope" label={t("dataScope")} extra={t("dataScopeHint")}><Select options={[{value:"SELF",label:t("scopeSelf")},{value:"ORGANIZATION",label:t("scopeOrganization")},{value:"SUBTREE",label:t("scopeSubtree")},{value:"ALL",label:t("scopeAll")}]} getPopupContainer={trigger=>trigger.parentElement ?? document.body}/></Form.Item>
        <Card size="small" title={t("fileQuotaAllocation")} extra={canReadFileQuota && <Button type="link" size="small" href="/dashboard/files-all#tab=file-quota">{t("fileQuotaDefaultSettings")}</Button>} className="mb-4">
          <Form.Item name="fileQuotaStorageGiB" label={t("roleMemberStorageLimit")} extra={t("roleStorageQuotaHint")} rules={[{ type: "number", min: 0, max: MAX_SAFE_QUOTA/GIB, message: t("invalidRole") }]}>
            <InputNumber min={0} max={MAX_SAFE_QUOTA/GIB} precision={3} step={0.25} suffix="GiB" style={{width:"100%"}} />
          </Form.Item>
          <Form.Item name="fileQuotaFilesLimit" label={t("roleMemberFileCountLimit")} extra={t("roleFileCountQuotaHint")} rules={[{ type: "integer", min: 0, max: MAX_SAFE_QUOTA, message: t("invalidRole") }]}>
            <InputNumber min={0} max={MAX_SAFE_QUOTA} precision={0} style={{width:"100%"}} />
          </Form.Item>
        </Card>
        <Form.Item name="authorities" label={t("permissions")} extra={t("permissionPageTreeHint")}>
          <RolePermissionPicker groups={permissionCatalog.data ?? []} actorAuthorities={authorities} canEdit={editableRole} canManagePluginPermissions={canManagePluginPermissions} t={t} />
        </Form.Item>
      </Form>}
      {selected && detail.data && effectiveCanReadAuthenticationPolicy && <RoleAuthenticationEditor role={detail.data} canWrite={effectiveCanManageAuthenticationPolicy} />}
    </FormDrawer>
  </>;
}

function roleFormValues(role:Role|undefined){return role?{...role,fileQuotaStorageGiB:role.fileQuotaBytesLimit===null?null:role.fileQuotaBytesLimit/GIB,fileQuotaFilesLimit:role.fileQuotaFilesLimit}:undefined;}
