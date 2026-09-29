"use client";
import {useState} from "react";
import {useQuery} from "@tanstack/react-query";
import {api} from "@/platform/api/client";
import {PlatformDrawer} from "@/platform/components/platform-drawer";
import {ResourceTable} from "@/platform/components/resource-table";
import type {PageResult} from "@/platform/api/pagination";
import {usePreferences} from "@/platform/providers";
import {formatDateTime} from "@/platform/i18n/format";
import {useListRequest} from "@/platform/routing/use-list-request";
import {createListRequest, listRequestParams} from "@/platform/api/pagination";
type Revision={id:string;version:number;enabled:boolean;changedBy:string;changeReason:string;createdAt:string};
export function ModuleHistoryDrawer({moduleId,open,onClose}:{moduleId:string|null;open:boolean;onClose:()=>void}){
 const {t,locale}=usePreferences();
 const [request,setRequest]=useListRequest();
 const [requestModuleId,setRequestModuleId]=useState<string|null>(null);
 const activeRequest=requestModuleId===moduleId?request:createListRequest();
 const query=useQuery({queryKey:["module-history",moduleId,activeRequest],enabled:open&&!!moduleId,staleTime:0,queryFn:({signal})=>{
  if(!moduleId)throw new Error("moduleNotFound");
  return api<PageResult<Revision>>(`/platform/modules/${encodeURIComponent(moduleId)}/history?${listRequestParams(activeRequest)}`,{signal});
 }});
 return <PlatformDrawer title={t("moduleHistory")} open={open} onClose={onClose} size={800} destroyOnHidden>
  <p>{moduleId}</p>
  <ResourceTable<Revision> result={query.data} loading={query.isFetching} error={query.error} request={activeRequest} onChange={next=>{setRequestModuleId(moduleId);setRequest(next);}} onRetry={()=>void query.refetch()} columns={[
   {title:t("version"),dataIndex:"version",valueType:"digit",width:80},
   {title:t("status"),dataIndex:"enabled",valueEnum:{true:{text:t("enabled")},false:{text:t("disabled")}},render:(_,row)=>t(row.enabled?"enabled":"disabled"),width:100},
   {title:t("eventTime"),dataIndex:"createdAt",valueType:"dateTimeRange",render:(_,row)=>formatDateTime(row.createdAt,locale,{dateStyle:"medium",timeStyle:"medium"}),width:170},
   {title:t("actor"),dataIndex:"changedBy",width:130},
   {title:t("changeReason"),dataIndex:"changeReason",render:(_,row)=><span style={{overflowWrap:"anywhere"}}>{row.changeReason}</span>},
  ]}/>
 </PlatformDrawer>;
}
