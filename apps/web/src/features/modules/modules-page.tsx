"use client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import {usePluginManager} from "./plugin-manager";
import {useEffect} from "react";
import {useQuery,useQueryClient} from "@tanstack/react-query";
import {Alert, App, Button, Tabs, Tag, Tooltip} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import {api,mutate,ApiError} from "@/platform/api/client";
import type {ModuleInfo as ApiModuleInfo} from "@/platform/modules/api-types";
import {ResourceTable} from "@/platform/components/resource-table";
import {usePreferences} from "@/platform/providers";
import type {MessageKey} from "@/platform/i18n/messages";
import {useListRequest} from "@/platform/routing/use-list-request";
import {matchesListFilters} from "@/platform/api/pagination";
import { useTabAnchor } from "@/platform/routing/use-tab-anchor";
import { accountAvatarKey } from "@/platform/api/account-avatar-contract";

const moduleErrorKeys = ["requiredModule", "moduleDependencyConflict", "invalidModuleChange", "staleRecord", "pluginFixedGraph", "pluginClusterLifecycleUnsupported", "pluginGraphChanged"] as const satisfies readonly MessageKey[];
type ModuleInfo = ApiModuleInfo & {core:boolean};
export function ModulesPage({canWrite}:{canWrite:boolean}){
 const {t}=usePreferences(),{message,modal}=App.useApp(),client=useQueryClient();
 const [request,setRequest]=useListRequest();
 const [moduleTab,setModuleTab]=useTabAnchor("",["core","manageable"],"core");
 useEffect(()=>{setRequest(current=>({...current,page:1}));},[moduleTab]);
 const plugins=usePluginManager(canWrite);
 const query=useQuery({queryKey:["modules"],queryFn:({signal})=>api<ModuleInfo[]>("/platform/modules",{signal}),staleTime:0});
 const name=(row:ModuleInfo)=>row.id==="identity"?t("identityModule"):row.id==="platform"?t("platformModule"):row.id==="audit-console"?t("auditConsole"):row.name;
 const rows=(query.data??[]).map(row=>({...row,core:Boolean(row.core??row.required)||row.id==="audit-console",moduleDisplayName:name(row)})).filter(row=>matchesListFilters(row,request.filters,request.filterModes));
 const coreRows=rows.filter(row=>row.core),manageableRows=rows.filter(row=>!row.core),visibleRows=moduleTab==="core"?coreRows:manageableRows;
 const {pending,run}=useSubmitAction({errorKeys:moduleErrorKeys,onError:(key,error)=>{
  void message.error(t(key));if(error instanceof ApiError&&error.status===409)void query.refetch();
 }});
 const save=async(row:ModuleInfo)=>{
  if(row.core||pending||!canWrite)return;
  return run(async()=>{
   await mutate(`/platform/modules/${encodeURIComponent(row.id)}/state`,"PUT",{enabled:!row.enabled,version:row.stateVersion});
   await Promise.all([client.invalidateQueries({queryKey:["modules"]}),client.invalidateQueries({queryKey:["session"]}),client.invalidateQueries({queryKey:accountAvatarKey})]);
   client.removeQueries({queryKey:["audit"]});
   if(typeof BroadcastChannel!=="undefined"){const channel=new BroadcastChannel("lumira:modules");channel.postMessage("changed");channel.close();}
   void message.success(t("settingsSaved"));
  });
 };
 const confirmToggle=(row:ModuleInfo)=>{
  if(row.core||pending||!canWrite)return;
  const action=row.enabled?"disableModule":"enableModule";
  modal.confirm({title:t(action),content:t("moduleToggleHint"),okText:t("confirm"),cancelText:t("cancel"),onOk:()=>save(row)});
 };
 return <>
  {plugins.drawer}
  {plugins.lifecycleBlockReason&&<Alert className="mb-4" type="info" showIcon title={t(plugins.lifecycleBlockReason)}/>}
  <Tabs activeKey={moduleTab} onChange={key=>{setModuleTab(key as "core"|"manageable");setRequest(current=>({...current,page:1}));}} items={[
   {key:"core",label:t("coreModules")},{key:"manageable",label:t("manageableModules")},
  ]}/>
  <ResourceTable<ModuleInfo> searchInitialValues={{ moduleDisplayName: request.filters?.moduleDisplayName, enabled: request.filters?.enabled }} result={{items:visibleRows.slice((request.page-1)*request.pageSize,request.page*request.pageSize),total:visibleRows.length,page:request.page,pageSize:request.pageSize}} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()} actions={moduleTab==="manageable"&&plugins.controls} columns={[
   {title:t("module"),dataIndex:"moduleDisplayName",search:true,render:(_,row)=>name(row)},
   {title:t("id"),dataIndex:"id"},{title:t("version"),dataIndex:"version"},
   {title:t("status"),dataIndex:"enabled",search:true,valueEnum:{true:{text:t("enabled")},false:{text:t("disabled")}},render:(_,row)=><Tag color={row.enabled?"success":"default"}>{t(!row.core&&!plugins.query.data?.find(p=>p.id===row.id)?.installed?"pluginUninstalled":row.enabled?"enabled":"disabled")}</Tag>},
   {title:t("dependencies"),dataIndex:"dependencies",width:280,render:(_,row)=>{const dependencies=row.dependencies.join(", ")||"—";return <Tooltip title={dependencies} placement="topLeft"><span tabIndex={0} aria-label={`${t("dependencies")}: ${dependencies}`} style={{display:"block",maxWidth:260,overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap"}}>{dependencies}</span></Tooltip>}},
   ...(moduleTab==="manageable"&&canWrite?[{title:t("actions"), key: "actions",search:false,render:(_:unknown,row:ModuleInfo)=>{const plugin=plugins.query.data?.find(p=>p.id===row.id),installed=plugin?.installed,disabled=plugins.pending||pending||!plugins.lifecycleChangesAllowed;return <TableActions wrap>{installed?<><Button disabled={disabled} onClick={()=>confirmToggle(row)}>{t(row.enabled?"disableModule":"enableModule")}</Button><Button disabled={disabled} onClick={()=>void plugins.prepare(row.id,"uninstall")}>{t("uninstallPlugin")}</Button></>:<Button disabled={disabled||plugins.query.isPending} onClick={()=>void plugins.prepare(row.id,"install")}>{t("installPlugin")}</Button>}</TableActions>;}}]:[]),
  ]}/>
 </>;
}
