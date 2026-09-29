"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {ProfileCompletion} from "./profile-completion";
import {DictionarySelect} from "@/platform/components/dictionary-select";
import {Button, Empty, Input, InputNumber, Select, Spin} from "antd";
import {useState} from "react";
import {useQuery,useQueryClient} from "@tanstack/react-query";
import {api,ApiError,mutate} from "@/platform/api/client";
import {FormDrawer} from "@/platform/components/form-drawer";
import {usePreferences} from "@/platform/providers";
import type {MessageKey} from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
type Field={key:string;label:string;description:string;type:'TEXT'|'TEXTAREA'|'NUMBER'|'DATE'|'SELECT';required:boolean;placeholder:string;groupKey:string;groupLabel:string;options:string[];dictionaryCode?:string|null};
type Snapshot={version:number;schemaVersion:number;fields:Field[];values:Record<string,string>};
/** Typed controls share the server schema; the server remains authoritative for all validation. */
function ProfileValueFields({fields}:{fields:Field[]}){
 const {t}=usePreferences();const groups=Map.groupBy(fields,field=>field.groupKey);
 return <>{[...groups].map(([key,items])=><fieldset key={key} className="mb-4 border-0 p-0"><legend>{items[0].groupLabel}</legend>{items.map(field=><Form.Item key={field.key} name={field.key} label={field.label} extra={field.description||undefined} rules={[{required:field.required,message:t('invalidProfileValues')}]} getValueFromEvent={field.type==='NUMBER'||field.type==='SELECT'?value=>value??'':undefined}>
 {field.type==='SELECT'&&field.dictionaryCode?<DictionarySelect code={field.dictionaryCode}/>:field.type==='SELECT'?<Select allowClear placeholder={field.placeholder} options={field.options.map(value=>({value,label:value}))}/>:field.type==='NUMBER'?<InputNumber stringMode placeholder={field.placeholder}/>:field.type==='TEXTAREA'?<Input.TextArea rows={4} maxLength={2000} placeholder={field.placeholder}/>:<Input type={field.type==='DATE'?'date':'text'} maxLength={200} placeholder={field.placeholder}/>}</Form.Item>)}</fieldset>)}</>;
}
export function ProfileValuesDrawer({open,onClose}:{open:boolean;onClose:()=>void}){
 const {t}=usePreferences();const client=useQueryClient();const [pending,setPending]=useState(false),[error,setError]=useState<MessageKey|null>(null);
 const query=useQuery({queryKey:['profile-values'],queryFn:({signal})=>api<Snapshot>('/account/profile-values',{signal}),enabled:open,staleTime:0});
 return <FormDrawer open={open} title={t('fieldCustomGroup')} formId="account-profile-values" pending={pending} submitDisabled={!query.data||query.isFetching||!!query.error||query.data.fields.length===0} onClose={()=>{setError(null);onClose();}}>
 <ProfileCompletion open={open}/>
 {query.isFetching&&<Spin/>}<FeedbackBubble id="account-profile-values-error" content={error ? t(error) : query.error ? t("unavailable") : undefined}/>
 {!query.data&&query.error&&<Button onClick={()=>void query.refetch()}>{t('retry')}</Button>}
 {query.data&&!query.isFetching&&<Form id="account-profile-values" key={`${query.data.version}-${query.data.schemaVersion}`} layout="vertical" disabled={pending} initialValues={query.data.values} onFinish={async values=>{
  if(pending)return;setPending(true);setError(null);
  try{await mutate('/account/profile-values','PUT',{version:query.data.version,schemaVersion:query.data.schemaVersion,values:Object.fromEntries(query.data.fields.map(field=>[field.key,values[field.key]??'']))});onClose();await Promise.all([client.invalidateQueries({queryKey:['profile-completion']}),client.invalidateQueries({queryKey:['profile-values']}),client.invalidateQueries({queryKey:['profile']}),client.invalidateQueries({queryKey:['users']})]);}
  catch(e){const key=e instanceof ApiError?e.message:'unavailable';setError(key==='staleRecord'||key==='invalidProfileValues'?key:'unavailable');}finally{setPending(false);}
 }}>{query.data.fields.length?<ProfileValueFields fields={query.data.fields}/>:<Empty description={t('noCustomProfileFields')}/>}</Form>}
 </FormDrawer>;
}
