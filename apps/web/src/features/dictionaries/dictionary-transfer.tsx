"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useRef,useState } from "react";
import {ProDescriptions} from "@ant-design/pro-components";
import {App, Button, Spin} from "antd";
import { useQueryClient } from "@tanstack/react-query";
import { api,ApiError,mutate } from "@/platform/api/client";
import { downloadJson } from "@/platform/files/download-json";
import { FormDrawer } from "@/platform/components/form-drawer";
import { AsyncButton } from "@/platform/components/async-button";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";

export function errorKey(e:unknown):MessageKey {
  if(e instanceof ApiError) {
    if(e.message==="duplicateRecord")return "duplicateDictionary";
    if(e.message==="dictionaryDatasetTooLarge")return "datasetTooLarge";
    if(e.status===400)return "invalidDataset";
    if(e.status===403)return "forbidden";
  }
  return e instanceof SyntaxError?"invalidDataset":"unavailable";
}
export function DictionaryExport({id}:{id:string}) {
  const {t}=usePreferences();const {message}=App.useApp();const [pending,setPending]=useState(false);
  return <AsyncButton type="link" pending={pending} label={t("exportDictionary")} onClick={async()=>{setPending(true);try{downloadJson(`dictionary-${id}.json`,await api(`/platform/dictionaries/${id}/export`));}catch(e){void message.error(t(errorKey(e)));}finally{setPending(false);}}} />;
}
export function DictionaryImport() {
  const {t}=usePreferences();const client=useQueryClient();const generation=useRef(0);
  const [open,setOpen]=useState(false),[pending,setPending]=useState(false),[reading,setReading]=useState(false);
  const [error,setError]=useState<MessageKey|null>(null);
  const [preview,setPreview]=useState<{data:unknown;code:string;name:string;structure:string;itemCount:number}|null>(null);
  const close=()=>{generation.current++;setOpen(false);setPreview(null);setError(null);setReading(false);};
  return <><Button onClick={()=>setOpen(true)}>{t("importDictionary")}</Button>
    <FormDrawer title={t("importDictionary")} open={open} onClose={close} pending={pending} submitDisabled={!preview || reading} formId="dictionary-import">
      <Form.Item label={t("chooseDataset")} extra={t("datasetHint")}>
      <input aria-label={t("chooseDataset")} type="file" accept=".json,application/json" disabled={pending} onChange={async event=>{
        const file=event.target.files?.[0];const current=++generation.current;setPreview(null);setError(null);if(!file){setReading(false);return;}
        if(file.size>20*1024*1024){setError("datasetTooLarge");setReading(false);return;}
        setReading(true);
        try {const data=JSON.parse(await file.text());const result=await mutate<{code:string;name:string;structure:string;itemCount:number}>("/platform/dictionaries/preview","POST",data);if(current===generation.current)setPreview({...result,data});}
        catch(e){if(current===generation.current)setError(errorKey(e));}finally{if(current===generation.current)setReading(false);}
      }} />
      </Form.Item>
      {reading && <Spin size="small" />}
      <FeedbackBubble id="dictionary-import-error" content={error ? t(error) : undefined}/>
      {preview && <ProDescriptions column={1} dataSource={preview} columns={[{dataIndex:"code",title:t("dictionaryCode")},{dataIndex:"name",title:t("name")},{dataIndex:"structure",title:t("dictionaryStructure"),render:()=>t(preview.structure==="TREE"?"treeDictionary":"flatDictionary")},{dataIndex:"itemCount",title:t("dictionaryItems")}]} />}
    <Form id="dictionary-import" onFinish={async()=>{if(!preview || reading || pending)return;setPending(true);setError(null);try{await mutate("/platform/dictionaries/import","POST",preview.data);close();await Promise.all([client.invalidateQueries({queryKey:["dictionaries"]}),client.invalidateQueries({queryKey:["dictionary-options"]})]);}catch(e){setError(errorKey(e));}finally{setPending(false);}}} />
    </FormDrawer>
  </>;
}
