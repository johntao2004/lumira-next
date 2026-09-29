"use client";
import {QueryFailure} from "../components/query-failure";
import {Alert} from "antd";
import {useQuery} from "@tanstack/react-query";
import {api} from "../api/client";
import {usePreferences} from "../providers";
import {formatDateTime} from "../i18n/format";
export function MaintenanceNotice(){
 const {t,locale}=usePreferences();const query=useQuery({queryKey:["public-maintenance"],queryFn:({signal})=>api<{active:boolean;title:string;message:string;endAt:string|null}>("/public/maintenance",{signal}),refetchInterval:30000});
 const status=query.data;
 return <>{query.error&&<QueryFailure title={t("maintenanceLoadFailed")} pending={query.isFetching} onRetry={()=>void query.refetch()}/>}
 {status?.active&&<Alert type="warning" showIcon title={status.title||t("maintenanceActive")} description={<>{status.message&&<p style={{whiteSpace:"pre-wrap"}}>{status.message}</p>}{status.endAt&&<p>{t("maintenanceEndAt")}: {formatDateTime(status.endAt,locale)}</p>}</>} className="mb-4"/>}</>;
}
