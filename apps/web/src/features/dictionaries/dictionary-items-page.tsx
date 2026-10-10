"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useListLocation,useLocationParams } from "@/platform/routing/use-list-location";
import { locationId } from "@/platform/routing/list-location";
import { useQuery,useQueryClient } from "@tanstack/react-query";
import {Alert, Breadcrumb, Button, Input, InputNumber, Select, Space, Spin, Switch, Tag, Typography} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api,mutate } from "@/platform/api/client";
import { useCrudDrawer } from "@/platform/forms/use-crud-drawer";
import { saveRecord, pageAfterRemoval } from "@/platform/api/records";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { ResourceSelect } from "@/platform/components/resource-select";
import { FormDrawer } from "@/platform/components/form-drawer";
import { DeleteAction } from "@/platform/components/delete-action";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import type { Dictionary } from "./dictionaries-page";
import { QueryFailure } from "@/platform/components/query-failure";
import { listRequestParams } from "@/platform/api/pagination";

const dictionaryItemErrorKeys = ["invalidDictionaryItem", "dictionaryFlat", "dictionaryCycle", "dictionaryItemInUse", "staleRecord", "forbidden"] as const satisfies readonly MessageKey[];
const dictionaryItemErrorAliases = { duplicateRecord: "duplicateDictionaryItem" } as const satisfies Readonly<Record<string, MessageKey>>;
const feedbackStatusValues = new Set(["OPEN", "PROCESSING", "RESOLVED", "CLOSED"]);

