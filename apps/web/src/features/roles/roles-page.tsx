"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {Alert, Button, Card, Checkbox, Col, Input, InputNumber, Row, Spin, Switch, Tag, Select, Space, Typography} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
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
    pages: ["personalizationSettings", "messageChannels", "smsSettings", "profileFields", "agreements", "watermark", "maintenance", "securitySettings", "passkeySettings", "fileRetention", "aiConsole", "aiModels", "aiJudgment", "aiCapabilities"],
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

export function RolesPage({canWrite,authorities}:{canWrite:boolean;authorities:string[]}) {
  const {t,locale}=usePreferences();
  const confirm=useCenteredModalConfirm();
  const client=useQueryClient();
  const [request,setRequest]=useListRequest();
  const [selected,setSelected]=useState<string|null|undefined>(undefined);
  const [pending,setPending]=useState(false);
  const [error,setError]=useState<MessageKey|null>(null);
  const query=useQuery({queryKey:["roles",request],queryFn:({signal})=>api<PageResult<Role>>(`/platform/roles?${listRequestParams(request)}`,{signal})});
  const detail=useQuery({queryKey:["role",selected],enabled:typeof selected==="string",queryFn:({signal})=>api<Role>(`/platform/roles/${selected}`,{signal}),staleTime:0});
  const permissions=useQuery({queryKey:["permissions"],enabled:selected!==undefined,queryFn:({signal})=>api<string[]>("/platform/permissions",{signal})});
  const close=()=>{setSelected(undefined);setError(null);};
  return <>
    <ResourceTable<Role> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()}
      actions={canWrite && <Button type="primary" onClick={()=>setSelected(null)}>{t("createRole")}</Button>}
      columns={[{title:t("roleCode"),dataIndex:"code",search:true},{title:t("name"),dataIndex:"name", search: true},{title:t("roleFileStorageLimit"),dataIndex:"fileQuotaBytesLimit",search:false,render:(_,r)=>r.fileQuotaBytesLimit===null?<Typography.Text type="secondary">—</Typography.Text>:formatBytes(r.fileQuotaBytesLimit,locale)},{title:t("roleFileCountLimit"),dataIndex:"fileQuotaFilesLimit",search:false,render:(_,r)=>r.fileQuotaFilesLimit===null?<Typography.Text type="secondary">—</Typography.Text>:new Intl.NumberFormat(locale).format(r.fileQuotaFilesLimit)},{title:t("status"),dataIndex:"enabled", search: true,valueEnum:{true:{text:t("enabled")},false:{text:t("disabled")}},render:(_,r)=><Tag color={r.enabled?"success":"default"}>{t(r.enabled?"enabled":"disabled")}</Tag>},
        {title:t("actions"),search:false,render:(_,r)=><TableActions><Button type="link" onClick={()=>setSelected(r.id)}>{t(canWrite?"edit":"roleDetails")}</Button>{canWrite && <DeleteAction name={r.name} onDelete={async()=>{
          await mutate(`/platform/roles/${r.id}?version=${r.version}`,"DELETE",{});
          if(query.data?.items.length===1 && request.page>1) setRequest({...request,page:request.page-1});
          await client.invalidateQueries({queryKey:["roles"]});client.removeQueries({queryKey:["role",r.id]});
        }}/>}</TableActions>}]} />
    <FormDrawer open={selected!==undefined} title={t(selected?"roleDetails":"createRole")} pending={pending} size={760} submitDisabled={!canWrite || !permissions.data || (!!selected && (!detail.data || detail.isFetching))} formId="role-editor" onClose={close}>
      <FeedbackBubble id="role-editor-error" content={error ? t(error) : undefined} />
      {permissions.error || (selected && detail.error) ? <QueryFailure title={t("unavailable")} pending={permissions.isFetching || !!(selected && detail.isFetching)} onRetry={()=>{void permissions.refetch();void detail.refetch();}} /> : permissions.isPending || (selected && (detail.isPending || detail.isFetching)) ? <Spin/> :
      <Form key={selected ?? "create"} id="role-editor" layout="vertical" disabled={!canWrite || pending} initialValues={selected?roleFormValues(detail.data):{enabled:true,authorities:[],dataScope:"SELF",fileQuotaStorageGiB:null,fileQuotaFilesLimit:null}} onFinish={async values=>{
        setPending(true);setError(null);
        try {
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
              currentRole.fileQuotaBytesLimit!==fileQuotaBytesLimit&&`${t("roleFileStorageLimit")}: ${currentRole.fileQuotaBytesLimit===null?t("roleQuotaUnrestrictedHint"):formatBytes(currentRole.fileQuotaBytesLimit,locale)} → ${fileQuotaBytesLimit===null?t("roleQuotaUnrestrictedHint"):formatBytes(fileQuotaBytesLimit,locale)}`,
              currentRole.fileQuotaFilesLimit!==(fileQuotaFilesLimit??null)&&`${t("roleFileCountLimit")}: ${currentRole.fileQuotaFilesLimit===null?t("roleQuotaUnrestrictedHint"):new Intl.NumberFormat(locale).format(currentRole.fileQuotaFilesLimit)} → ${fileQuotaFilesLimit==null?t("roleQuotaUnrestrictedHint"):new Intl.NumberFormat(locale).format(fileQuotaFilesLimit)}`,
              currentRole.enabled!==roleValues.enabled&&`${t("enabled")}: ${t(currentRole.enabled?"enabled":"disabled")} → ${t(roleValues.enabled?"enabled":"disabled")}`,
            ].filter(Boolean);
            const confirmed=await new Promise<boolean>(resolve=>confirm({
              title:t("fileQuotaAllocation"),
              content:<div className="flex flex-col gap-2"><Typography.Paragraph>{t("roleQuotaImpactHint")}</Typography.Paragraph>{changes.map((change,index)=><Typography.Paragraph key={index} className="mb-0">{change}</Typography.Paragraph>)}</div>,
              okText:t("confirm"),cancelText:t("cancel"),
              onOk:()=>resolve(true),onCancel:()=>resolve(false),
            }));
            if(!confirmed)return;
          }
          await mutate(selected?`/platform/roles/${selected}`:"/platform/roles",selected?"PUT":"POST",{...roleValues,fileQuotaBytesLimit,fileQuotaFilesLimit:fileQuotaFilesLimit??null,version:detail.data?.version});
          close();await Promise.all([
            client.invalidateQueries({queryKey:["roles"]}),
            client.invalidateQueries({queryKey:["session"]}),
            client.invalidateQueries({queryKey:["user-file-quota-preview"]}),
            client.invalidateQueries({queryKey:["files","usage"]}),
          ]);
        } catch(e) {setError(messageKeyFor(e,roleErrorKeys,"unavailable",roleErrorAliases));} finally {setPending(false);}
      }}>
        <Form.Item name="code" label={t("roleCode")} rules={[{required:true,pattern:/^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}$/,message:t("invalidRole")}]}><Input disabled={!!selected || !canWrite} maxLength={100}/></Form.Item>
        <Form.Item name="name" label={t("name")} rules={[{required:true,whitespace:true,message:t("nameRequired")},{max:200,message:t("nameTooLong")}]}><Input maxLength={200}/></Form.Item>
        <Form.Item name="enabled" label={t("enabled")} valuePropName="checked"><Switch/></Form.Item>
        <Form.Item name="dataScope" label={t("dataScope")} extra={t("dataScopeHint")}><Select options={[{value:"SELF",label:t("scopeSelf")},{value:"ORGANIZATION",label:t("scopeOrganization")},{value:"SUBTREE",label:t("scopeSubtree")},{value:"ALL",label:t("scopeAll")}]} getPopupContainer={trigger=>trigger.parentElement ?? document.body}/></Form.Item>
        <Card size="small" title={t("fileQuotaAllocation")} className="mb-4">
          <Alert className="mb-4" type="info" showIcon message={t("roleQuotaImpactHint")} />
          <Form.Item name="fileQuotaStorageGiB" label={t("roleFileStorageLimit")} extra={t("roleQuotaUnrestrictedHint")}>
            <InputNumber min={0} max={MAX_SAFE_QUOTA/GIB} precision={3} step={0.25} addonAfter="GiB" style={{width:"100%"}} />
          </Form.Item>
          <Form.Item name="fileQuotaFilesLimit" label={t("roleFileCountLimit")} extra={t("roleQuotaUnrestrictedHint")}>
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
                      return <Checkbox key={permission} value={permission} disabled={!authorities.includes(permission)}>{label ? t(label) : permission}</Checkbox>;
                    })}
                  </Space>
                </Card>
              </Col>)}
            </Row>
          </Checkbox.Group>
        </Form.Item>
      </Form>}
    </FormDrawer>
  </>;
}

function roleFormValues(role:Role|undefined){return role?{...role,fileQuotaStorageGiB:role.fileQuotaBytesLimit===null?null:role.fileQuotaBytesLimit/GIB,fileQuotaFilesLimit:role.fileQuotaFilesLimit}:undefined;}
function formatBytes(bytes:number,locale:string){const unit=bytes>=1024**3?3:bytes>=1024**2?2:bytes>=1024?1:0;return `${new Intl.NumberFormat(locale,{maximumFractionDigits:2}).format(bytes/1024**unit)} ${["B","KiB","MiB","GiB"][unit]}`;}
