"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {SecurityEventsDrawer} from "./security-events-drawer";
import {EmailDrawer} from "./email-drawer";
import {PhoneDrawer} from "./phone-drawer";
import {ProfileCompletion} from "./profile-completion";
import {AvatarEditor} from "./avatar-editor";
import {ProfileValuesDrawer} from "./profile-values-drawer";
import {PasskeysDrawer} from "./passkeys-drawer";
import {Alert, App, Button, Input, Spin} from "antd";
import { useState } from "react";
import { useQuery,useQueryClient } from "@tanstack/react-query";
import { api,ApiError,mutate } from "@/platform/api/client";
import { FormDrawer } from "@/platform/components/form-drawer";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";

type BuiltInField={key:"displayName"|"realName"|"birthDate"|"region";label:string;description:string;placeholder:string;required:boolean;groupKey:string;groupLabel:string};
type Profile={fields:BuiltInField[];schemaVersion:number;username:string;displayName:string;realName:string;birthMonth:string;region:string;version:number};
const builtInLabelKeys={displayName:"name",realName:"realName",birthDate:"birthMonth",region:"region"} as const;
const builtInDefaultLabels={displayName:"Display name",realName:"Real name",birthDate:"Birth date",region:"Region"} as const;
export function ProfileDrawer({open,onClose}:{open:boolean;onClose:()=>void}) {
  const [securityOpen,setSecurityOpen]=useState(false);
  const [emailOpen,setEmailOpen]=useState(false);
  const [phoneOpen,setPhoneOpen]=useState(false);
  const [passkeyOpen,setPasskeyOpen]=useState(false);
  const [customOpen,setCustomOpen]=useState(false),[dirty,setDirty]=useState(false);const {modal}=App.useApp();
  const {t}=usePreferences();const client=useQueryClient();
  const [pending,setPending]=useState(false);const [error,setError]=useState<MessageKey|null>(null);
  const query=useQuery({queryKey:["profile"],queryFn:()=>api<Profile>("/account/profile"),enabled:open,refetchOnWindowFocus:false});
  const profile=query.data;
  const ready=!!profile && Array.isArray(profile.fields) && !query.isFetching && !query.error;
  return <><SecurityEventsDrawer open={securityOpen} onClose={()=>setSecurityOpen(false)}/><EmailDrawer open={emailOpen} onClose={()=>setEmailOpen(false)}/><PhoneDrawer open={phoneOpen} onClose={()=>setPhoneOpen(false)}/><PasskeysDrawer open={passkeyOpen} onClose={()=>setPasskeyOpen(false)}/><ProfileValuesDrawer open={customOpen} onClose={()=>setCustomOpen(false)}/><FormDrawer title={t("profile")} open={open} formId="account-profile" pending={pending} submitDisabled={!ready} onClose={()=>{setError(null);setDirty(false);onClose();}}>
    <ProfileCompletion open={open}/>
    {open&&<AvatarEditor/>}
    <Button className="mb-4" disabled={pending} onClick={()=>setSecurityOpen(true)}>{t("securityEvents")}</Button>
    <Button className="mb-4" disabled={pending} onClick={()=>setEmailOpen(true)}>{t("accountEmail")}</Button>
    <Button className="mb-4" disabled={pending} onClick={()=>setPhoneOpen(true)}>{t("accountPhone")}</Button>
    <Button className="mb-4" disabled={pending} onClick={()=>setPasskeyOpen(true)}>{t("passkeyCredentials")}</Button>
    <FeedbackBubble id="account-profile-error" content={error ? t(error) : query.error ? t("unavailable") : undefined} />
    {query.isFetching && <Spin />}
    <Button className="mb-4" disabled={pending} onClick={()=>{const proceed=()=>{setDirty(false);onClose();setCustomOpen(true);};if(dirty)modal.confirm({title:t("unsavedProfileHint"),onOk:proceed});else proceed();}}>{t("fieldCustomGroup")}</Button>
    {ready && profile && <Form key={profile.version} id="account-profile" layout="vertical" disabled={pending} onValuesChange={()=>setDirty(true)} initialValues={profile} onFinish={async values=>{
      setPending(true);setError(null);
      try {
        await mutate("/account/profile","PUT",{...values,version:profile.version,schemaVersion:profile.schemaVersion});
        setDirty(false);onClose();await Promise.all([client.invalidateQueries({queryKey:["profile"]}),client.invalidateQueries({queryKey:["profile-completion"]}),client.invalidateQueries({queryKey:["session"]}),client.invalidateQueries({queryKey:["users"]})]);
      } catch(e) {const key=e instanceof ApiError?e.message:"unavailable";setError(key==="staleRecord" || key==="invalidProfile"?key:"unavailable");} finally {setPending(false);}
    }}>
      <Form.Item label={t("username")}><Input value={profile.username} disabled /></Form.Item>
      {[...Map.groupBy(profile.fields,field=>field.groupKey)].map(([group,fields])=><fieldset key={group} className="mb-4 border-0 p-0"><legend>{fields[0].groupLabel}</legend>{fields.map(field=>{
       const key=field.key==='birthDate'?'birthMonth':field.key;
       const label=field.label&&field.label!==builtInDefaultLabels[field.key]?field.label:t(builtInLabelKeys[field.key]);
       return <Form.Item key={field.key} name={key} label={label} extra={field.description||undefined} rules={[{required:field.required,whitespace:true,message:t('invalidProfile')},{max:200,message:t('nameTooLong')}]}><Input type={field.key==='birthDate'?'month':'text'} maxLength={200} placeholder={field.placeholder}/></Form.Item>;
      })}</fieldset>)}
    </Form>}
  </FormDrawer></>;
}
