"use client";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import {PlatformDrawer} from "@/platform/components/platform-drawer";

import {useState} from "react";
import {Input, Typography} from "antd";
import {useQuery,useQueryClient} from "@tanstack/react-query";
import {api,mutate} from "@/platform/api/client";
import {AsyncButton} from "@/platform/components/async-button";
import {DrawerFormActions} from "@/platform/components/drawer-actions";
import {QueryFailure} from "@/platform/components/query-failure";
import {usePreferences} from "@/platform/providers";
import {formatDateTime} from "@/platform/i18n/format";
import type {MessageKey} from "@/platform/i18n/messages";
import {FeedbackBubble} from "@/platform/components/feedback-bubble";
import {MAX_PASSWORD_BYTES} from "@/platform/auth/password-contract";
import {useContactVerificationAction} from "./use-contact-verification-action";

type EmailState={email:string|null;verifiedAt:string|null;version:number;pendingEmail:string|null;expiresAt:string|null;requestedAt:string|null};
const queryKey=["account-email"];
const errorKeys:Record<string,MessageKey>={invalidEmail:"invalidEmail",wrongCurrentPassword:"wrongCurrentPassword",staleRecord:"staleRecord",emailAlreadyVerified:"emailAlreadyVerified",emailVerificationLimited:"emailVerificationLimited",invalidVerificationToken:"invalidVerificationToken",emailUnavailable:"emailUnavailable",smtpUnavailable:"emailSmtpUnavailable",secretKeyUnavailable:"emailSmtpUnavailable",forbidden:"forbidden"};

export function EmailDrawer({open,onClose}:{open:boolean;onClose:()=>void}){
 const {t,locale}=usePreferences(),client=useQueryClient();
 const [requestForm]=Form.useForm(),[confirmForm]=Form.useForm();
 const [notice,setNotice]=useState<MessageKey|null>(null);
 const contact=useContactVerificationAction({queryKey,requestForm,errorKeys,onError:()=>setNotice(null)});
 const {action,pendingStep}=contact;
 const pending=action.pending;
 const query=useQuery({queryKey,queryFn:({signal})=>api<EmailState>("/account/email",{signal}),enabled:open,staleTime:0,refetchOnWindowFocus:true});
 const ready=!!query.data&&!query.error&&!query.isFetching;
 async function submit(kind:"request"|"confirm",values:Record<string,string>){
  const account=query.data;
  if(action.isPending()||!ready||!account)return;
  setNotice(null);
  await contact.run(kind,async()=>{
   if(kind==="request"){
    const next=await mutate<EmailState>("/account/email/request","POST",{...values,version:account.version,locale});
    client.setQueryData(queryKey,next);confirmForm.resetFields();setNotice("emailQueued");
   }else{
    await mutate("/account/email/confirm","POST",values);confirmForm.resetFields();setNotice("emailVerified");
   }
  });
 }
 function close(){if(action.isPending())return;requestForm.resetFields();confirmForm.resetFields();action.setError(null);setNotice(null);onClose();}
 return <PlatformDrawer title={t("accountEmail")} open={open} closable={{disabled:!!pending}} onClose={close} destroyOnHidden size={440} footer={<DrawerFormActions formId="account-email-confirm" onClose={close} pending={!!pending} submitDisabled={!ready||!query.data?.pendingEmail} submitLabel={t("confirmEmail")}/> }>
  <Typography.Paragraph>{t("emailIndependentHint")}</Typography.Paragraph>
  {query.error&&<QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={()=>void query.refetch()}/>}
  {query.data&&!query.error&&<>
   <Typography.Paragraph style={{overflowWrap:"anywhere"}}>{t("verifiedEmail")}: {query.data.email??t("emailNotVerified")}</Typography.Paragraph>
   {query.data.pendingEmail&&<Typography.Paragraph style={{overflowWrap:"anywhere"}}>{t("pendingEmail")}: {query.data.pendingEmail}<br/>{t("mailExpires")}: {formatDateTime(query.data.expiresAt,locale,{dateStyle:"medium",timeStyle:"short"})}</Typography.Paragraph>}
  </>}
  <FeedbackBubble kind="success" id="account-email-success" content={notice ? t(notice) : undefined}/>
  <FeedbackBubble id="account-email-error" content={action.error ? t(action.error) : undefined}/>
  <Form name="account-email-request" form={requestForm} layout="vertical" disabled={pending||!ready} onFinish={values=>submit("request",values)}>
   <Form.Item name="email" label={t("accountEmail")} rules={[{required:true,type:"email",message:t("invalidEmail")},{max:254,message:t("invalidEmail")}]}><Input type="email" autoComplete="email" maxLength={254}/></Form.Item>
   <Form.Item name="currentPassword" label={t("currentPassword")} rules={[{required:true,message:t("passwordRequired")}]}><Input.Password autoComplete="current-password" maxLength={MAX_PASSWORD_BYTES}/></Form.Item>
   <AsyncButton htmlType="submit" label={t("requestEmailCode")} pending={pendingStep==="request"} disabled={pending||!ready}/>
  </Form>
  <Form id="account-email-confirm" name="account-email-confirm" className="mt-6" form={confirmForm} layout="vertical" disabled={pending||!ready||!query.data?.pendingEmail} onFinish={values=>submit("confirm",values)}>
   <Form.Item name="token" label={t("emailCode")} rules={[{required:true,pattern:/^[A-Za-z0-9_-]{43}$/,message:t("invalidVerificationToken")}]}><Input autoComplete="one-time-code" maxLength={43} spellCheck={false}/></Form.Item>
  </Form>
 </PlatformDrawer>;
}
