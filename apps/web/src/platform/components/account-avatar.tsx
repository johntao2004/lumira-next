"use client";
import {useEffect,useState} from "react";
import {Avatar} from "antd";
import {UserOutlined} from "@ant-design/icons";
import {useQuery} from "@tanstack/react-query";
import {api,apiBlob,getAuthorizationContext} from "../api/client";
import {accountAvatarKey,type AccountAvatarData} from "../api/account-avatar-contract";
import {usePreferences} from "../providers";

export {accountAvatarKey} from "../api/account-avatar-contract";
export type {AccountAvatarData} from "../api/account-avatar-contract";
export function useAccountAvatar(enabled=true){
 return useQuery({queryKey:accountAvatarKey,enabled,queryFn:({signal})=>api<AccountAvatarData>("/account/avatar",{signal}),refetchOnWindowFocus:true});
}
function avatarContentKey(fileId:string|null,version:number|null,revision:string|null){
 return [...accountAvatarKey,"content",fileId,version,revision] as const;
}
/** One query and rendering path for the header, rail and profile editor. */
export function AccountAvatar({size=32}:{size?:number}){
 const {data}=useAccountAvatar();const {t}=usePreferences();
 const revision=getAuthorizationContext()?.revision??null;
 const hasAvatar=Boolean(data?.fileId||data?.generated);
 const contentKey=avatarContentKey(data?.fileId??null,data?.version??null,revision);
 const content=useQuery({
  queryKey:contentKey,
  enabled:hasAvatar&&typeof data?.version==="number",
  queryFn:({signal})=>apiBlob(`/account/avatar/content?version=${data!.version}`,{signal}),
 });
 const contentKeyId=JSON.stringify(contentKey);
 const [contentSource,setContentSource]=useState<{key:string;url:string}|null>(null);
 useEffect(()=>{
  if(!hasAvatar||!content.data){setContentSource(null);return;}
  const url=URL.createObjectURL(content.data);
  setContentSource({key:contentKeyId,url});
  return()=>URL.revokeObjectURL(url);
 },[content.data,contentKeyId,hasAvatar]);
 const src=hasAvatar&&contentSource?.key===contentKeyId?contentSource.url:undefined;
 return <Avatar size={size} alt={t("avatar")} icon={<UserOutlined/>} src={src}/>;
}
