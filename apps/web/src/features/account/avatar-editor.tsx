"use client";
import {useRef,useState} from "react";
import {App,Space} from "antd";
import {useQuery,useQueryClient} from "@tanstack/react-query";
import {api,ApiError,mutate,upload} from "@/platform/api/client";
import {AccountAvatar,accountAvatarKey,useAccountAvatar,type AccountAvatarData} from "@/platform/components/account-avatar";
import {AsyncButton} from "@/platform/components/async-button";
import {DeleteAction} from "@/platform/components/delete-action";
import {QueryFailure} from "@/platform/components/query-failure";
import {usePreferences} from "@/platform/providers";
import type {MessageKey} from "@/platform/i18n/messages";
import {FeedbackBubble} from "@/platform/components/feedback-bubble";

export function AvatarEditor(){
 const {t}=usePreferences(),{message}=App.useApp(),client=useQueryClient(),query=useAccountAvatar();
 const schema=useQuery({queryKey:["account-profile-fields"],queryFn:({signal})=>api<{data:{fields:{key:string;label:string;description:string;visible:boolean;required:boolean}[]}}>("/account/profile-fields",{signal}),staleTime:0,refetchOnWindowFocus:true});
 const rule=schema.data?.data.fields.find(field=>field.key==="avatarUrl");
 const input=useRef<HTMLInputElement>(null),busy=useRef(false);
 const [pending,setPending]=useState<"upload"|"remove"|null>(null),[error,setError]=useState<MessageKey|null>(null);
 async function change(kind:"upload"|"remove",file?:File){
  const account=query.data;
  if(busy.current||!account||!schema.data||schema.error||rule?.visible===false||(kind==="remove"&&rule?.required))return;
  if(kind==="upload"&&!file)return;
  if(file&&file.size>5*1024*1024){setError("avatarTooLarge");return;}
  busy.current=true;setPending(kind);setError(null);
  try{
   const path=`/account/avatar?version=${account.version}`;
   let next: AccountAvatarData;
   if(kind==="upload") {
    if(!file)return;
    next=await upload<AccountAvatarData>(path,file);
   } else next=await mutate<AccountAvatarData>(path,"DELETE");
   client.setQueryData(accountAvatarKey,next);void message.success(t(kind==="upload"?"avatarUpdated":"avatarRemoved"));
  }catch(e){
   const code=e instanceof ApiError?e.message:"unavailable";
   const keys:Record<string,MessageKey>={avatarRequired:"avatarRequired",forbidden:"forbidden",invalidImage:"invalidAvatar",unsupportedImage:"invalidAvatar",imageTooLarge:"avatarTooLarge",imageDimensionsExceeded:"avatarDimensions",staleRecord:"staleRecord",fileQuotaExceeded:"fileQuotaExceeded",uploadRateLimited:"uploadRateLimited",uploadTimeout:"fileUploadExpired"};
   setError(keys[code]??"unavailable");
  }finally{
   await Promise.all([client.invalidateQueries({queryKey:accountAvatarKey}),client.invalidateQueries({queryKey:["files"]}),client.invalidateQueries({queryKey:["profile-completion"]}),client.invalidateQueries({queryKey:["account-profile-fields"]})]);
   busy.current=false;setPending(null);
  }
 }
 if(rule?.visible===false)return null;
 return <section aria-label={t("avatar")} className="mb-4">
  {rule&&<p>{rule.label}{rule.required?" *":""}{rule.description?` — ${rule.description}`:""}</p>}
  <Space wrap align="center"><AccountAvatar size={64}/>
   <input ref={input} type="file" accept="image/png,image/jpeg" hidden onChange={event=>{const file=event.target.files?.[0];event.target.value="";if(file)void change("upload",file);}}/>
   <AsyncButton label={t("changeAvatar")} pending={pending==="upload"} disabled={!!pending||!query.data||!!query.error||!schema.data||!!schema.error} onClick={()=>input.current?.click()}/>
   {query.data?.fileId&&<DeleteAction name={t("avatar")} actionLabel={t("removeAvatar")} disabled={!!pending||!!query.error||!schema.data||!!schema.error||rule?.required} onDelete={()=>change("remove")}/>}
  </Space>
  <p>{t("avatarHint")}</p>
  {rule?.required&&<p>{t("avatarRequired")}</p>}
  {schema.error&&<QueryFailure title={t("unavailable")} pending={schema.isFetching} onRetry={()=>void schema.refetch()}/>}
  {query.error&&<QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={()=>void query.refetch()}/>}
  <FeedbackBubble id="account-avatar-error" content={error ? t(error) : undefined}/>
 </section>;
}
