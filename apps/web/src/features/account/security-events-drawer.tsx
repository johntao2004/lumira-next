"use client";
import {useState} from "react";
import {useQuery} from "@tanstack/react-query";
import {PlatformDrawer} from "@/platform/components/platform-drawer";
import {ResourceTable} from "@/platform/components/resource-table";
import type {PageResult} from "@/platform/api/pagination";
import {api} from "@/platform/api/client";
import {usePreferences} from "@/platform/providers";
import {accountSecurityActions} from "@/platform/auth/security-actions";
import {formatDateTime} from "@/platform/i18n/format";
import {useListRequest} from "@/platform/routing/use-list-request";
import {listRequestParams} from "@/platform/api/pagination";
type Event={id:string;action:string;createdAt:string;clientAddress:string|null;userAgent:string|null};
export function SecurityEventsDrawer({open,onClose}:{open:boolean;onClose:()=>void}){
 const {t,locale}=usePreferences(),[request,setRequest]=useListRequest();
 const query=useQuery({queryKey:["account-security-events",request],queryFn:({signal})=>api<PageResult<Event>>(`/account/security-events?${listRequestParams(request)}`,{signal}),enabled:open,staleTime:0});
 return <PlatformDrawer title={t("securityEvents")} open={open} onClose={onClose} size={720} destroyOnHidden>
  <ResourceTable<Event> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()} columns={[
   {title:t("eventTime"),dataIndex:"createdAt",search:true,valueType:"dateTimeRange",width:170,render:(_,row)=>formatDateTime(row.createdAt,locale,{dateStyle:"medium",timeStyle:"medium"})},
   {title:t("actions"),dataIndex:"action",search:true,valueEnum:Object.fromEntries(Object.entries(accountSecurityActions).map(([action,label])=>[action,{text:t(label)}])),width:130,render:(_,row)=>accountSecurityActions[row.action]?t(accountSecurityActions[row.action]):row.action},
   {title:t("clientAddress"),dataIndex:"clientAddress",search:true,width:130,render:(_,row)=><span style={{overflowWrap:"anywhere"}}>{row.clientAddress??"—"}</span>},
   {title:t("userAgent"),dataIndex:"userAgent",render:(_,row)=><span style={{overflowWrap:"anywhere"}}>{row.userAgent??"—"}</span>},
  ]}/>
 </PlatformDrawer>;
}
