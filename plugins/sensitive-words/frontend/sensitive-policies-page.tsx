"use client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";

import {useState} from "react";
import {useQuery,useQueryClient} from "@tanstack/react-query";
import {Alert,Button,Card,Input,Select,Space,Switch,Tag,Typography} from "antd";
import {AnchorTabs as Tabs} from "@/platform/components/anchor-tabs";
import {PlatformForm as Form} from "@/platform/components/platform-form";
import {FormDrawer} from "@/platform/components/form-drawer";
import {ResourceTable} from "@/platform/components/resource-table";
import {FeedbackBubble} from "@/platform/components/feedback-bubble";
import {api,mutate} from "@/platform/api/client";
import {listRequestParams,type PageResult} from "@/platform/api/pagination";
import {useListRequest} from "@/platform/routing/use-list-request";
import {usePreferences} from "@/platform/providers";
import {formatDateTime} from "@/platform/i18n/format";
import type {MessageKey} from "@/platform/i18n/messages";
import {SensitiveWordLibrary} from "./sensitive-words-page";

type Policy={id:string;roleId:string;roleCode:string;roleName:string;roleEnabled:boolean;mode:"ON"|"OFF";version:number};
type Hit={id:string;actor:string;source:string;action:string;matchCount:number;createdAt:string};
type Preview={hit:boolean;blocked:boolean;totalMatchCount:number;matches:{maskedWord:string;action:string}[]};
const errors=["forbidden","staleRecord","invalidRole","invalidSensitiveWord","moduleUnavailable","sensitiveTextTooLong"] as const;

export function SensitiveWordsPage({canWrite,canImport}:{canWrite:boolean;canImport:boolean}) {
  const {t}=usePreferences();
  return <>
    <Tabs defaultActiveKey="roles" destroyOnHidden items={[
      {key:"roles",label:t("sensitiveRoleControl"),children:<RoleControl canWrite={canWrite}/>},
      {key:"words",label:t("sensitiveSharedDictionary"),children:<SensitiveWordLibrary canWrite={canWrite} canImport={canImport}/>},
      {key:"hits",label:t("sensitiveHitRecords"),children:<HitRecords/>},
      {key:"preview",label:t("sensitivePolicyTrial"),children:<PolicyTrial/>},
    ]}/>
  </>;
}

function RoleControl({canWrite}:{canWrite:boolean}) {
  const {t}=usePreferences();const client=useQueryClient();
  const [request,setRequest]=useListRequest();const [editing,setEditing]=useState<Policy|null>(null);
  const {pending,error,setError,run}=useSubmitAction({errorKeys:errors});
  const query=useQuery({queryKey:["sensitive-role-policies",request],queryFn:async({signal})=>{
    const result=await api<PageResult<Policy>>(`/platform/sensitive-policies/roles?${listRequestParams(request,{search:request.filters?.roleName??request.search})}`,{signal});
    return {...result,items:result.items.map(row=>({...row,id:row.roleId}))};
  }});
  return <>
    <ResourceTable<Policy> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()} columns={[
      {title:t("name"),dataIndex:"roleName",search:true},
      {title:t("roleCode"),dataIndex:"roleCode",search:false},
      {title:t("status"),dataIndex:"roleEnabled",search:false,render:(_,r)=><Tag>{t(r.roleEnabled?"enabled":"disabled")}</Tag>},
      {title:t("sensitiveRoleControl"),dataIndex:"mode",search:false,render:(_,r)=><Space><Tag color={r.mode==="ON"?"success":"default"}>{t(r.mode==="ON"?"enableSensitiveWord":"disableSensitiveWord")}</Tag>{r.version===0&&<Typography.Text type="secondary">{t("sensitiveDefaultControl")}</Typography.Text>}</Space>},
      {title:t("actions"), key: "actions",search:false,render:(_,r)=>canWrite&&<Button type="link" onClick={()=>{setError(null);setEditing(r);}}>{t("edit")}</Button>},
    ]}/>
    <FormDrawer open={!!editing} title={t("sensitiveRoleControl")} pending={pending} formId="role-content-policy" onClose={()=>{setEditing(null);setError(null);}}>
      <FeedbackBubble id="role-content-policy-error" content={error?t(error):undefined}/>
      {editing&&<Form key={editing.roleId} name="role-content-policy" id="role-content-policy" layout="vertical" initialValues={{controlled:editing.mode==="ON"}} onFinish={values=>run(async()=>{
        if(!canWrite)return false;await mutate(`/platform/sensitive-policies/roles/${editing.roleId}`,"PUT",{mode:values.controlled?"ON":"OFF",version:editing.version});setEditing(null);await client.invalidateQueries({queryKey:["sensitive-role-policies"]});
      })}>
        <Typography.Paragraph>{editing.roleName} · {editing.roleCode}</Typography.Paragraph>
        <Form.Item name="controlled" label={t("sensitiveRoleControl")} valuePropName="checked" extra={t("sensitiveRoleControlHint")}><Switch disabled={pending||!canWrite}/></Form.Item>
      </Form>}
    </FormDrawer>
  </>;
}

