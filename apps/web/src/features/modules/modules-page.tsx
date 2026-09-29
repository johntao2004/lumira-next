"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {ModuleHistoryDrawer} from "./module-history-drawer";
import {ModulePackagesDrawer} from "./module-packages-drawer";
import {useState} from "react";
import {useQuery,useQueryClient} from "@tanstack/react-query";
import {Alert, App, Button, Input, Tabs, Tag, Tooltip} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import {api,mutate,ApiError} from "@/platform/api/client";
import type {ModuleInfo as ApiModuleInfo} from "@/platform/modules/api-types";
import {ResourceTable} from "@/platform/components/resource-table";
import {FormDrawer} from "@/platform/components/form-drawer";
import {usePreferences} from "@/platform/providers";
import type {MessageKey} from "@/platform/i18n/messages";
import {useListRequest} from "@/platform/routing/use-list-request";
import {messageKeyFor} from "@/platform/api/errors";
import {matchesListFilters} from "@/platform/api/pagination";

const moduleErrorKeys = ["requiredModule", "moduleDependencyConflict", "invalidModuleChange", "staleRecord"] as const satisfies readonly MessageKey[];
type ModuleInfo = ApiModuleInfo & {core:boolean};
export function ModulesPage({canWrite,canReadHistory}:{canWrite:boolean;canReadHistory:boolean}){
 const {t}=usePreferences(),{message}=App.useApp(),client=useQueryClient();
 const [request,setRequest]=useListRequest(),[selected,setSelected]=useState<ModuleInfo|null>(null),[pending,setPending]=useState(false),[form]=Form.useForm();
 const [moduleTab,setModuleTab]=useState<"core"|"manageable">("core");
 const [historyId,setHistoryId]=useState<string|null>(null),[historyOpen,setHistoryOpen]=useState(false);
 const [packagesOpen,setPackagesOpen]=useState(false);
 const query=useQuery({queryKey:["modules"],queryFn:({signal})=>api<ModuleInfo[]>("/platform/modules",{signal}),staleTime:0});
 const name=(row:ModuleInfo)=>row.id==="identity"?t("identityModule"):row.id==="platform"?t("platformModule"):row.id==="audit-console"?t("auditConsole"):row.name;
 const rows=(query.data??[]).map(row=>({...row,core:Boolean(row.core??row.required)||row.id==="audit-console",moduleDisplayName:name(row)})).filter(row=>matchesListFilters(row,request.filters,request.filterModes));
 const coreRows=rows.filter(row=>row.core),manageableRows=rows.filter(row=>!row.core),visibleRows=moduleTab==="core"?coreRows:manageableRows;
 const close=()=>{if(!pending){setSelected(null);form.resetFields();}};
 const save=async({reason}:{reason:string})=>{
  if(!selected||selected.core||pending||!canWrite)return;setPending(true);
  try{
   await mutate(`/platform/modules/${encodeURIComponent(selected.id)}/state`,"PUT",{enabled:!selected.enabled,version:selected.stateVersion,reason});
   await Promise.all([client.invalidateQueries({queryKey:["modules"]}),client.invalidateQueries({queryKey:["session"]})]);
   client.removeQueries({queryKey:["audit"]});
   if(typeof BroadcastChannel!=="undefined"){const channel=new BroadcastChannel("lumira:modules");channel.postMessage("changed");channel.close();}
   setSelected(null);form.resetFields();void message.success(t("settingsSaved"));
  }catch(error){
   void message.error(t(messageKeyFor(error,moduleErrorKeys)));
   if(error instanceof ApiError&&error.status===409)void query.refetch();
  }finally{setPending(false);}
 };
 return <>
  <ModuleHistoryDrawer moduleId={historyId} open={historyOpen&&canReadHistory} onClose={()=>setHistoryOpen(false)}/>
  <ModulePackagesDrawer open={packagesOpen&&canReadHistory} onClose={()=>setPackagesOpen(false)} canWrite={canWrite}/>
  <Tabs activeKey={moduleTab} onChange={key=>{setModuleTab(key as "core"|"manageable");setRequest(current=>({...current,page:1}));}} items={[
   {key:"core",label:t("coreModules")},{key:"manageable",label:t("manageableModules")},
  ]}/>
  <ResourceTable<ModuleInfo> searchInitialValues={{ moduleDisplayName: request.filters?.moduleDisplayName, enabled: request.filters?.enabled }} result={{items:visibleRows.slice((request.page-1)*request.pageSize,request.page*request.pageSize),total:visibleRows.length,page:request.page,pageSize:request.pageSize}} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()} actions={canReadHistory&&<Button onClick={()=>setPackagesOpen(true)}>{t("modulePackages")}</Button>} columns={[
   {title:t("module"),dataIndex:"moduleDisplayName",search:true,render:(_,row)=><>{name(row)}{row.core&&<Tag className="ml-2">{t("requiredModule")}</Tag>}</>},
   {title:t("id"),dataIndex:"id"},{title:t("version"),dataIndex:"version"},
   {title:t("status"),dataIndex:"enabled",search:true,valueEnum:{true:{text:t("enabled")},false:{text:t("disabled")}},render:(_,row)=><Tag color={row.enabled?"success":"default"}>{t(row.enabled?"enabled":"disabled")}</Tag>},
   {title:t("dependencies"),dataIndex:"dependencies",width:280,render:(_,row)=>{const dependencies=row.dependencies.join(", ")||"—";return <Tooltip title={dependencies} placement="topLeft"><span tabIndex={0} aria-label={`${t("dependencies")}: ${dependencies}`} style={{display:"block",maxWidth:260,overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap"}}>{dependencies}</span></Tooltip>}},
   {title:t("actions"),search:false,render:(_,row)=><TableActions wrap>{!row.core&&canWrite?<Button onClick={()=>{form.resetFields();setSelected(row);}}>{t(row.enabled?"disableModule":"enableModule")}</Button>:null}{canReadHistory&&<Button onClick={()=>{setHistoryId(row.id);setHistoryOpen(true);}}>{t("moduleHistory")}</Button>}</TableActions>},
  ]}/>
  <FormDrawer open={!!selected} title={t(selected?.enabled?"disableModule":"enableModule")} pending={pending} formId="module-state" onClose={close}>
   {selected && <Alert type="info" showIcon title={t("moduleToggleHint")} className="mb-4" />}
   <Form form={form} id="module-state" name="module-state" layout="vertical" onFinish={save}>
    <Form.Item name="reason" label={t("changeReason")} rules={[{required:true,whitespace:true,message:t("changeReason")},{max:1000}]}><Input.TextArea maxLength={1000} rows={4} disabled={pending}/></Form.Item>
   </Form>
  </FormDrawer>
 </>;
}
