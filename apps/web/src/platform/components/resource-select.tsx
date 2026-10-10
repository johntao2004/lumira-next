"use client";
import { useEffect, useRef, useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Select, Space } from "antd";
import { api } from "../api/client";
import { usePreferences } from "../providers";
import type { PageResult } from "../api/pagination";
import { AsyncButton } from "./async-button";
import { QueryFailure } from "./query-failure";

type NamedResource={id:string;name?:string;label?:string;username?:string;displayName?:string;enabled?:boolean};
type CurrentSelection={itemId:string;label:string;enabled:boolean};
type ResourceSelectBase={endpoint:string;disabled?:boolean;excludeId?:string;id?:string;labelField?:"name"|"label";currentSelection?:CurrentSelection;optionLabel?:(item:NamedResource)=>string;max?:number};
type SingleResourceSelectProps=ResourceSelectBase&{multiple?:false;value?:string|null;onChange?:(value:string|null)=>void};
type MultipleResourceSelectProps=ResourceSelectBase&{multiple:true;value?:string[]|null;onChange?:(value:string[])=>void};
type ResourceSelectProps=SingleResourceSelectProps|MultipleResourceSelectProps;
/** Paginated search with an independent lookup for an existing off-page selection. */
export function ResourceSelect(props:ResourceSelectProps) {
  const {endpoint,disabled,excludeId,id,labelField="name",currentSelection,optionLabel,max=10_000}=props;
  const value=props.value;
  const multiple=props.multiple===true;
  const onChange=props.onChange as ((value:string|null|string[])=>void)|undefined;
  const {t}=usePreferences();
  const [search,setSearch]=useState("");
  const labels=useRef(new Map<string,{value:string;label:string;disabled?:boolean}>());
  const query=useInfiniteQuery({queryKey:["resource-select",endpoint,search],initialPageParam:1,
    queryFn:({pageParam,signal})=>api<PageResult<NamedResource>>(`${endpoint}?${new URLSearchParams({page:String(pageParam),pageSize:"20",search})}`,{signal}),
    getNextPageParam:page=>page.page*page.pageSize<page.total?page.page+1:undefined});
  const scalarValue=typeof value==="string"?value:undefined;
  const seededSelection=scalarValue&&currentSelection?.itemId===scalarValue?currentSelection:undefined;
  const selected=useQuery({queryKey:["resource-select-detail",endpoint,scalarValue],enabled:!multiple&&!!scalarValue&&!seededSelection,queryFn:({signal})=>api<NamedResource>(`${endpoint}/${scalarValue}`,{signal})});
  useEffect(() => { labels.current.clear(); }, [endpoint, labelField]);
  useEffect(() => {
    for (const item of query.data?.pages.flatMap(page=>page.items)??[]) {
      labels.current.set(item.id, { value:item.id, label:optionLabel?.(item)??item[labelField]??item.displayName??item.username??item.id, disabled:item.enabled===false });
    }
  }, [query.data, labelField, optionLabel]);
  const options=new Map<string,{value:string;label:string;disabled?:boolean}>();
  if(seededSelection)options.set(seededSelection.itemId,{value:seededSelection.itemId,label:seededSelection.label,disabled:!seededSelection.enabled});
  if(selected.data&&!seededSelection)options.set(selected.data.id,{value:selected.data.id,label:optionLabel?.(selected.data)??selected.data[labelField]??selected.data.displayName??selected.data.username??selected.data.id});
  const blockedHistoricalId=currentSelection&&!currentSelection.enabled?currentSelection.itemId:undefined;
  for(const item of query.data?.pages.flatMap(page=>page.items)??[]) if(item.id!==excludeId&&item.id!==seededSelection?.itemId&&item.id!==blockedHistoricalId)options.set(item.id,{value:item.id,label:optionLabel?.(item)??item[labelField]??item.displayName??item.username??item.id,disabled:item.enabled===false});
  if (multiple) {
    const selectedIds=Array.isArray(value)?value:[];
    for(const selectedId of selectedIds) {
      const cached=labels.current.get(selectedId);
      if(cached&&!options.has(selectedId))options.set(selectedId,cached);
    }
  }
  const selectedError=selected.error&&!seededSelection;
  return <Space orientation="vertical" style={{width:"100%"}}>
    <Select mode={multiple?"multiple":undefined} maxCount={multiple?max:undefined} getPopupContainer={trigger=>trigger.parentElement ?? document.body} id={id} style={{width:"100%"}} value={multiple?(Array.isArray(value)?value:undefined):(typeof value==="string"?value:undefined)} disabled={disabled} allowClear showSearch={{filterOption:false,onSearch:setSearch}} onChange={next=>onChange?.(multiple?(Array.isArray(next)?next.slice(0,max):[]):(Array.isArray(next)?null:next??null))} loading={query.isFetching} options={[...options.values()]} placeholder={t("searchCodes")}
      popupRender={menu=><>{menu}{query.hasNextPage && <AsyncButton block pending={query.isFetchingNextPage} onMouseDown={event=>event.preventDefault()} onClick={()=>void query.fetchNextPage()} label={t("loadMore")} />}</>}/>
    {(query.error||selectedError) && <QueryFailure title={t("unavailable")} pending={query.isFetching || selected.isFetching} onRetry={()=>{void query.refetch();if(value&&!seededSelection)void selected.refetch();}}/>}
  </Space>;
}
