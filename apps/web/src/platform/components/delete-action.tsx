"use client";
import { useState } from "react";
import { App, Button, Popconfirm } from "antd";
import { ApiError } from "../api/client";
import { messageKeyFor } from "../api/errors";
import { usePreferences } from "../providers";
import type { MessageKey } from "../i18n/messages";

const deleteErrorKeys = ["staleRecord","dictionaryInUse","dictionaryReferenced","dictionaryItemReferenced","dictionaryItemInUse","roleInUse","organizationInUse","sessionNotFound","forbidden","protectedAccount"] as const satisfies readonly MessageKey[];

/** Shared confirmation, pending state, and error feedback for versioned deletions. */
export function DeleteAction({name,onDelete,actionLabel,confirmation,disabled=false}:{name:string;onDelete:()=>Promise<void>;actionLabel?:string;confirmation?:string;disabled?:boolean}) {
  const {t}=usePreferences();
  const {message}=App.useApp();
  const [pending,setPending]=useState(false);
  return <Popconfirm getPopupContainer={trigger=>trigger.parentElement ?? document.body} title={confirmation??t("deleteConfirm",{name})} okText={actionLabel??t("deleteRecord")} cancelText={t("cancel")} okButtonProps={{danger:true,loading:pending,"aria-label":actionLabel??t("deleteRecord"),"aria-busy":pending}} cancelButtonProps={{disabled:pending}} onConfirm={async()=>{
    if(pending||disabled)return;setPending(true);
    try {await onDelete();}
    catch(error) {
      const code=error instanceof ApiError?(error.message==="fileStateConflict"?"staleRecord":error.message):"unavailable";
      void message.error(t(messageKeyFor(code,deleteErrorKeys)));
    } finally {setPending(false);}
  }} disabled={disabled}><Button type="link" danger disabled={pending||disabled}>{actionLabel??t("deleteRecord")}</Button></Popconfirm>;
}
