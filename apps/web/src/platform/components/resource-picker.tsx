"use client";
import {ConfigProvider} from "antd";
import {useQuery} from "@tanstack/react-query";
import {ResourceTable} from "./resource-table";
import type {PageResult} from "../api/pagination";
import {api} from "../api/client";
import {usePreferences} from "../providers";
import {useListRequest} from "../routing/use-list-request";
import {listRequestParams} from "../api/pagination";
/** A form-compatible paginated multi-picker; selection survives page/search changes. */
export function ResourcePicker({endpoint,value=[],onChange,id,max=100}:{endpoint:string;value?:string[];onChange?:(ids:string[])=>void;id?:string;max?:number}){
 const {t}=usePreferences();const {componentDisabled}=ConfigProvider.useConfig();const [request,setRequest]=useListRequest({pageSize:10});
 const query=useQuery({queryKey:["resource-picker",endpoint,request],queryFn:({signal})=>api<PageResult<{id:string;name:string;enabled:boolean}>>(`${endpoint}?${listRequestParams(request)}`,{signal})});
 return <div id={id}><ConfigProvider componentDisabled={false}><ResourceTable result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()} columns={[{title:t("name"),dataIndex:"name",search:true}]} rowSelection={{selectedRowKeys:value,preserveSelectedRowKeys:true,onChange:keys=>{if(!componentDisabled)onChange?.(keys.map(String).slice(0,max));},getCheckboxProps:row=>({disabled:componentDisabled||(!row.enabled&&!value.includes(row.id))||(!value.includes(row.id)&&value.length>=max)})}}/></ConfigProvider><div>{value.length} / {max}</div></div>;
}
