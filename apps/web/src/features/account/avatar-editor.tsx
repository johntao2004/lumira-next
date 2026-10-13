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
import {localizeProfileFieldLabel} from "@/platform/profile-fields/localization";
import {FeedbackBubble} from "@/platform/components/feedback-bubble";
import {useSubmitAction} from "@/platform/forms/use-submit-action";

const avatarErrorKeys:Record<string,MessageKey>={avatarRequired:"avatarRequired",forbidden:"forbidden",invalidImage:"invalidAvatar",unsupportedImage:"invalidAvatar",imageTooLarge:"avatarTooLarge",imageDimensionsExceeded:"avatarDimensions",staleRecord:"staleRecord",fileQuotaExceeded:"fileQuotaExceeded",uploadRateLimited:"uploadRateLimited",uploadTimeout:"fileUploadExpired"};

function avatarError(error:unknown):MessageKey{
 const code=error instanceof ApiError?error.message:"";
 return avatarErrorKeys[code]??"unavailable";
}

export function AvatarEditor({size=64}:{size?:number}){
 const {t}=usePreferences(),{message}=App.useApp(),client=useQueryClient(),query=useAccountAvatar();
 const schema=useQuery({queryKey:["account-profile-fields"],queryFn:({signal})=>api<{data:{fields:{key:string;label:string;custom?:boolean;description:string;visible:boolean;required:boolean}[]}}>("/account/profile-fields",{signal}),staleTime:0,refetchOnWindowFocus:true});
 const rule=schema.data?.data.fields.find(field=>field.key==="avatarUrl");
 const input=useRef<HTMLInputElement>(null);
 const [activeAction,setActiveAction]=useState<"upload"|"remove"|null>(null);
 const action=useSubmitAction({mapError:avatarError});
 async function change(kind:"upload"|"remove",file?:File){
  const account=query.data;
  if(action.isPending()||!account||!schema.data||schema.error||rule?.visible===false||(kind==="remove"&&rule?.required))return;
  if(kind==="upload"&&!file)return;
  if(file&&file.size>5*1024*1024){action.setError("avatarTooLarge");return;}
  setActiveAction(kind);
  await action.run(async()=>{
   let failure:unknown;
   try{
    const path=`/account/avatar?version=${account.version}`;
    let next:AccountAvatarData;
    if(kind==="upload"){
     if(!file)return false;
     next=await upload<AccountAvatarData>(path,file);
    }else next=await mutate<AccountAvatarData>(path,"DELETE");
    client.setQueryData(accountAvatarKey,next);
    void message.success(t(kind==="upload"?"avatarUpdated":"avatarRemoved"));
   }catch(cause){failure=cause;}
   const refreshes=await Promise.allSettled([
    client.invalidateQueries({queryKey:accountAvatarKey}),
    client.invalidateQueries({queryKey:["files"]}),
    client.invalidateQueries({queryKey:["profile-completion"]}),
    client.invalidateQueries({queryKey:["account-profile-fields"]}),
   ]);
   if(failure)throw failure;
   const refreshFailure=refreshes.find(result=>result.status==="rejected");
   if(refreshFailure?.status==="rejected")throw refreshFailure.reason;
  });
  setActiveAction(null);
 }
 if(rule?.visible===false)return null;
 const profileLayout=size>=144;
 const label=rule?localizeProfileFieldLabel(rule,t):t("avatar");
 return <section aria-label={t("avatar")} className={profileLayout?"account-profile-avatar-editor":"mb-4"}>
  {rule&&<p>{label}{rule.required?" *":""}{rule.description?` — ${rule.description}`:""}</p>}
  <div className={profileLayout?"account-profile-avatar-preview":undefined}><AccountAvatar size={size}/></div>
  <Space wrap align="center">
   <input ref={input} type="file" accept="image/png,image/jpeg" hidden style={{display:"none"}} onChange={event=>{const file=event.target.files?.[0];event.target.value="";if(file)void change("upload",file);}}/>
   <AsyncButton label={t("changeAvatar")} pending={activeAction==="upload"} disabled={action.pending||!query.data||!!query.error||!schema.data||!!schema.error} onClick={()=>input.current?.click()}/>
   {query.data?.fileId&&<DeleteAction name={t("avatar")} actionLabel={t("removeAvatar")} disabled={action.pending||!!query.error||!schema.data||!!schema.error||rule?.required} onDelete={()=>change("remove")}/>}
  </Space>
  <p>{t("avatarHint")}</p>
  {rule?.required&&<p>{t("avatarRequired")}</p>}
  {schema.error&&<QueryFailure title={t("unavailable")} pending={schema.isFetching} onRetry={()=>void schema.refetch()}/>}
  {query.error&&<QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={()=>void query.refetch()}/>}
  <FeedbackBubble id="account-avatar-error" content={action.errorMessage}/>
 </section>;
}
