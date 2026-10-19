/** 按打开周期固定自定义资料草稿与字段版本。
 * Pin custom profile drafts and schema versions for each opening. */
"use client";
import { useEffect, useRef, useState } from "react";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {ProfileCompletion} from "./profile-completion";
import {DictionarySelect} from "@/platform/components/dictionary-select";
import {Button, Empty, Input, InputNumber, Select, Spin} from "antd";
import {useQuery,useQueryClient} from "@tanstack/react-query";
import {api,mutate} from "@/platform/api/client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import {FormDrawer} from "@/platform/components/form-drawer";
import {usePreferences} from "@/platform/providers";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { preserveProfileFieldValues } from "@/platform/profile-fields/values";
import { profileFieldValueValid } from "@/platform/profile-fields/validation";
type Field={key:string;label:string;description:string;type:'TEXT'|'TEXTAREA'|'NUMBER'|'DATE'|'SELECT';required:boolean;placeholder:string;groupKey:string;groupLabel:string;options:string[];dictionaryCode?:string|null};
type DictionarySelection={itemId:string;label:string;enabled:boolean};
type Snapshot={version:number;schemaVersion:number;fields:Field[];values:Record<string,string>;dictionarySelections?:Record<string,DictionarySelection>};
const uuidPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function profileFormValues(snapshot:Snapshot){
 const values={...snapshot.values};
 for(const field of snapshot.fields){
  if(field.type!=='SELECT'||!field.dictionaryCode)continue;
  const value=values[field.key];const itemId=snapshot.dictionarySelections?.[field.key]?.itemId;
  if(value&&itemId&&uuidPattern.test(value)&&uuidPattern.test(itemId)&&value.toLowerCase()===itemId.toLowerCase())values[field.key]=itemId;
 }
 return values;
}
/** 控件共享服务端字段定义，校验仍以服务端为准。
 * Typed controls share the server schema; server validation remains authoritative. */
function ProfileValueFields({fields,values,dictionarySelections}:{fields:Field[];values:Record<string,string>;dictionarySelections?:Record<string,DictionarySelection>}){
 const {t}=usePreferences();const groups=Map.groupBy(fields,field=>field.groupKey);
 return <>{[...groups].map(([key,items])=><fieldset key={key} className="mb-4 border-0 p-0"><legend>{items[0].groupLabel}</legend>{items.map(field=>{
 const value=values[field.key];const metadata=dictionarySelections?.[field.key];
 const currentSelection=value&&dictionarySelections!==undefined?{itemId:value,label:metadata?.itemId===value?metadata.label:t('unavailable'),enabled:metadata?.itemId===value?metadata.enabled:false}:undefined;
 return <Form.Item key={field.key} name={field.key} label={field.label} extra={field.description||undefined} rules={[{required:field.required,message:t('invalidProfileValues')},{validator:async(_,value:unknown)=>{if(!profileFieldValueValid(field,value))throw new Error(t('invalidProfileValues'));}}]} getValueFromEvent={field.type==='NUMBER'||field.type==='SELECT'?value=>value??'':undefined}>
 {field.type==='SELECT'&&field.dictionaryCode?<DictionarySelect code={field.dictionaryCode} currentSelection={currentSelection}/>:field.type==='SELECT'?<Select allowClear placeholder={field.placeholder} options={field.options.map(value=>({value,label:value}))}/>:field.type==='NUMBER'?<InputNumber stringMode maxLength={40} inputMode="decimal" placeholder={field.placeholder}/>:field.type==='TEXTAREA'?<Input.TextArea rows={4} maxLength={2000} placeholder={field.placeholder}/>:<Input type={field.type==='DATE'?'date':'text'} maxLength={field.type==='DATE'?10:200} placeholder={field.placeholder}/>}</Form.Item>})}</fieldset>)}</>;
}
export function ProfileValuesDrawer({open,onClose}:{open:boolean;onClose:()=>void}){
 const {t}=usePreferences();const client=useQueryClient();const {pending,error,setError,run}=useSubmitAction({errorKeys:["staleRecord","invalidProfileValues"]});
 const query=useQuery({queryKey:['profile-values'],queryFn:({signal})=>api<Snapshot>('/account/profile-values',{signal}),enabled:open,staleTime:0});
 const [snapshot,setSnapshot]=useState<Snapshot|null>(null);
 const generation=useRef(0);const operationSignal=useSessionScope();
 // 每次打开建立独立草稿；后台刷新不卸载表单或更换提交版本。
 // Each opening owns its draft; background refresh never unmounts it or changes its submitted version.
 useEffect(()=>{generation.current+=1;if(!open)setSnapshot(null);return()=>{generation.current+=1;};},[open]);
 useEffect(()=>{if(open&&!snapshot&&query.data&&!query.isFetching&&!query.error)setSnapshot(query.data);},[open,snapshot,query.data,query.isFetching,query.error]);
 const initialValues=snapshot?profileFormValues(snapshot):undefined;
 return <FormDrawer open={open} title={t('fieldCustomGroup')} formId="account-profile-values" pending={pending} submitDisabled={!snapshot||!!query.error||snapshot.fields.length===0} onClose={()=>{setError(null);onClose();}}>
 <ProfileCompletion open={open}/>
 {!snapshot&&query.isFetching&&<Spin/>}<FeedbackBubble id="account-profile-values-error" content={error ? t(error) : query.error ? t("unavailable") : undefined}/>
 {query.error&&<Button onClick={()=>void query.refetch()}>{t('retry')}</Button>}
 {snapshot&&initialValues&&<Form id="account-profile-values" key={`${snapshot.version}-${snapshot.schemaVersion}`} layout="vertical" disabled={pending} initialValues={initialValues} onFinish={values=>run(async()=>{
const signal=operationSignal();const started=generation.current;if(signal.aborted)return;
await mutate('/account/profile-values','PUT',{version:snapshot.version,schemaVersion:snapshot.schemaVersion,values:preserveProfileFieldValues(values,snapshot.fields,initialValues)},signal);if(signal.aborted||started!==generation.current)return;onClose();await Promise.all([client.invalidateQueries({queryKey:['profile-completion']}),client.invalidateQueries({queryKey:['profile-values']}),client.invalidateQueries({queryKey:['profile']}),client.invalidateQueries({queryKey:['users']})]);
 })}>{snapshot.fields.length?<ProfileValueFields fields={snapshot.fields} values={initialValues} dictionarySelections={snapshot.dictionarySelections}/>:<Empty description={t('noCustomProfileFields')}/>}</Form>}
 </FormDrawer>;
}
