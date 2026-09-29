"use client";
import {PlatformDrawer} from "@/platform/components/platform-drawer";
import { useState } from "react";
import { useQuery,useQueryClient } from "@tanstack/react-query";
import { history } from "@umijs/max";
import { Tag } from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api,mutate } from "@/platform/api/client";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { DeleteAction } from "@/platform/components/delete-action";
import { usePreferences } from "@/platform/providers";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";

type Session={id:string;createdAt:string;lastAccessAt:string;expiresAt:string;current:boolean};
export function SessionsDrawer({open,onClose}:{open:boolean;onClose:()=>void}) {
  const {t,locale}=usePreferences();const client=useQueryClient();
  const [request,setRequest]=useListRequest();
  const query=useQuery({queryKey:["account-sessions",request],enabled:open,queryFn:({signal})=>api<PageResult<Session>>(`/account/sessions?${listRequestParams(request)}`,{signal}),staleTime:0});
  const format=(value:string)=>formatDateTime(value,locale,{dateStyle:"medium",timeStyle:"medium"});
  return <PlatformDrawer open={open} onClose={onClose} title={t("mySessions")} size={900} destroyOnHidden>
    <ResourceTable<Session> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()}
      actions={<DeleteAction name="" actionLabel={t("signOutOthers")} confirmation={t("signOutOthersConfirm")} onDelete={async()=>{await mutate("/account/sessions/others","DELETE",{});setRequest({...request,page:1});await client.invalidateQueries({queryKey:["account-sessions"]});}}/>}
      columns={[{title:t("status"),dataIndex:"current",search:true,valueEnum:{true:{text:t("currentSession")},false:{text:t("otherSession")}},render:(_,s)=>s.current?<Tag color="success">{t("currentSession")}</Tag>:t("otherSession")},{title:t("sessionCreated"),dataIndex:"createdAt",valueType:"dateTimeRange",render:(_,s)=>format(s.createdAt)},{title:t("lastAccess"),dataIndex:"lastAccessAt",valueType:"dateTimeRange",render:(_,s)=>format(s.lastAccessAt)},{title:t("sessionExpires"),dataIndex:"expiresAt",valueType:"dateTimeRange",render:(_,s)=>format(s.expiresAt)},
        {title:t("actions"),search:false,render:(_,s)=><TableActions><DeleteAction name="" actionLabel={t("logout")} confirmation={t(s.current?"signOutCurrentConfirm":"signOutSessionConfirm")} onDelete={async()=>{
          await mutate(`/account/sessions/${s.id}`,"DELETE",{});
          if(s.current){client.clear();onClose();history.replace("/user/login");}
          else {if(query.data?.items.length===1 && request.page>1)setRequest({...request,page:request.page-1});await client.invalidateQueries({queryKey:["account-sessions"]});}
        }}/></TableActions>}]} />
  </PlatformDrawer>;
}