type Item={id:string;parentId:string|null;value:string;label:string;sortOrder:number;enabled:boolean;remark:string;version:number};
export function DictionaryItemsPage({dictionary,canWrite,onBack}:{dictionary:Dictionary;canWrite:boolean;onBack:()=>void}) {
  const {t}=usePreferences();const client=useQueryClient();const endpoint=`/platform/dictionaries/${dictionary.id}/items`;const lookupEndpoint=`/dictionaries/${encodeURIComponent(dictionary.code)}/items`;
  const [request,setRequest]=useListLocation("items.");
  const [params,updateLocation]=useLocationParams();
  const parent=dictionary.structure!=="TREE" || params.get("items.parent")==="all"?null:locationId(params.get("items.parent"))??"root";
  const navigate=(id:string|null)=>updateLocation({"items.parent":id==="root"?null:id??"all","items.page":null,"items.search":null});
  const ancestors=useQuery({queryKey:["dictionary-items",dictionary.id,"ancestors",parent],enabled:!!parent && parent!=="root",queryFn:({signal})=>api<Item[]>(`${endpoint}/${parent}/ancestors`,{signal})});
  const {selected,setSelected,pending,error,close,saved,run}=useCrudDrawer<string>({errorKeys:dictionaryItemErrorKeys,errorAliases:dictionaryItemErrorAliases});
  const query=useQuery({queryKey:["dictionary-items",dictionary.id,parent,request],queryFn:({signal})=>api<PageResult<Item>>(`${endpoint}?${listRequestParams(request,{...(parent?{parent}:{})})}`,{signal})});
  const detail=useQuery({queryKey:["dictionary-item",dictionary.id,selected],enabled:typeof selected==="string",queryFn:({signal})=>api<Item>(`${endpoint}/${selected}`,{signal})});
  const feedbackStatus=dictionary.code==="work_order_feedback_status";
  const feedbackDefault=dictionary.code==="work_order_feedback_default";
  const lockedLegacyStatus=feedbackStatus && typeof selected==="string" && !!detail.data && !feedbackStatusValues.has(detail.data.value);
  const sensitiveCategory=dictionary.code==="sensitive_word_category";
  const canCreateItem=canWrite && !feedbackStatus && !feedbackDefault;
  const canDeleteItem=canWrite && !feedbackStatus && !feedbackDefault;
  const ready=selected===null || (!!detail.data && !detail.isFetching && !detail.error);
  const refresh=()=>Promise.all([client.invalidateQueries({queryKey:["dictionary-items",dictionary.id]}),client.invalidateQueries({queryKey:["resource-select",endpoint]}),client.invalidateQueries({queryKey:["resource-select-detail",endpoint]}),client.invalidateQueries({queryKey:["resource-select",lookupEndpoint]}),client.invalidateQueries({queryKey:["resource-select-detail",lookupEndpoint]}),client.invalidateQueries({queryKey:["feedback-options"]}),client.invalidateQueries({queryKey:["dictionary-options"]})]);
  return <>
    <Space className="mb-4"><Button onClick={onBack}>{t("dictionaries")}</Button><Typography.Text strong>{dictionary.name} · {t("dictionaryItems")}</Typography.Text></Space>
    {dictionary.structure==="TREE" && <Space orientation="vertical" className="mb-4">
      <Select aria-label={t("dictionaryItems")} value={parent?"tree":"all"} onChange={value=>navigate(value==="all"?null:"root")} options={[{value:"tree",label:t("treeDictionary")},{value:"all",label:t("dictionaryItems")}]} />
      {parent && <Breadcrumb items={[{title:<Button type="link" onClick={()=>navigate("root")}>{t("treeDictionary")}</Button>},...(ancestors.data??[]).map(item=>({title:<Button type="link" onClick={()=>navigate(item.id)}>{item.label}</Button>}))]} />}
      {ancestors.error && <QueryFailure title={t("unavailable")} pending={ancestors.isFetching} onRetry={()=>void ancestors.refetch()} />}
    </Space>}
    <ResourceTable<Item> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()}
      actions={canCreateItem && <Button type="primary" onClick={()=>setSelected(null)}>{t("createDictionaryItem")}</Button>}
      columns={[{title:t("itemValue"),dataIndex:"value",search:true},{title:t("itemLabel"),dataIndex:"label", search: true,render:(_,row)=>dictionary.structure==="TREE"?<Button type="link" aria-label={`${t("viewChildren")}: ${row.label}`} onClick={()=>navigate(row.id)}>{row.label}</Button>:row.label},{title:t("sortOrder"),dataIndex:"sortOrder",valueType:"digit"},{title:t("status"),dataIndex:"enabled", search: true,valueEnum:{true:{text:t("enabled")},false:{text:t("disabled")}},render:(_,row)=><Tag color={row.enabled?"success":"default"}>{t(row.enabled?"enabled":"disabled")}</Tag>},
        {title:t("actions"), key: "actions",search:false,render:(_,row)=><TableActions><Button type="link" onClick={()=>setSelected(row.id)}>{t(canWrite && !(feedbackStatus && !feedbackStatusValues.has(row.value))?"edit":"details")}</Button>{canDeleteItem && <DeleteAction name={row.label} onDelete={async()=>{
          await mutate(`${endpoint}/${row.id}?version=${row.version}`,"DELETE",{});setRequest(pageAfterRemoval(request,query.data?.items.length));await refresh();
        }}/>}</TableActions>}]} />
    <FormDrawer open={selected!==undefined} title={t(lockedLegacyStatus?"details":selected?"dictionaryItems":"createDictionaryItem")} formId="dictionary-item-editor" pending={pending} submitDisabled={!ready || !canWrite || lockedLegacyStatus} onClose={close}>
      <FeedbackBubble id="dictionary-item-editor-error" content={error ? t(error) : selected && detail.error ? t("unavailable") : undefined}/>
      {selected && detail.isFetching && <Spin />}
        {ready && <Form key={selected??"new"} id="dictionary-item-editor" layout="vertical" disabled={pending || !canWrite || lockedLegacyStatus} initialValues={selected?detail.data:{enabled:true,sortOrder:0,parentId:parent && parent!=="root"?parent:null,remark:""}} onFinish={values=>run(async()=>{
await saveRecord(endpoint,selected,{...values,parentId:values.parentId??null,version:selected?detail.data?.version:undefined});saved();await refresh();
      })}>
        <Form.Item name="value" label={t("itemValue")} rules={[{required:true,whitespace:true,message:t("invalidDictionaryItem")},...(sensitiveCategory?[{validator:(_:unknown,value?:string)=>!value || Array.from(value.trim()).length<=64?Promise.resolve():Promise.reject(new Error(t("invalidDictionaryItem")))}]:[]),...(["work_order_feedback_category","work_order_feedback_priority","work_order_feedback_status"].includes(dictionary.code)?[{pattern:/^[A-Z][A-Z0-9_]{0,31}$/,message:t("invalidDictionaryItem")}]:[])]}><Input maxLength={sensitiveCategory?128:200} readOnly={!!selected && ["work_order_feedback_category","work_order_feedback_priority","work_order_feedback_status","work_order_feedback_default"].includes(dictionary.code)} /></Form.Item>
        <Form.Item name="label" label={t("itemLabel")} rules={[{required:true,whitespace:true,message:t("invalidDictionaryItem")}]}><Input maxLength={200} readOnly={feedbackDefault && !!selected && !["INITIAL_STATUS","DEFAULT_PRIORITY"].includes(detail.data?.value??"")} /></Form.Item>
        {dictionary.structure==="TREE" && <Form.Item name="parentId" label={t("parentItem")}><ResourceSelect endpoint={endpoint} labelField="label" excludeId={selected??undefined} disabled={pending || !canWrite} /></Form.Item>}
        <Form.Item name="sortOrder" label={t("sortOrder")} rules={[{required:true,message:t("invalidDictionaryItem")}]}><InputNumber min={-2147483648} max={2147483647} precision={0} /></Form.Item>
        <Form.Item name="remark" label={t("remark")}><Input.TextArea maxLength={1000} showCount readOnly={feedbackStatus && !!selected} /></Form.Item>
        <Form.Item name="enabled" label={t("enabled")} valuePropName="checked"><Switch disabled={feedbackDefault || (feedbackStatus && (!selected || !!detail.data?.enabled))} /></Form.Item>
      </Form>}
    </FormDrawer>
  </>;
}
