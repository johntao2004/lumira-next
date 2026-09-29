"use client";
import {useState} from "react";
import {useQuery} from "@tanstack/react-query";
import {PlatformDrawer} from "@/platform/components/platform-drawer";
import {ResourceTable} from "@/platform/components/resource-table";
import type {PageResult} from "@/platform/api/pagination";
import {DeleteAction} from "@/platform/components/delete-action";
import { TableActions } from "@/platform/components/table-actions";
import {api,mutate} from "@/platform/api/client";
import {usePreferences} from "@/platform/providers";
import {formatDateTime} from "@/platform/i18n/format";
import {useListRequest} from "@/platform/routing/use-list-request";
import {listRequestParams} from "@/platform/api/pagination";
type DraftSummary={id:string;draftKey:string;revision:string;payloadBytes:number;updatedAt:string};
export function DraftsDrawer({open,onClose}:{open:boolean;onClose:()=>void}){
 const {t,locale}=usePreferences(),[request,setRequest]=useListRequest();
 const query=useQuery({queryKey:["account-drafts",request],queryFn:({signal})=>api<PageResult<DraftSummary>>(`/account/drafts?${listRequestParams(request)}`,{signal}),enabled:open,staleTime:0});
 return <PlatformDrawer title={t("drafts")} open={open} onClose={onClose} size={720} destroyOnHidden>
  <ResourceTable<DraftSummary> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()} columns={[
   {title:t("draftName"),dataIndex:"draftKey",search:true,width:180,render:(_,row)=><span style={{overflowWrap:"anywhere"}}>{row.draftKey==="organization.create"?t("createOrganization"):row.draftKey}</span>},
   {title:t("fileSize"),dataIndex:"payloadBytes",valueType:"digit",width:120,render:(_,row)=>new Intl.NumberFormat(locale).format(row.payloadBytes)},
   {title:t("draftSavedAt"),dataIndex:"updatedAt",valueType:"dateTimeRange",width:170,render:(_,row)=>formatDateTime(row.updatedAt,locale,{dateStyle:"short",timeStyle:"short"})},
   {title:t("actions"),search:false,width:100,render:(_,row)=><TableActions><DeleteAction name={row.draftKey==="organization.create"?t("createOrganization"):row.draftKey} actionLabel={t("deleteDraft")} onDelete={async()=>{
    try{await mutate(`/account/drafts/${encodeURIComponent(row.draftKey)}?revision=${encodeURIComponent(row.revision)}`,"DELETE");}
    catch(error){void query.refetch();throw error;}
    if(query.data?.items.length===1&&request.page>1)setRequest({...request,page:request.page-1});else await query.refetch();
   }}/></TableActions>},
  ]}/>
 </PlatformDrawer>;
}
