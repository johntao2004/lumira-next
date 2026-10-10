"use client";
import { useEffect, useRef } from "react";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {Button, Card, Checkbox, Col, Input, InputNumber, Row, Spin, Switch, Tag, Select, Space, Typography} from "antd";
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
import { formatQuotaBytes } from "@/platform/i18n/format";

import type { Role } from "./types";

type RolePermissionSection = {
  id: string;
  title: MessageKey;
  resources: readonly string[];
  pages: readonly MessageKey[];
};

type RolePermissionGroup = RolePermissionSection & { permissions: string[] };
const GIB = 1024 ** 3;
const MAX_SAFE_QUOTA = Number.MAX_SAFE_INTEGER;

const rolePermissionSections = [
  { id: "users", title: "userManagement", resources: ["platform:users"], pages: ["userList", "onlineUsers"] },
  { id: "roles", title: "roles", resources: ["platform:roles"], pages: ["roles"] },
  { id: "organizations", title: "organizations", resources: ["platform:organizations"], pages: ["organizations"] },
  { id: "dictionaries", title: "dictionaries", resources: ["platform:dictionaries"], pages: ["dictionaries"] },
  { id: "files", title: "allFiles", resources: ["platform:files"], pages: ["allFiles"] },
  { id: "notifications", title: "notificationCenter", resources: ["platform:notifications"], pages: ["notificationCenter"] },
  { id: "modules", title: "modules", resources: ["platform:modules"], pages: ["modules"] },
  {
    id: "settings",
    title: "systemSettings",
    resources: ["platform:settings"],
    pages: ["personalizationSettings", "messageChannels", "smsSettings", "profileFields", "agreements", "watermark", "maintenance", "securitySettings", "authenticationMethods", "passkeySettings", "fileRetention", "aiConsole", "aiModels"],
  },
  {
    id: "operations",
    title: "operationsCenter",
    resources: ["platform:audit", "platform:events"],
    pages: ["monitoring", "audit", "deliveryRecords", "smsDeliveries", "eventOutbox"],
  },
  { id: "feedback", title: "feedback", resources: ["platform:feedback"], pages: ["feedback"] },
  { id: "navigation", title: "navigationManagement", resources: ["platform:navigation"], pages: ["navigationManagement"] },
  { id: "sensitive-words", title: "sensitiveWords", resources: ["platform:sensitive-words"], pages: ["sensitiveWords"] },
  { id: "localization", title: "localizationManagement", resources: ["platform:localization"], pages: ["localizationManagement"] },
] as const satisfies readonly RolePermissionSection[];

const specificPermissionLabels: Readonly<Record<string, MessageKey>> = {
  "platform:roles:preview": "rolePreview",
  "platform:mfa:manage": "mfaManagement",
  "platform:events:write": "eventReplay",
  "platform:feedback:create": "createFeedback",
  "platform:feedback:manage": "processFeedback",
  "platform:sensitive-words:import": "importSensitiveWords",
  "platform:localization:import": "importLocalizationEntries",
};

function buildRolePermissionGroups(permissions: readonly string[]): RolePermissionGroup[] {
  const knownResources = rolePermissionSections.flatMap((section) => section.resources);
  const groups: RolePermissionGroup[] = rolePermissionSections.map((section) => ({
    ...section,
    permissions: permissions
      .filter((permission) => section.resources.some((resource) => permission.startsWith(`${resource}:`)))
      .sort((left, right) => Number(!left.endsWith(":read")) - Number(!right.endsWith(":read"))),
  })).filter((section) => section.permissions.length > 0);
  const ungrouped = permissions.filter((permission) => !knownResources.some((resource) => permission.startsWith(`${resource}:`)));
  if (ungrouped.length) groups.push({ id: "other", title: "permissions", resources: [], pages: [], permissions: ungrouped });
  return groups;
}

function rolePermissionLabel(permission: string): MessageKey | null {
  return specificPermissionLabels[permission]
    ?? (permission.endsWith(":read") ? "pageAccess" : permission.endsWith(":write") ? "permissionWrite" : null);
}

