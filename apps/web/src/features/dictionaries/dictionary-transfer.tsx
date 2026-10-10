"use client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useRef,useState } from "react";
import {ProDescriptions} from "@ant-design/pro-components";
import {App, Button, Spin, Upload} from "antd";
import type {UploadFile, UploadProps} from "antd";
import {UploadOutlined} from "@ant-design/icons";
import { useQueryClient } from "@tanstack/react-query";
import { api,ApiError,mutate } from "@/platform/api/client";
import { errorCode } from "@/platform/api/errors";
import { downloadJson } from "@/platform/files/download-json";
import { FormDrawer } from "@/platform/components/form-drawer";
import { AsyncButton } from "@/platform/components/async-button";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { useSessionScope } from "@/platform/auth/use-session-scope";

export function errorKey(e:unknown):MessageKey {
  if(errorCode(e)==="sensitiveContent")return "sensitiveContent";
  if(e instanceof ApiError) {
    if(e.message==="duplicateRecord")return "duplicateDictionary";
    if(e.message==="dictionaryDatasetTooLarge")return "datasetTooLarge";
    if(e.status===400)return "invalidDataset";
    if(e.status===403)return "forbidden";
  }
  return e instanceof SyntaxError?"invalidDataset":"unavailable";
}
export function DictionaryExport({id}:{id:string}) {
  const {t}=usePreferences();const {message}=App.useApp();const {pending,run}=useSubmitAction({mapError:errorKey,onError:key=>{void message.error(t(key));}});
  return <AsyncButton type="link" pending={pending} label={t("exportDictionary")} onClick={()=>run(async()=>{downloadJson(`dictionary-${id}.json`,await api(`/platform/dictionaries/${id}/export`));})} />;
}
export function DictionaryImport() {
  const {t}=usePreferences();const client=useQueryClient();const generation=useRef(0);const operationSignal=useSessionScope();
  const [open,setOpen]=useState(false),[reading,setReading]=useState(false);
  const {pending,error,setError,run}=useSubmitAction({mapError:errorKey});
  const [fileList,setFileList]=useState<UploadFile[]>([]);
  const [preview,setPreview]=useState<{data:unknown;code:string;name:string;structure:string;itemCount:number}|null>(null);
  const clearSelection=()=>{generation.current++;setFileList([]);setPreview(null);setError(null);setReading(false);};
  const close=()=>{clearSelection();setOpen(false);};
  const beforeUpload:UploadProps["beforeUpload"]=(file)=>{
    const current=++generation.current;const signal=operationSignal();setPreview(null);setError(null);
    if(signal.aborted)return Upload.LIST_IGNORE;
    if(file.size>20*1024*1024){setFileList([]);setError("datasetTooLarge");setReading(false);return Upload.LIST_IGNORE;}
    setFileList([{uid:file.uid,name:file.name,size:file.size,originFileObj:file}]);setReading(true);
    void (async()=>{
      try {
        const contents=await file.text();
        if(signal.aborted||current!==generation.current)return;
        const data=JSON.parse(contents);
        if(signal.aborted||current!==generation.current)return;
        const result=await mutate<{code:string;name:string;structure:string;itemCount:number}>("/platform/dictionaries/preview","POST",data,signal);
        if(!signal.aborted&&current===generation.current)setPreview({...result,data});
      }
      catch(e){if(!signal.aborted&&current===generation.current)setError(errorKey(e));}
      finally{if(!signal.aborted&&current===generation.current)setReading(false);}
    })();
    return false;
  };
  return <><Button onClick={()=>setOpen(true)}>{t("importDictionary")}</Button>
    <FormDrawer title={t("importDictionary")} open={open} onClose={close} pending={pending} submitDisabled={!preview || reading} formId="dictionary-import">
      <Form.Item label={t("chooseDataset")} extra={t("datasetHint")}>
      <Upload accept=".json,application/json" maxCount={1} fileList={fileList} disabled={pending} beforeUpload={beforeUpload} onChange={({fileList:next})=>setFileList(next.slice(-1))} onRemove={()=>{clearSelection();return true;}}><Button disabled={pending} icon={<UploadOutlined />} aria-label={t("chooseDataset")}>{t("uploadFile")}</Button></Upload>
      </Form.Item>
      {reading && <Spin size="small" />}
      <FeedbackBubble id="dictionary-import-error" content={error ? t(error) : undefined}/>
      {preview && <ProDescriptions column={1} dataSource={preview} columns={[{dataIndex:"code",title:t("dictionaryCode")},{dataIndex:"name",title:t("name")},{dataIndex:"structure",title:t("dictionaryStructure"),render:()=>t(preview.structure==="TREE"?"treeDictionary":"flatDictionary")},{dataIndex:"itemCount",title:t("dictionaryItems")}]} />}
    <Form id="dictionary-import" onFinish={()=>{
      const signal=operationSignal();
      return run(async()=>{
        if(!preview || reading || signal.aborted)return false;
        try {
          await mutate("/platform/dictionaries/import","POST",preview.data,signal);
          if(signal.aborted)return false;
          close();
          await Promise.all([client.invalidateQueries({queryKey:["dictionaries"]}),client.invalidateQueries({queryKey:["dictionary-options"]})]);
        } catch(error) { if(signal.aborted)return false; throw error; }
      });
    }} />
    </FormDrawer>
  </>;
}
