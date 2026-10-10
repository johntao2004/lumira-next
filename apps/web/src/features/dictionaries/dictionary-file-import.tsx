"use client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {useRef,useState} from "react";
import {Button, Table, Upload} from "antd";
import type {UploadFile, UploadProps} from "antd";
import {UploadOutlined} from "@ant-design/icons";
import {useQueryClient} from "@tanstack/react-query";
import {upload,ApiError} from "@/platform/api/client";
import {FormDrawer} from "@/platform/components/form-drawer";
import {AsyncButton} from "@/platform/components/async-button";
import {usePreferences} from "@/platform/providers";
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZE_OPTIONS } from "@/platform/api/pagination";
import {errorKey} from "./dictionary-transfer";
import {FeedbackBubble} from "@/platform/components/feedback-bubble";
import { useSessionScope } from "@/platform/auth/use-session-scope";

import {DictionaryFields,dictionaryDefaults,type DictionaryMetadata as Metadata} from "./dictionary-fields";
type Preview={sha256:string;summary:{itemCount:number};rows:{value:string;label:string;parentValue:string|null}[]};
export function DictionaryFileImport(){
 const {t}=usePreferences();const client=useQueryClient();const [form]=Form.useForm<Metadata>();
 const operationSignal=useSessionScope();
 const generation=useRef(0);const [open,setOpen]=useState(false),[checking,setChecking]=useState(false);
 const [file,setFile]=useState<File|null>(null);
 const [fileList,setFileList]=useState<UploadFile[]>([]);
 const [preview,setPreview]=useState<{result:Preview;query:string;file:File}|null>(null);
 const [issues,setIssues]=useState<ApiError["issues"]>([]);
 const {pending,error,setError,run}=useSubmitAction({mapError:errorKey,onError:(_key,e)=>{setIssues(e instanceof ApiError?e.issues:[]);}});
 const showError=(e:unknown)=>{setError(errorKey(e));setIssues(e instanceof ApiError?e.issues:[]);};
 const invalidate=()=>{generation.current++;setPreview(null);setError(null);setIssues([]);setChecking(false);};
 const close=()=>{invalidate();setOpen(false);setFile(null);setFileList([]);form.resetFields();};
 const beforeUpload:UploadProps["beforeUpload"]=(selected)=>{
  invalidate();
  if(selected.size>20*1024*1024){setError("datasetTooLarge");setFile(null);setFileList([]);return Upload.LIST_IGNORE;}
  setFile(selected);return false;
 };
 const check=async()=>{
  invalidate();const current=generation.current;const signal=operationSignal();
  if(signal.aborted)return;
  let values:Metadata;try{values=await form.validateFields();}catch{return;}
  if(signal.aborted||!file||current!==generation.current)return;
  setChecking(true);
  const query=new URLSearchParams({...values,enabled:String(values.enabled),filename:file.name}).toString();
  try{const result=await upload<Preview>(`/platform/dictionaries/file-preview?${query}`,file,signal);if(!signal.aborted&&current===generation.current)setPreview({result,query,file});}
  catch(e){if(!signal.aborted&&current===generation.current)showError(e);}finally{if(!signal.aborted&&current===generation.current)setChecking(false);}
 };
 return <><Button onClick={()=>setOpen(true)}>{t("importDictionary")} · TXT / Excel</Button>
 <FormDrawer open={open} title={`${t("importDictionary")} · TXT / Excel`} pending={pending} submitDisabled={!preview||checking} onClose={close} formId="dictionary-file-import">
 <Form form={form} id="dictionary-file-import" layout="vertical" disabled={pending} initialValues={dictionaryDefaults} onValuesChange={invalidate} onFinish={()=>{
  const signal=operationSignal();
  return run(async()=>{
  if(!preview||checking||signal.aborted)return false;
  try {
   await upload(`/platform/dictionaries/file-import?${preview.query}&expectedSha256=${preview.result.sha256}`,preview.file,signal);
   if(signal.aborted)return false;
   close();
   await Promise.all([client.invalidateQueries({queryKey:["dictionaries"]}),client.invalidateQueries({queryKey:["dictionary-options"]}) ]);
  } catch(error) { if(signal.aborted)return false; throw error; }
 });}}>
 <DictionaryFields/>
 <Form.Item label={t("chooseDataset")} required><Upload accept=".txt,.xls,.xlsx" maxCount={1} fileList={fileList} disabled={pending} beforeUpload={beforeUpload} onChange={({fileList:next})=>setFileList(next.slice(-1))} onRemove={()=>{invalidate();setFile(null);setFileList([]);return true;}}><Button disabled={pending} icon={<UploadOutlined />} aria-label={t("chooseDataset")}>{t("uploadFile")}</Button></Upload></Form.Item>
 <AsyncButton disabled={!file||pending} pending={checking} onClick={()=>void check()} label={t("previewDataset")} />
 </Form>
 <FeedbackBubble id="dictionary-file-import-error" content={error ? t(error) : undefined}/>
 {issues.length>0&&<Table size="small" rowKey={item=>`${item.row}-${item.field}-${item.reason}`} pagination={{defaultPageSize:DEFAULT_TABLE_PAGE_SIZE,showSizeChanger:true,pageSizeOptions:[...TABLE_PAGE_SIZE_OPTIONS]}} dataSource={issues} columns={[{title:"#",dataIndex:"row"},{title:t("name"),dataIndex:"field"},{title:t("details"),dataIndex:"reason",render:(reason:string)=>t(reason==="duplicate"?"duplicateDictionaryItem":reason==="hierarchy"?"importHierarchy":"importInvalidField")}]} />}
 {preview&&<><p>{t("dictionaryItems")}: {preview.result.summary.itemCount}</p><Table size="small" rowKey="value" pagination={{defaultPageSize:DEFAULT_TABLE_PAGE_SIZE,showSizeChanger:true,pageSizeOptions:[...TABLE_PAGE_SIZE_OPTIONS]}} dataSource={preview.result.rows} columns={[{title:t("itemValue"),dataIndex:"value"},{title:t("name"),dataIndex:"label"}]} /></>}
 </FormDrawer></>;
}