export function RolesPage({canWrite,authorities,canManageAuthenticationPolicy=false,canReadRegistrationSettings=false,readOnlyPreview=false}:{canWrite:boolean;authorities:string[];canManageAuthenticationPolicy?:boolean;canReadRegistrationSettings?:boolean;readOnlyPreview?:boolean}) {
  const previewReadOnly = readOnlyPreview || isRolePreviewReadOnly();
  const effectiveCanWrite = canWrite && !previewReadOnly;
  const effectiveCanManageAuthenticationPolicy = canManageAuthenticationPolicy && !previewReadOnly;
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
  const permissions=useQuery({queryKey:["permissions"],enabled:selected!==undefined,queryFn:({signal})=>api<string[]>("/platform/permissions",{signal})});

  return <>
    <ResourceTable<Role> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()}
      actions={effectiveCanWrite && <Button type="primary" onClick={()=>setSelected(null)}>{t("createRole")}</Button>}
      columns={[{title:t("roleCode"),dataIndex:"code",search:true},{title:t("name"),dataIndex:"name", search: true,render:(_,role)=><Space wrap><Typography.Text>{role.name}</Typography.Text>{role.defaultRegistrationRole && <Tag color="processing">{t("registrationDefaultRole")}</Tag>}{role.defaultRegistrationRole && effectiveCanReadRegistrationSettings && <Button type="link" size="small" href="/dashboard/registration">{t("registrationSettingsTitle")}</Button>}</Space>},{title:t("roleMemberStorageLimit"),dataIndex:"fileQuotaBytesLimit",search:false,render:(_,r)=>r.fileQuotaBytesLimit===null?<Typography.Text type="secondary">—</Typography.Text>:formatQuotaBytes(r.fileQuotaBytesLimit,locale)},{title:t("roleMemberFileCountLimit"),dataIndex:"fileQuotaFilesLimit",search:false,render:(_,r)=>r.fileQuotaFilesLimit===null?<Typography.Text type="secondary">—</Typography.Text>:new Intl.NumberFormat(locale).format(r.fileQuotaFilesLimit)},{title:t("status"),dataIndex:"enabled", search: true,valueEnum:{true:{text:t("enabled")},false:{text:t("disabled")}},render:(_,r)=><Tag color={r.enabled?"success":"default"}>{t(r.enabled?"enabled":"disabled")}</Tag>},
        {title:t("actions"), key: "actions",search:false,render:(_,r)=><TableActions><Button type="link" onClick={()=>setSelected(r.id)}>{t(effectiveCanWrite?"edit":"roleDetails")}</Button>{effectiveCanWrite && <DeleteAction name={r.name} onDelete={async()=>{
          await mutate(`/platform/roles/${r.id}?version=${r.version}`,"DELETE",{});
          setRequest(pageAfterRemoval(request,query.data?.items.length));
          await client.invalidateQueries({queryKey:["roles"]});client.removeQueries({queryKey:["role",r.id]});
        }}/>}</TableActions>}]} />
    <FormDrawer open={selected!==undefined} title={t(selected?"roleDetails":"createRole")} pending={pending} size={760} submitDisabled={!effectiveCanWrite || !permissions.data || (!!selected && (!detail.data || detail.isFetching))} formId="role-editor" onClose={close}>
      <FeedbackBubble id="role-editor-error" content={error ? t(error) : undefined} />
      {permissions.error || (selected && detail.error) ? <QueryFailure title={t("unavailable")} pending={permissions.isFetching || !!(selected && detail.isFetching)} onRetry={()=>{void permissions.refetch();void detail.refetch();}} /> : permissions.isPending || (selected && (detail.isPending || detail.isFetching)) ? <Spin/> :
      <Form key={selected ?? "create"} name="role-editor" id="role-editor" layout="vertical" disabled={!effectiveCanWrite || pending} initialValues={selected?roleFormValues(detail.data):{enabled:true,authorities:[],dataScope:"SELF",fileQuotaStorageGiB:null,fileQuotaFilesLimit:null}} onFinish={values=>run(async()=>{

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
              currentRole.fileQuotaBytesLimit!==fileQuotaBytesLimit&&`${t("roleMemberStorageLimit")}: ${currentRole.fileQuotaBytesLimit===null?t("roleQuotaUnrestrictedHint"):formatQuotaBytes(currentRole.fileQuotaBytesLimit,locale)} → ${fileQuotaBytesLimit===null?t("roleQuotaUnrestrictedHint"):formatQuotaBytes(fileQuotaBytesLimit,locale)}`,
              currentRole.fileQuotaFilesLimit!==(fileQuotaFilesLimit??null)&&`${t("roleMemberFileCountLimit")}: ${currentRole.fileQuotaFilesLimit===null?t("roleQuotaUnrestrictedHint"):new Intl.NumberFormat(locale).format(currentRole.fileQuotaFilesLimit)} → ${fileQuotaFilesLimit==null?t("roleQuotaUnrestrictedHint"):new Intl.NumberFormat(locale).format(fileQuotaFilesLimit)}`,
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
          await saveRecord("/platform/roles",selected,{...roleValues,fileQuotaBytesLimit,fileQuotaFilesLimit:fileQuotaFilesLimit??null,version:detail.data?.version});
          saved();await Promise.all([
            client.invalidateQueries({queryKey:["roles"]}),
            client.invalidateQueries({queryKey:["session"]}),
            client.invalidateQueries({queryKey:["user-file-quota-preview"]}),
            client.invalidateQueries({queryKey:["files","usage"]}),
          ]);

      })}>
        <Form.Item name="code" label={t("roleCode")} rules={[{required:true,pattern:/^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}$/,message:t("invalidRole")}]}><Input disabled={!!selected || !effectiveCanWrite} maxLength={100}/></Form.Item>
        <Form.Item name="name" label={t("name")} rules={[{required:true,whitespace:true,message:t("nameRequired")},{max:200,message:t("nameTooLong")}]}><Input maxLength={200}/></Form.Item>
        <Form.Item name="enabled" label={t("enabled")} valuePropName="checked"><Switch/></Form.Item>
        <Form.Item name="dataScope" label={t("dataScope")} extra={t("dataScopeHint")}><Select options={[{value:"SELF",label:t("scopeSelf")},{value:"ORGANIZATION",label:t("scopeOrganization")},{value:"SUBTREE",label:t("scopeSubtree")},{value:"ALL",label:t("scopeAll")}]} getPopupContainer={trigger=>trigger.parentElement ?? document.body}/></Form.Item>
        <Card size="small" title={t("fileQuotaAllocation")} className="mb-4">
          <Form.Item name="fileQuotaStorageGiB" label={t("roleMemberStorageLimit")} extra={t("roleQuotaUnrestrictedHint")}>
            <InputNumber min={0} max={MAX_SAFE_QUOTA/GIB} precision={3} step={0.25} suffix="GiB" style={{width:"100%"}} />
          </Form.Item>
          <Form.Item name="fileQuotaFilesLimit" label={t("roleMemberFileCountLimit")} extra={t("roleQuotaUnrestrictedHint")}>
            <InputNumber min={0} max={MAX_SAFE_QUOTA} precision={0} style={{width:"100%"}} />
          </Form.Item>
        </Card>
        <Form.Item name="authorities" label={t("permissions")} extra={t("rolePermissionHint")}>
          <Checkbox.Group style={{display:"block",width:"100%"}}>
            <Row gutter={[12,12]}>
              {buildRolePermissionGroups(permissions.data ?? []).map(group=><Col xs={24} md={12} key={group.id}>
                <Card size="small" title={t(group.title)} styles={{body:{padding:12}}}>
                  {group.pages.length>1 && <Typography.Text type="secondary" style={{display:"block",fontSize:12,lineHeight:1.5,marginBottom:10}}>{group.pages.map(page=>t(page)).join(" · ")}</Typography.Text>}
                  <Space direction="vertical" size={8}>
                    {group.permissions.map(permission=>{
                      const label=rolePermissionLabel(permission);
                      return <Checkbox key={permission} value={permission} disabled={!effectiveCanWrite || !authorities.includes(permission)}>{label ? t(label) : permission}</Checkbox>;
                    })}
                  </Space>
                </Card>
              </Col>)}
            </Row>
          </Checkbox.Group>
        </Form.Item>
      </Form>}
      {selected && detail.data && effectiveCanManageAuthenticationPolicy && <RoleAuthenticationEditor role={detail.data} canWrite={effectiveCanWrite} />}
    </FormDrawer>
  </>;
}

function roleFormValues(role:Role|undefined){return role?{...role,fileQuotaStorageGiB:role.fileQuotaBytesLimit===null?null:role.fileQuotaBytesLimit/GIB,fileQuotaFilesLimit:role.fileQuotaFilesLimit}:undefined;}
