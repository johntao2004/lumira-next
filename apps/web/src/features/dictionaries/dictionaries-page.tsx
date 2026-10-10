"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { DictionaryFileImport } from "./dictionary-file-import";
import { DictionaryImport,DictionaryExport } from "./dictionary-transfer";
import { DictionaryFields,dictionaryDefaults } from "./dictionary-fields";
import { DictionaryItemsPage } from "./dictionary-items-page";
import { useListLocation,useLocationParams } from "@/platform/routing/use-list-location";
import { locationId } from "@/platform/routing/list-location";
import { useQuery,useQueryClient } from "@tanstack/react-query";
import {Button, Space, Spin, Tag} from "antd";
import { TableActions } from "@/platform/components/table-actions";
import { api,mutate } from "@/platform/api/client";
import { useCrudDrawer } from "@/platform/forms/use-crud-drawer";
import { saveRecord, pageAfterRemoval } from "@/platform/api/records";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { FormDrawer } from "@/platform/components/form-drawer";
import { DeleteAction } from "@/platform/components/delete-action";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { listRequestParams } from "@/platform/api/pagination";

const dictionaryErrorKeys = ["invalidDictionary", "dictionaryFlat", "dictionaryInUse", "staleRecord", "forbidden"] as const satisfies readonly MessageKey[];
const dictionaryErrorAliases = { duplicateRecord: "duplicateDictionary" } as const satisfies Readonly<Record<string, MessageKey>>;
const policyDictionaryCodes = new Set(["work_order_feedback_status","work_order_feedback_priority","work_order_feedback_default","work_order_feedback_category","sensitive_word_category"]);

export type Dictionary={id:string;code:string;name:string;enabled:boolean;structure:"FLAT"|"TREE";remark:string;version:number};
export function DictionariesPage({canWrite}:{canWrite:boolean}) {
  const {t}=usePreferences();const client=useQueryClient();
  const [request,setRequest]=useListLocation();
  const [params,updateLocation]=useLocationParams();
  const dictionaryId=locationId(params.get("dictionary"));
  const itemType=useQuery({queryKey:["dictionary",dictionaryId],enabled:!!dictionaryId,queryFn:({signal})=>api<Dictionary>(`/platform/dictionaries/${dictionaryId}`,{signal})});
  const showItems=(id:string|null)=>updateLocation({dictionary:id,"items.page":null,"items.search":null,"items.parent":null});
  const {selected,setSelected,pending,error,close,saved,run}=useCrudDrawer<string>({errorKeys:dictionaryErrorKeys,errorAliases:dictionaryErrorAliases});
  const query=useQuery({queryKey:["dictionaries",request],queryFn:({signal})=>api<PageResult<Dictionary>>(`/platform/dictionaries?${listRequestParams(request)}`,{signal})});
  const detail=useQuery({queryKey:["dictionary",selected],enabled:typeof selected==="string",queryFn:({signal})=>api<Dictionary>(`/platform/dictionaries/${selected}`,{signal})});
  const policyLocked=!!selected && !!detail.data && policyDictionaryCodes.has(detail.data.code);
  const ready=selected===null || (!!detail.data && !detail.isFetching && !detail.error);
  const refresh=()=>Promise.all([client.invalidateQueries({queryKey:["dictionaries"]}),client.invalidateQueries({queryKey:["feedback-options"]}),client.invalidateQueries({queryKey:["dictionary-options"]}),client.invalidateQueries({queryKey:["resource-select"]}),client.invalidateQueries({queryKey:["resource-select-detail"]})]);
  if(dictionaryId) {
    if(itemType.isPending)return <Spin />;
    if(!itemType.data || itemType.error)return <><FeedbackBubble content={t("unavailable")}/><Button onClick={()=>showItems(null)}>{t("dictionaries")}</Button></>;
    return <DictionaryItemsPage key={dictionaryId} dictionary={itemType.data} canWrite={canWrite} onBack={()=>showItems(null)} />;
  }
  return <>
    <ResourceTable<Dictionary> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()}
      actions={canWrite && <Space><Button type="primary" onClick={()=>setSelected(null)}>{t("createDictionary")}</Button><DictionaryImport /><DictionaryFileImport /></Space>}
      columns={[{title:t("dictionaryCode"),dataIndex:"code",search:true},{title:t("name"),dataIndex:"name", search: true},{title:t("dictionaryStructure"),dataIndex:"structure",valueEnum:{TREE:{text:t("treeDictionary")},FLAT:{text:t("flatDictionary")}},render:(_,row)=>t(row.structure==="TREE"?"treeDictionary":"flatDictionary")},{title:t("status"),dataIndex:"enabled", search: true,valueEnum:{true:{text:t("enabled")},false:{text:t("disabled")}},render:(_,row)=><Tag color={row.enabled?"success":"default"}>{t(row.enabled?"enabled":"disabled")}</Tag>},
        {title:t("actions"), key: "actions",search:false,render:(_,row)=><TableActions><DictionaryExport id={row.id} /><Button type="link" onClick={()=>showItems(row.id)}>{t("dictionaryItems")}</Button><Button type="link" onClick={()=>setSelected(row.id)}>{t(canWrite?"edit":"details")}</Button>{canWrite && !policyDictionaryCodes.has(row.code) && <DeleteAction name={row.name} onDelete={async()=>{
          await mutate(`/platform/dictionaries/${row.id}?version=${row.version}`,"DELETE",{});setRequest(pageAfterRemoval(request,query.data?.items.length));await refresh();
        }}/>}</TableActions>}]} />
    <FormDrawer open={selected!==undefined} title={t(selected?"dictionaries":"createDictionary")} formId="dictionary-editor" pending={pending} submitDisabled={!ready || !canWrite} onClose={close}>
      <FeedbackBubble id="dictionary-editor-error" content={error ? t(error) : detail.error && selected !== null ? t("unavailable") : undefined}/>
      {selected && detail.isFetching && <Spin />}
      {ready && <Form key={selected??"new"} id="dictionary-editor" layout="vertical" disabled={pending || !canWrite} initialValues={selected?detail.data:dictionaryDefaults} onFinish={values=>run(async()=>{
await saveRecord("/platform/dictionaries",selected,{...values,version:selected?detail.data?.version:undefined});saved();await refresh();
      })}>
        <DictionaryFields codeReadOnly={!!selected} policyLocked={policyLocked} policyEnabledLocked={policyLocked && !!detail.data?.enabled}/>
      </Form>}
    </FormDrawer>
  </>;
}