function sourceLabel(source:string):MessageKey {
  if(source.startsWith("Feedback"))return "feedback";
  if(source.startsWith("Ai"))return "aiConsole";
  if(source.startsWith("File"))return "filesTab";
  if(source.startsWith("Account")||source.startsWith("Profile"))return "profile";
  if(source.startsWith("User"))return "userList";
  if(source.startsWith("Role"))return "roles";
  if(source.startsWith("Notification"))return "notificationCenter";
  if(source.startsWith("Localization"))return "localizationManagement";
  if(source.startsWith("Navigation"))return "navigationManagement";
  return "systemSettings";
}

function HitRecords() {
  const {t,locale}=usePreferences();const [request,setRequest]=useListRequest();
  const query=useQuery({queryKey:["sensitive-policy-hits",request],queryFn:({signal})=>api<PageResult<Hit>>(`/platform/sensitive-policies/hits?${listRequestParams(request,{search:request.filters?.actor??request.search})}`,{signal})});
  return <>
    <ResourceTable<Hit> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()} columns={[
      {title:t("username"),dataIndex:"actor",search:true},{title:t("sensitiveSubmissionSource"),dataIndex:"source",search:false,render:(_,r)=>t(sourceLabel(r.source))},
      {title:t("sensitiveWordAction"),dataIndex:"action",search:false,render:(_,r)=><Tag color={r.action==="BLOCK"?"error":"warning"}>{t(r.action==="BLOCK"?"sensitiveActionBlock":"sensitiveActionLog")}</Tag>},
      {title:t("sensitiveMatchCount"),dataIndex:"matchCount",search:false},
      {title:t("eventCreatedAt"),dataIndex:"createdAt",search:false,render:(_,r)=>formatDateTime(r.createdAt,locale)},
    ]}/>
  </>;
}

function PolicyTrial() {
  const {t}=usePreferences();const [role,setRole]=useState<string>();const [text,setText]=useState("");
  const [result,setResult]=useState<Preview|null>(null);const {pending,error,setError,run}=useSubmitAction({errorKeys:errors});
  const roles=useQuery({queryKey:["sensitive-trial-roles"],queryFn:async({signal})=>{
    const rows:Policy[]=[];let page=1;
    while(true){const r=await api<PageResult<Policy>>(`/platform/sensitive-policies/roles?page=${page++}&pageSize=100`,{signal});rows.push(...r.items);if(rows.length>=r.total||!r.items.length)break;}
    return rows;
  }});
  return <Card title={t("sensitivePolicyTrial")}>
    <Typography.Paragraph type="secondary">{t("sensitiveTrialHint")}</Typography.Paragraph>
    <Space orientation="vertical" style={{width:"100%"}} size="middle">
      <Select aria-label={t("roles")} placeholder={t("roles")} allowClear showSearch optionFilterProp="label" value={role} loading={roles.isFetching} style={{width:"100%"}} options={roles.data?.filter(r=>r.roleEnabled).map(r=>({value:r.roleId,label:r.roleName}))} onChange={r=>{setRole(r);setResult(null);}}/>
      <Input.TextArea aria-label={t("sensitiveCheckText")} value={text} maxLength={200000} rows={6} onChange={e=>{setText(e.target.value);setResult(null);}}/>
      <Button type="primary" loading={pending} disabled={!text.trim()} onClick={()=>run(async()=>{setResult(await mutate<Preview>("/platform/sensitive-policies/preview","POST",{roleId:role??null,text}));})}>{t("checkSensitiveContent")}</Button>
      {result&&<Alert showIcon type={result.blocked?"error":result.hit?"warning":"success"} title={t(result.blocked?"sensitiveContentBlocked":result.hit?"sensitiveContentLogged":"sensitiveContentClean")} description={t("sensitiveCheckResultCount",{count:result.totalMatchCount})}/>}
    </Space>
    <FeedbackBubble id="sensitive-trial-error" content={error?t(error):undefined}/>
  </Card>;
}
