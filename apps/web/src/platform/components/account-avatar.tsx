"use client";
import {Avatar} from "antd";
import {UserOutlined} from "@ant-design/icons";
import {useQuery} from "@tanstack/react-query";
import {api} from "../api/client";
import {usePreferences} from "../providers";

export type AccountAvatarData={fileId:string|null;version:number};
export const accountAvatarKey=["account-avatar"] as const;
export function useAccountAvatar(enabled=true){
 return useQuery({queryKey:accountAvatarKey,enabled,queryFn:({signal})=>api<AccountAvatarData>("/account/avatar",{signal}),refetchOnWindowFocus:true});
}
/** One query and rendering path for the header, rail and profile editor. */
export function AccountAvatar({size=32}:{size?:number}){
 const {data}=useAccountAvatar();const {t}=usePreferences();
 return <Avatar size={size} alt={t("avatar")} icon={<UserOutlined/>} src={data?.fileId?`/api/account/avatar/content?version=${data.version}`:undefined}/>;
}
