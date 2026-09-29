"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {useRef,useState} from "react";
import {Input} from "antd";
import {useQueryClient} from "@tanstack/react-query";
import {PlatformDrawer} from "../components/platform-drawer";
import {NewPasswordFields} from "../components/new-password-fields";
import {AsyncButton} from "../components/async-button";
import {usePreferences} from "../providers";
import {ApiError,mutate} from "../api/client";
import type {MessageKey} from "../i18n/messages";
import {FeedbackBubble} from "../components/feedback-bubble";
const errors:Record<string,MessageKey>={invalidRecoveryRequest:"usernameRequired",invalidRecoveryToken:"invalidRecoveryToken",passwordRecoveryLimited:"passwordRecoveryLimited",passwordPolicy:"passwordPolicy",passwordMustDiffer:"passwordMustDiffer",smtpUnavailable:"emailSmtpUnavailable",smtpPasswordRequired:"emailSmtpUnavailable",secretKeyUnavailable:"emailSmtpUnavailable"};
export function PasswordRecoveryDrawer({open,onClose,onRecovered}:{open:boolean;onClose:()=>void;onRecovered:(username:string)=>void}){
 const {t,locale}=usePreferences(),client=useQueryClient(),[form]=Form.useForm();
 const busy=useRef(false),[pending,setPending]=useState<"request"|"confirm"|null>(null);
 const [error,setError]=useState<MessageKey|null>(null),[requested,setRequested]=useState(false);
 function close(){if(busy.current)return;form.resetFields();setError(null);setRequested(false);onClose();}
 async function submit(kind:"request"|"confirm",values:Record<string,string>){
  if(busy.current)return;busy.current=true;setPending(kind);setError(null);
  try{
   if(kind==="request"){
    await mutate("/auth/password-recovery/request","POST",{username:values.username,locale});setRequested(true);form.setFieldValue("token",undefined);
   }else{
    await mutate("/auth/password-recovery/confirm","POST",{username:values.username,token:values.token,newPassword:values.newPassword});
    client.clear();form.resetFields();setRequested(false);onRecovered(values.username);
   }
  }catch(e){setError(e instanceof ApiError&&e.status===403?"forbidden":errors[e instanceof ApiError?e.message:""]??"unavailable");}
  finally{if(kind==="confirm"){form.setFieldsValue({newPassword:undefined,confirmation:undefined});}busy.current=false;setPending(null);}
 }
 return <PlatformDrawer title={t("forgotPassword")} open={open} size={440} destroyOnHidden closable={{disabled:!!pending}} onClose={close}>
  <FeedbackBubble kind="success" id="password-recovery-requested" content={requested ? t("recoveryRequested") : undefined}/>
  <FeedbackBubble id="password-recovery-error" content={error ? t(error) : undefined}/>
  <Form name="password-recovery" form={form} layout="vertical" disabled={!!pending} onValuesChange={values=>{if(Object.hasOwn(values,"username")){setRequested(false);form.setFieldValue("token",undefined);}}} onFinish={values=>submit("confirm",values)}>
   <Form.Item name="username" label={t("username")} extra={t("recoveryHint")} rules={[{required:true,whitespace:true,message:t("usernameRequired")},{max:100,message:t("nameTooLong")}]}><Input autoComplete="username" maxLength={100}/></Form.Item>
   <AsyncButton className="mb-4" label={t("requestRecoveryCode")} pending={pending==="request"} disabled={!!pending} onClick={()=>{void form.validateFields(["username"]).then(values=>submit("request",values)).catch(()=>{});}}/>
   <Form.Item name="token" label={t("emailCode")} rules={[{required:true,pattern:/^[A-Za-z0-9_-]{43}$/,message:t("invalidRecoveryToken")}]}><Input autoComplete="one-time-code" maxLength={43} spellCheck={false}/></Form.Item>
   <NewPasswordFields/>
   <AsyncButton block type="primary" htmlType="submit" label={t("resetPassword")} pending={pending==="confirm"} disabled={!!pending}/>
  </Form>
 </PlatformDrawer>;
}
