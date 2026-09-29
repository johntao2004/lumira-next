"use client";
import {Alert,Tag} from "antd";
import {useQuery} from "@tanstack/react-query";
import {api,ApiError} from "@/platform/api/client";
import {ResourceTable} from "@/platform/components/resource-table";
import type {PageResult} from "@/platform/api/pagination";
import {usePreferences} from "@/platform/providers";
import {formatDateTime} from "@/platform/i18n/format";
import {useListRequest} from "@/platform/routing/use-list-request";
import type {MessageKey} from "@/platform/i18n/messages";
import {listRequestParams} from "@/platform/api/pagination";

const states={PENDING:"mailPending",LEASED:"mailLeased",SENDING:"mailSending",ACCEPTED:"mailAccepted",FAILED:"mailFailed",UNCERTAIN:"mailUncertain",EXPIRED:"mailExpired"} as const satisfies Record<string,MessageKey>;
const results:Record<string,MessageKey>={ACCEPTED:"mailAccepted",AUTHENTICATION_FAILED:"mailAuthenticationFailed",CONNECTION_FAILED:"mailConnectionFailed",DELIVERY_UNCERTAIN:"mailUncertain",CONFIGURATION_UNAVAILABLE:"mailConfigurationUnavailable",LEASE_EXPIRED:"mailLeaseExpired"};
type Entry={id:string;state:keyof typeof states;attempts:number;lastResult:string|null;availableAt:string;expiresAt:string;createdAt:string;updatedAt:string};
export function MailDeliveryPage(){
 const {t,locale}=usePreferences();const [request,setRequest]=useListRequest();
 const query=useQuery({queryKey:["mail-deliveries",request],queryFn:({signal})=>api<PageResult<Entry>>(`/platform/mail-deliveries?${listRequestParams(request,{state:request.filters?.state??""})}`,{signal}),refetchInterval:10000});
 const time=(value:string)=><time dateTime={value}>{formatDateTime(value,locale,{dateStyle:"short",timeStyle:"medium"})}</time>;
 const hasUncertainDelivery=query.data?.items.some(entry=>entry.state==="UNCERTAIN");
 return <>
  {hasUncertainDelivery&&<Alert type="warning" showIcon title={t("mailDeliveryHint")} className="mb-4"/>}
  <ResourceTable<Entry> searchInitialValues={{state:request.filters?.state}} result={query.error instanceof ApiError&&query.error.status===403?undefined:query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()} columns={[
   {title:t("targetId"),dataIndex:"id",search:true},
   {title:t("status"),dataIndex:"state",search:true,valueEnum:Object.fromEntries(Object.entries(states).map(([value,label])=>[value,{text:t(label)}])),render:(_,entry)=><Tag color={entry.state==="ACCEPTED"?"green":entry.state==="FAILED"||entry.state==="UNCERTAIN"?"orange":"default"}>{t(states[entry.state])}</Tag>},
   {title:t("mailAttempts"),dataIndex:"attempts",valueType:"digit"},
   {title:t("mailLastResult"),dataIndex:"lastResult",valueEnum:Object.fromEntries(Object.entries(results).map(([value,label])=>[value,{text:t(label)}])),render:(_,entry)=>entry.lastResult&&results[entry.lastResult]?t(results[entry.lastResult]):"—"},
   {title:t("eventTime"),dataIndex:"createdAt",valueType:"dateTimeRange",render:(_,entry)=>time(entry.createdAt)},
   {title:t("settingUpdatedAt"),dataIndex:"updatedAt",valueType:"dateTimeRange",render:(_,entry)=>time(entry.updatedAt)},
   {title:t("mailScheduled"),dataIndex:"availableAt",valueType:"dateTimeRange",render:(_,entry)=>time(entry.availableAt)},
   {title:t("mailExpires"),dataIndex:"expiresAt",valueType:"dateTimeRange",render:(_,entry)=>time(entry.expiresAt)},
  ]}/>
 </>;
}
