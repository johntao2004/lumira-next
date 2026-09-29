"use client";
import { useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Select, Space } from "antd";
import { api } from "../api/client";
import { usePreferences } from "../providers";
import type { PageResult } from "../api/pagination";
import { AsyncButton } from "./async-button";
import { QueryFailure } from "./query-failure";

type NamedResource={id:string;name?:string;label?:string};
/** Paginated search with an independent lookup for an existing off-page selection. */
export function ResourceSelect({endpoint,value,onChange,disabled,excludeId,id,labelField="name"}:{endpoint:string;value?:string|null;onChange?:(value:string|null)=>void;disabled?:boolean;excludeId?:string;id?:string;labelField?:"name"|"label"}) {
  const {t}=usePreferences();
  const [search,setSearch]=useState("");
  const query=useInfiniteQuery({queryKey:["resource-select",endpoint,search],initialPageParam:1,
    queryFn:({pageParam,signal})=>api<PageResult<NamedResource>>(`${endpoint}?${new URLSearchParams({page:String(pageParam),pageSize:"20",search})}`,{signal}),
    getNextPageParam:page=>page.page*page.pageSize<page.total?page.page+1:undefined});
  const selected=useQuery({queryKey:["resource-select-detail",endpoint,value],enabled:!!value,queryFn:({signal})=>api<NamedResource>(`${endpoint}/${value}`,{signal})});
  const options=new Map<string,{value:string;label:string}>();
  if(selected.data)options.set(selected.data.id,{value:selected.data.id,label:selected.data[labelField]??selected.data.id});
  for(const item of query.data?.pages.flatMap(page=>page.items)??[]) if(item.id!==excludeId)options.set(item.id,{value:item.id,label:item[labelField]??item.id});
  return <Space orientation="vertical" style={{width:"100%"}}>
    <Select getPopupContainer={trigger=>trigger.parentElement ?? document.body} id={id} style={{width:"100%"}} value={value??undefined} disabled={disabled} allowClear showSearch={{filterOption:false,onSearch:setSearch}} onChange={next=>onChange?.(next??null)} loading={query.isFetching} options={[...options.values()]} placeholder={t("searchCodes")}
      popupRender={menu=><>{menu}{query.hasNextPage && <AsyncButton block pending={query.isFetchingNextPage} onMouseDown={event=>event.preventDefault()} onClick={()=>void query.fetchNextPage()} label={t("loadMore")} />}</>}/>
    {(query.error||selected.error) && <QueryFailure title={t("unavailable")} pending={query.isFetching || selected.isFetching} onRetry={()=>{void query.refetch();if(value)void selected.refetch();}}/>}
  </Space>;
}
